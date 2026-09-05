package com.coalesce.domain.match;

import com.coalesce.domain.model.RecordId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The full result of comparing two records: the score, the decision, and the evidence both were
 * derived from.
 *
 * <h2>Why the breakdown is part of the value, not a debug log</h2>
 *
 * <p>The question a data steward actually asks is never "what was the score" — it is "why". A bare
 * {@code double} cannot answer it, and reconstructing the answer later requires re-running the
 * comparison against a schema version that may have moved on. Carrying the per-attribute similarities
 * and the list of attributes that could not be compared makes every decision explainable at the moment
 * it was made, which is also what makes the review queue usable: a reviewer sees "surname 0.94, date of
 * birth 1.0, address absent" rather than "0.83".
 *
 * <p>{@link #absentAttributes()} is deliberately as prominent as the scores. It is the difference
 * between "these two disagree on address" and "neither record has an address", which are opposite
 * conclusions that a single number cannot distinguish.
 *
 * @param pair                 the two records, canonically ordered
 * @param score                weighted mean similarity over the comparable attributes only, in {@code [0,1]}
 * @param attributeScores      similarity per attribute that was comparable on both records
 * @param absentAttributes     attributes the schema asked for that could not be compared, because one
 *                             side was missing the value or a comparator could not interpret it
 * @param evidenceWeightShare  fraction of the schema's total weight that {@code attributeScores}
 *                             represents; 1.0 means the pair was scored on every configured attribute
 * @param decision             what the engine will do with this pair
 */
public record ScoredPair(
        CandidatePair pair,
        double score,
        Map<String, Double> attributeScores,
        Set<String> absentAttributes,
        double evidenceWeightShare,
        MatchDecision decision) {

    public ScoredPair {
        Objects.requireNonNull(pair, "pair");
        Objects.requireNonNull(decision, "decision");
        if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be within [0,1], was " + score);
        }
        // Sorted copies: these are read by humans in the review queue and diffed between benchmark
        // runs, and hash-ordered output makes both harder than they need to be.
        attributeScores = Map.copyOf(new TreeMap<>(attributeScores));
        absentAttributes = Set.copyOf(new TreeSet<>(absentAttributes));
    }

    public RecordId left() {
        return pair.left();
    }

    public RecordId right() {
        return pair.right();
    }

    /** How many attributes actually carried evidence, as opposed to how many the schema configured. */
    public int contributingAttributes() {
        return attributeScores.size();
    }

    /**
     * One-line justification suitable for a review queue or an API response.
     *
     * <p>Formatted rather than structured because the consumers are a human and a log line; anything
     * that needs to compute on this should read the map instead of parsing the string.
     */
    public String explain() {
        StringBuilder text = new StringBuilder(decision.name())
                .append(" score=").append(String.format("%.3f", score))
                .append(" evidence=").append(String.format("%.0f%%", evidenceWeightShare * 100));
        attributeScores.forEach((attribute, similarity) ->
                text.append(' ').append(attribute).append('=').append(String.format("%.2f", similarity)));
        if (!absentAttributes.isEmpty()) {
            text.append(" absent=").append(absentAttributes);
        }
        return text.toString();
    }
}
