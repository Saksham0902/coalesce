package com.coalesce.domain.match;

/**
 * The verdict on one scored pair.
 *
 * <h2>Why three outcomes rather than two</h2>
 *
 * <p>A binary match/no-match decision has to place a single cut point somewhere on the score
 * distribution, and the region around that cut point is exactly where the classifier has no
 * information. Every borderline pair is therefore forced to be wrong in one of two directions: a false
 * merge, which fuses two people into one record and is very hard to undo cleanly, or a false split,
 * which leaves a duplicate in the system. The two errors are not symmetric in cost — in KYC screening a
 * false merge can clear a sanctioned party by attaching them to a clean identity, and in patient
 * matching it can attach one person's allergies to another's chart.
 *
 * <p>{@link #REVIEW} names the uncertain region instead of guessing in it. Pairs in the band are
 * durably recorded, surfaced to a human queue, and — critically — <b>never unioned automatically</b>.
 * That preserves the option value of the decision: a steward can resolve it later with evidence the
 * engine does not have, and the pair stays inspectable in the meantime.
 *
 * <p>The cost is real and should be stated: the review band is unbounded operational work. A band wide
 * enough to catch every ambiguous pair can queue more pairs than a team can clear, at which point the
 * queue is a backlog rather than a control. Band width is therefore a capacity decision as much as a
 * quality one, which is why both thresholds are schema configuration rather than constants.
 *
 * <p>The rejected alternative was a single threshold plus a confidence score, leaving consumers to
 * decide. That pushes the same judgement call to every caller, and callers reliably resolve it by
 * treating anything above the threshold as truth.
 */
public enum MatchDecision {

    /** Same entity, with enough evidence to merge without asking anyone. */
    MATCH,

    /** Plausibly the same entity; queued for a human. Never merged by the engine. */
    REVIEW,

    /** Not the same entity, as far as the available evidence shows. */
    NO_MATCH;

    /**
     * Whether the engine may union this pair on its own.
     *
     * <p>Exists so that no call site has to re-derive the rule with {@code == MATCH}. The one place
     * that decides which decisions are auto-mergeable is here, because a future {@code MATCH_STRONG}
     * tier would otherwise need every comparison in the codebase found and updated.
     */
    public boolean autoMergeable() {
        return this == MATCH;
    }

    /** Whether this pair belongs in the steward queue. */
    public boolean needsReview() {
        return this == REVIEW;
    }
}
