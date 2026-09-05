package com.coalesce.domain.cluster;

import com.coalesce.domain.match.CandidatePair;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Why a candidate link was or was not allowed to merge two clusters.
 *
 * <h2>Why this is not a boolean</h2>
 *
 * <p>"Why are these two records not merged?" is the most common question a data steward asks, and a
 * boolean cannot answer it. Worse, the answers are operationally different in kind: a pair refused for
 * insufficient cohesion is a matching-quality signal worth investigating, a pair refused because a
 * steward asserted they are different people is correct behaviour that must not be re-litigated, and a
 * pair refused on cluster size is a capacity guard that probably means an upstream data quality problem
 * is producing a runaway merge chain. Collapsing those into {@code false} guarantees somebody
 * eventually re-derives the distinction by reading resolver source code.
 *
 * <p>The measured cohesion is carried because the number is the answer: "refused, cross-cluster
 * cohesion 0.42 against a threshold of 0.70" tells a steward both what happened and how far off it was,
 * which is enough to decide whether to override or to fix the data.
 *
 * @param pair              the link under consideration
 * @param outcome           what was decided
 * @param detail            human-readable specifics, including the offending values where relevant
 * @param measuredCohesion  the sampled cross-boundary mean, present only when cohesion was measured
 * @param resultingClusterSize size the merged cluster would have had, or the existing size when the
 *                          link was refused before that could be established
 */
public record LinkVerdict(
        CandidatePair pair,
        Outcome outcome,
        String detail,
        OptionalDouble measuredCohesion,
        int resultingClusterSize) {

    /**
     * The distinct reasons a link is or is not applied.
     *
     * <p>{@link #ALREADY_CONNECTED} is separated from the refusals because it is not one: the two
     * records are already in the same cluster, so the link is redundant rather than rejected. Counting
     * it as a refusal would make a healthy run look like it was fighting its own policy.
     */
    public enum Outcome {

        /** Merged. */
        ALLOWED,

        /** Redundant — transitively already the same cluster. Not a refusal. */
        ALREADY_CONNECTED,

        /** Score fell in the review band; a human decides, and the engine must not pre-empt them. */
        REVIEW_REQUIRED,

        /** Score below the review band. */
        NOT_A_MATCH,

        /** A steward asserted that two records across this boundary are different entities. */
        MANUALLY_SEPARATED,

        /** The merged cluster would exceed the configured ceiling. */
        MAX_CLUSTER_SIZE,

        /** Sampled cross-boundary similarity did not clear the cohesion threshold. */
        INSUFFICIENT_COHESION;

        /** Whether the policy actively declined, as opposed to merging or finding nothing to do. */
        public boolean isRefusal() {
            return this != ALLOWED && this != ALREADY_CONNECTED;
        }
    }

    public LinkVerdict {
        Objects.requireNonNull(pair, "pair");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(measuredCohesion, "measuredCohesion");
    }

    public boolean allowed() {
        return outcome == Outcome.ALLOWED;
    }

    public boolean refused() {
        return outcome.isRefusal();
    }

    static LinkVerdict allow(CandidatePair pair, String detail, OptionalDouble cohesion, int resultingSize) {
        return new LinkVerdict(pair, Outcome.ALLOWED, detail, cohesion, resultingSize);
    }

    static LinkVerdict refuse(CandidatePair pair, Outcome outcome, String detail, int size) {
        return new LinkVerdict(pair, outcome, detail, OptionalDouble.empty(), size);
    }

    static LinkVerdict refuseOnCohesion(CandidatePair pair, String detail, double cohesion, int size) {
        return new LinkVerdict(pair, Outcome.INSUFFICIENT_COHESION, detail, OptionalDouble.of(cohesion), size);
    }

    @Override
    public String toString() {
        return "%s %s: %s".formatted(pair, outcome, detail);
    }
}
