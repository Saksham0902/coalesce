package com.coalesce.domain.link;

import com.coalesce.domain.model.RecordId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What retracting one link did to the cluster it belonged to.
 *
 * <p>{@code split} is false surprisingly often, and that is the interesting part rather than a
 * degenerate case: in a densely linked cluster the retracted edge is one of several paths between the
 * two records, so the membership is unchanged. A steward who expected a split needs to be told that
 * their retraction had no structural effect and which other links are holding the cluster together,
 * otherwise they will assume the operation failed.
 *
 * @param retracted the link that was removed
 * @param affected  the cluster's membership before retraction — the scope that had to be recomputed,
 *                  which is the whole reason un-merge is affordable
 * @param resulting the connected components of that membership afterwards; one entry when no split
 *                  occurred, two when the link was a cut edge, and more only if the caller had already
 *                  left the cluster disconnected
 */
public record UnmergeResult(EntityLink retracted, Set<RecordId> affected, List<Set<RecordId>> resulting) {

    public UnmergeResult {
        Objects.requireNonNull(retracted, "retracted");
        affected = Set.copyOf(affected);
        resulting = List.copyOf(resulting);
    }

    public boolean split() {
        return resulting.size() > 1;
    }
}
