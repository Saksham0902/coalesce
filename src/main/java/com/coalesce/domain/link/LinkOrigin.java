package com.coalesce.domain.link;

/**
 * Who created a link.
 *
 * <p>Recorded on every link because it determines precedence, not merely because it is interesting
 * provenance. A human assertion outranks any score the engine computes, and without this field the
 * next resolution run cannot tell its own previous output apart from a steward's correction — so it
 * would overwrite the correction and the steward would watch the same wrong merge reappear.
 */
public enum LinkOrigin {

    /** Produced by a resolution run from a schema and a score. Freely recomputed and superseded. */
    AUTOMATIC,

    /**
     * Asserted by a human. Never overridden by automatic scoring.
     *
     * <p>Paired with {@code NO_MATCH} this is the negative assertion "these are different entities",
     * which is the only durable way to stop a merge that the scores keep proposing.
     */
    MANUAL_OVERRIDE
}
