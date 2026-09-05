package com.coalesce.domain.match;

import com.coalesce.domain.model.SourceRecord;
import com.coalesce.domain.schema.ResolutionSchema;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Turns two records and a schema into a score and a decision.
 *
 * <h2>Renormalisation, and why it is the whole point</h2>
 *
 * <p>The score is the weighted mean of the per-attribute similarities <b>over the attributes that were
 * actually comparable</b>, not over every attribute the schema configures. If a schema weights five
 * attributes and a pair shares three, the score divides by the weight of those three.
 *
 * <p>The alternative — scoring an absent attribute as 0.0 and dividing by the full weight — is the
 * standard mistake, and it is worse than it looks. It does not add noise uniformly; it penalises
 * precisely the sparse records, which are disproportionately the ones that need matching, because a
 * record with half its fields empty is usually the one that got typed in twice. A pair that agrees
 * perfectly on name, date of birth and national id would score 0.6 solely for not having an address on
 * file, and would then fall below any sensibly-set threshold. See {@code AttributeComparator} for the
 * contract that makes the distinction available at all.
 *
 * <h2>The failure mode renormalisation creates, and the guard for it</h2>
 *
 * <p>Renormalising over present attributes means the denominator shrinks with the evidence, so
 * <b>confidence is no longer tied to how much was known</b>. Two records that share only
 * {@code country="US"} score 1.0 — a perfect, meaningless match. In a real dataset this is not a corner
 * case: sparse records cluster together, and unguarded renormalisation merges them into one giant
 * component of "records we know nothing about".
 *
 * <p>Two guards, both schema configuration, close it:
 *
 * <ul>
 *   <li>{@code minContributingAttributes} — a minimum count of comparable attributes.</li>
 *   <li>{@code minEvidenceWeightShare} — a minimum fraction of the schema's total weight. Needed
 *       separately because count alone treats three weak attributes as stronger than one decisive one,
 *       which inverts what the weights were written to say.</li>
 * </ul>
 *
 * <p>A pair that clears {@code matchThreshold} but fails a guard is demoted to {@link
 * MatchDecision#REVIEW}, not to {@code NO_MATCH}. It looks like a match on the evidence available; what
 * is missing is evidence, and a human with access to more of it is the right resolution. Demoting it to
 * {@code NO_MATCH} would be treating absence as disagreement — the same error, one layer up.
 *
 * <h2>Known limitation: the model is a linear weighted mean</h2>
 *
 * <p>Weights are hand-set, independent, and additive. The Fellegi-Sunter model instead learns
 * per-attribute agreement and disagreement likelihoods (m and u probabilities) and sums log-likelihood
 * ratios, which is both better calibrated and able to say that agreement on a rare surname is worth
 * more than agreement on a common one. It needs labelled data or EM over the candidate set, which is a
 * different project shape; the weighted mean is chosen here because it is inspectable and requires no
 * training corpus, and its limits are being stated rather than hidden. Attribute interactions are not
 * modelled at all: agreement on both first and last name is treated as exactly the sum of its parts.
 *
 * <p>Stateless and safe for concurrent use, given a thread-safe registry.
 */
public final class PairScorer {

    private final ComparatorRegistry comparators;

    public PairScorer(ComparatorRegistry comparators) {
        this.comparators = Objects.requireNonNull(comparators, "comparators");
    }

    public ScoredPair score(ResolutionSchema schema, SourceRecord left, SourceRecord right) {
        return score(schema, CandidatePair.of(left.id(), right.id()), left, right);
    }

    /**
     * Scores a pair whose canonical identity the caller already holds.
     *
     * <p>{@code pair} is passed in rather than rebuilt so that the record arguments may arrive in
     * either order without the resulting {@code ScoredPair} changing identity — the blocking stage
     * produces the pair first and looks the records up afterwards.
     */
    public ScoredPair score(ResolutionSchema schema, CandidatePair pair, SourceRecord left, SourceRecord right) {
        Map<String, Double> contributions = new LinkedHashMap<>();
        Set<String> absent = new LinkedHashSet<>();

        double weightedSum = 0.0;
        double presentWeight = 0.0;

        for (AttributeComparatorSpec spec : schema.attributes()) {
            Optional<String> a = left.attribute(spec.attribute());
            Optional<String> b = right.attribute(spec.attribute());
            if (a.isEmpty() || b.isEmpty()) {
                absent.add(spec.attribute());
                continue;
            }

            OptionalDouble similarity = comparators.require(spec.comparator()).compare(a.get(), b.get());
            if (similarity.isEmpty()) {
                // Present but uninterpretable — "n/a" in a date column. Indistinguishable from missing
                // as far as evidence goes, so it is treated identically rather than scored as 0.
                absent.add(spec.attribute());
                continue;
            }

            double value = similarity.getAsDouble();
            if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                // The contract says [0,1]. A comparator that breaks it would skew every score it
                // touches, so it is named here rather than allowed to distort the run quietly.
                throw new IllegalStateException("comparator '%s' returned %s for attribute '%s', outside [0,1]"
                        .formatted(spec.comparator(), value, spec.attribute()));
            }

            contributions.put(spec.attribute(), value);
            weightedSum += spec.weight() * value;
            presentWeight += spec.weight();
        }

        if (presentWeight == 0.0) {
            // No shared comparable attribute at all. Not a mismatch — an unscoreable pair. It is
            // reported as NO_MATCH with a score of zero because the engine must not merge it, but the
            // empty breakdown is what tells a reviewer that nothing was actually compared.
            return new ScoredPair(pair, 0.0, contributions, absent, 0.0, MatchDecision.NO_MATCH);
        }

        double score = clampToUnit(weightedSum / presentWeight);
        double evidenceShare = clampToUnit(presentWeight / schema.totalWeight());
        MatchDecision decision = decide(schema, score, contributions.size(), evidenceShare);
        return new ScoredPair(pair, score, contributions, absent, evidenceShare, decision);
    }

    private static MatchDecision decide(ResolutionSchema schema, double score, int contributing,
                                        double evidenceShare) {
        if (score < schema.reviewThreshold()) {
            return MatchDecision.NO_MATCH;
        }
        if (score < schema.matchThreshold()) {
            return MatchDecision.REVIEW;
        }
        boolean enoughEvidence = contributing >= schema.minContributingAttributes()
                && evidenceShare >= schema.minEvidenceWeightShare();
        return enoughEvidence ? MatchDecision.MATCH : MatchDecision.REVIEW;
    }

    /**
     * Guards against floating-point drift only. The weighted mean of values in {@code [0,1]} is
     * mathematically in {@code [0,1]}, but summing and dividing doubles can land a hair outside, and
     * {@code ScoredPair} rejects out-of-range scores.
     */
    private static double clampToUnit(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
