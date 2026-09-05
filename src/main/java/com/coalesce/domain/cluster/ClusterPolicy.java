package com.coalesce.domain.cluster;

import com.coalesce.domain.match.CandidatePair;
import com.coalesce.domain.match.MatchDecision;
import com.coalesce.domain.match.ScoredPair;
import com.coalesce.domain.model.RecordId;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Decides whether a pairwise match is allowed to become a cluster merge.
 *
 * <h2>The problem this exists to solve</h2>
 *
 * <p>{@link UnionFind} computes transitive closure, and transitive closure is not a correct resolution
 * policy. Similarity is not transitive: "Jon Smith, London" resembles "John Smith, London", which
 * resembles "John Smyth, London", which resembles "Joan Smyth, London". Each link is individually
 * defensible and the endpoints are different people. Run that over a real dataset and the failure is
 * not a few bad merges — it is a phase transition. Once enough weak links exist, one connected
 * component swallows a large fraction of the data, and because every merge was individually plausible,
 * nothing in the logs looks wrong. This is the single most common way a naive entity resolution system
 * fails in production.
 *
 * <p>Thresholding harder does not fix it. Raising the match threshold until no chain forms also
 * discards the true matches that are genuinely borderline, which is trading a catastrophic precision
 * failure for a large recall failure. The chain has to be broken with a property that individual pair
 * scores cannot see: what the resulting cluster looks like.
 *
 * <h2>The two guards</h2>
 *
 * <p><b>Cohesion</b> is the real defence. Before merging clusters A and B, sample record pairs across
 * the boundary and require their mean similarity to clear a threshold. In an A~B~C~D chain, merging
 * {A,B,C} with {D} forces (A,D) and (B,D) to be scored — the comparisons that transitive closure never
 * makes — and their low scores refuse the merge. This is a local approximation of a correlation
 * clustering objective: rather than optimising global cluster quality, which is NP-hard, it enforces a
 * local density constraint greedily.
 *
 * <p><b>Maximum cluster size</b> is a blunt circuit breaker for what cohesion misses. It exists because
 * cohesion is sampled rather than exhaustive and can be defeated by a dense enough blob of
 * near-identical junk records — a source system emitting thousands of {@code "TEST TEST"} rows will
 * form a genuinely cohesive cluster that is nonetheless wrong. A ceiling turns that into a refusal and
 * an alert instead of a 40,000-member entity. It is an admission that the statistical guard is not
 * sufficient, which is why both are here.
 *
 * <h2>Sampling: the accuracy/cost trade-off, stated plainly</h2>
 *
 * <p>Checking every cross-boundary pair is |A|x|B| comparisons. Merging into a 500-member cluster one
 * record at a time then costs 500 comparisons for that link alone, and the total across a run is
 * quadratic in cluster size — the same cost blocking was introduced to eliminate, reintroduced at the
 * clustering stage. Sampling k pairs makes each decision O(k) and the run linear in links.
 *
 * <p>What is given up: the sampled mean is an estimate, with standard error roughly s/sqrt(k). A small
 * sample can miss a minority of bad cross pairs, so a merge that exhaustive checking would refuse can
 * pass. The exposure is bounded — a pair must be unlucky in the sample <em>and</em> clear the pairwise
 * threshold — and the size ceiling caps the damage when it happens. Sample size is configuration
 * because the right value depends on cluster size distribution, which is a property of the data rather
 * than of the algorithm.
 *
 * <p>Sampling is a <b>deterministic stride</b> over the cross-product index space rather than random.
 * Reproducibility is worth more than statistical purity here: when a steward asks why two records did
 * not merge, the answer has to be the same today as it was in the batch that produced it. Random
 * sampling would make refusals unreproducible unless the seed were persisted per decision. The cost is
 * a known bias — striding can correlate with the order records were merged in, so it does not sample
 * uniformly at random from the boundary. Reservoir sampling with a persisted per-link seed is the
 * proper fix and is not implemented.
 *
 * <h2>Manual separations outrank everything</h2>
 *
 * <p>If a steward has asserted that any record in A is not the same entity as any record in B, the
 * merge is refused regardless of score. Enforcing it here rather than at the pair level is what makes
 * it survive transitive closure: blocking only the asserted pair would still let A and B merge through
 * a third record, quietly undoing the human's decision. See {@code LinkLedger} for the durability side
 * of the same argument.
 *
 * <p>Stateless with respect to the clustering: {@link #evaluate} never mutates {@link ClusterState}.
 * The caller applies the union when the verdict allows it, so a verdict can be computed, logged, or
 * explained without changing anything.
 */
public final class ClusterPolicy {

    /**
     * Supplies the similarity of an arbitrary record pair, on demand.
     *
     * <p>Cohesion needs scores for pairs that blocking never proposed — that is the entire point, since
     * the pairs transitive closure skips are the ones that reveal a bad chain. An interface rather than
     * a direct dependency on the scorer keeps this class free of schemas and comparators, so the chain
     * tests can drive it with a hand-written score table and no matching stack at all.
     *
     * <p>Returns empty for a pair that cannot be scored, following the same
     * absence-is-not-disagreement rule as {@code AttributeComparator}.
     */
    public interface ScoreOracle {
        OptionalDouble score(RecordId left, RecordId right);
    }

    /**
     * The set of records a given record has been manually asserted to differ from.
     *
     * <p>Indexed by record rather than exposed as a pair predicate so that the cross-boundary check
     * costs O(|smaller cluster| + matches) instead of O(|A|x|B|) predicate calls.
     */
    public interface Separations {

        Set<RecordId> separatedFrom(RecordId id);

        static Separations none() {
            return id -> Set.of();
        }
    }

    /**
     * @param maxClusterSize      ceiling on merged cluster size; the circuit breaker
     * @param cohesionThreshold   minimum sampled cross-boundary mean similarity, in {@code [0,1]}.
     *                            Normally set below the match threshold: cross-boundary pairs are
     *                            expected to be weaker than the link that proposed the merge, and
     *                            requiring them to be as strong would refuse almost every merge of two
     *                            multi-record clusters.
     * @param cohesionSampleSize  cross pairs to sample per decision
     */
    public record Config(int maxClusterSize, double cohesionThreshold, int cohesionSampleSize) {

        public Config {
            if (maxClusterSize < 2) {
                throw new IllegalArgumentException(
                        "maxClusterSize below 2 forbids all merging, was " + maxClusterSize);
            }
            if (!Double.isFinite(cohesionThreshold) || cohesionThreshold < 0.0 || cohesionThreshold > 1.0) {
                throw new IllegalArgumentException("cohesionThreshold must be within [0,1], was " + cohesionThreshold);
            }
            if (cohesionSampleSize < 1) {
                throw new IllegalArgumentException(
                        "cohesionSampleSize must be at least 1, was " + cohesionSampleSize);
            }
        }

        /**
         * Defaults chosen for person-level resolution: a person is rarely represented by more than a
         * few dozen source records, and 12 samples keep the standard error of the cohesion estimate
         * around 0.1 for typical score spreads while staying cheap.
         */
        public static Config defaults() {
            return new Config(50, 0.60, 12);
        }
    }

    private final Config config;
    private final ScoreOracle oracle;
    private final Separations separations;

    public ClusterPolicy(Config config, ScoreOracle oracle) {
        this(config, oracle, Separations.none());
    }

    public ClusterPolicy(Config config, ScoreOracle oracle, Separations separations) {
        this.config = Objects.requireNonNull(config, "config");
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        this.separations = Objects.requireNonNull(separations, "separations");
    }

    /**
     * Vets one candidate link against the clusters it would join.
     *
     * <p>Checks run cheapest-and-most-decisive first: the pair's own decision, then redundancy, then
     * the human assertion that cannot be overridden, then the size ceiling, and only then the cohesion
     * sample, which is the only check that costs comparisons.
     */
    public LinkVerdict evaluate(ScoredPair scored, ClusterState state) {
        CandidatePair pair = scored.pair();

        if (scored.decision() != MatchDecision.MATCH) {
            LinkVerdict.Outcome outcome = scored.decision() == MatchDecision.REVIEW
                    ? LinkVerdict.Outcome.REVIEW_REQUIRED
                    : LinkVerdict.Outcome.NOT_A_MATCH;
            return LinkVerdict.refuse(pair, outcome,
                    "pair scored %.3f, decision %s".formatted(scored.score(), scored.decision()),
                    state.clusterSize(pair.left()));
        }

        if (state.connected(pair.left(), pair.right())) {
            return new LinkVerdict(pair, LinkVerdict.Outcome.ALREADY_CONNECTED,
                    "already one cluster of %d records".formatted(state.clusterSize(pair.left())),
                    OptionalDouble.empty(), state.clusterSize(pair.left()));
        }

        List<RecordId> leftCluster = state.members(pair.left());
        List<RecordId> rightCluster = state.members(pair.right());
        int mergedSize = leftCluster.size() + rightCluster.size();

        RecordId[] separated = findSeparation(leftCluster, rightCluster);
        if (separated != null) {
            return LinkVerdict.refuse(pair, LinkVerdict.Outcome.MANUALLY_SEPARATED,
                    "a steward asserted %s and %s are different entities".formatted(
                            separated[0].qualified(), separated[1].qualified()),
                    mergedSize);
        }

        if (mergedSize > config.maxClusterSize()) {
            return LinkVerdict.refuse(pair, LinkVerdict.Outcome.MAX_CLUSTER_SIZE,
                    "merging %d and %d records would exceed the ceiling of %d".formatted(
                            leftCluster.size(), rightCluster.size(), config.maxClusterSize()),
                    mergedSize);
        }

        Cohesion cohesion = sampleCohesion(pair, leftCluster, rightCluster);
        if (cohesion.sampled() == 0) {
            // Nothing across the boundary could be scored — either both clusters are singletons, so the
            // only cross pair is the link itself, or no sampled pair shared a comparable attribute.
            // Refusing here would be inventing disagreement out of missing data, the same error the
            // comparator contract exists to prevent.
            return LinkVerdict.allow(pair, "no corroborating cross-cluster pair was scoreable",
                    OptionalDouble.empty(), mergedSize);
        }
        if (cohesion.mean() < config.cohesionThreshold()) {
            return LinkVerdict.refuseOnCohesion(pair,
                    "cross-cluster cohesion %.3f over %d sampled pairs is below the threshold of %.3f"
                            .formatted(cohesion.mean(), cohesion.sampled(), config.cohesionThreshold()),
                    cohesion.mean(), mergedSize);
        }

        return LinkVerdict.allow(pair,
                "cross-cluster cohesion %.3f over %d sampled pairs".formatted(cohesion.mean(), cohesion.sampled()),
                OptionalDouble.of(cohesion.mean()), mergedSize);
    }

    private RecordId[] findSeparation(List<RecordId> leftCluster, List<RecordId> rightCluster) {
        List<RecordId> smaller = leftCluster.size() <= rightCluster.size() ? leftCluster : rightCluster;
        Set<RecordId> larger = Set.copyOf(smaller == leftCluster ? rightCluster : leftCluster);
        for (RecordId candidate : smaller) {
            for (RecordId forbidden : separations.separatedFrom(candidate)) {
                if (larger.contains(forbidden)) {
                    return new RecordId[] {candidate, forbidden};
                }
            }
        }
        return null;
    }

    /**
     * Mean similarity of up to {@code cohesionSampleSize} pairs drawn across the boundary.
     *
     * <p>The candidate link itself is excluded. Including it would let a merge corroborate itself: for
     * two singletons the link is the only cross pair, so cohesion would equal the pair score, which
     * already cleared a higher threshold. The check would then always pass and measure nothing.
     */
    private Cohesion sampleCohesion(CandidatePair pair, List<RecordId> leftCluster, List<RecordId> rightCluster) {
        long total = (long) leftCluster.size() * rightCluster.size();
        int width = rightCluster.size();

        // Stride the index space so samples spread across both clusters instead of clustering in the
        // first rows. Coprime-ish striding is not attempted; see the class note on sampling bias.
        long stride = Math.max(1L, total / config.cohesionSampleSize());

        double sum = 0.0;
        int sampled = 0;
        for (long index = 0; index < total && sampled < config.cohesionSampleSize(); index += stride) {
            RecordId a = leftCluster.get((int) (index / width));
            RecordId b = rightCluster.get((int) (index % width));
            if (a.equals(pair.left()) && b.equals(pair.right())
                    || a.equals(pair.right()) && b.equals(pair.left())) {
                continue;
            }
            OptionalDouble score = oracle.score(a, b);
            if (score.isPresent()) {
                sum += score.getAsDouble();
                sampled++;
            }
        }
        return new Cohesion(sampled == 0 ? 0.0 : sum / sampled, sampled);
    }

    private record Cohesion(double mean, int sampled) {
    }
}
