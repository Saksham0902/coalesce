package com.coalesce.domain.link;

import com.coalesce.domain.model.RecordId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of a steward asserting that two records are different entities.
 *
 * <h2>Why this can partially fail</h2>
 *
 * <p>Retracting the direct link between the two records is easy. Honouring the assertion is not,
 * because the records may still be joined through a chain: separating A from D does nothing to A~B~C~D.
 * Making the assertion structurally true would mean choosing a set of links to cut — a minimum cut on
 * the cluster's link graph, weighted by score — and cutting links the steward never examined and may
 * well believe in.
 *
 * <p>So the assertion is recorded and enforced going forward ({@link LinkLedger} refuses any future
 * merge across it, which is what stops re-resolution from undoing it), and the residual path is
 * reported instead of being silently tolerated. The caller surfaces it as a conflict, with the actual
 * chain listed, so the steward decides which link is wrong. Automating the cut is the proper fix and is
 * deliberately not attempted: guessing which of a steward's links to destroy is worse than asking.
 *
 * @param assertion      the negative link now on record
 * @param retractedDirect the direct joining link that was removed, if one existed
 * @param residualPath   the surviving chain still connecting the two records, empty when the assertion
 *                       is fully satisfied. Populated means the clustering does not yet reflect it.
 */
public record SeparationResult(EntityLink assertion, Optional<EntityLink> retractedDirect,
                               List<RecordId> residualPath) {

    public SeparationResult {
        Objects.requireNonNull(assertion, "assertion");
        Objects.requireNonNull(retractedDirect, "retractedDirect");
        residualPath = List.copyOf(residualPath);
    }

    /** True when the two records are now in different clusters, as the steward intended. */
    public boolean enforced() {
        return residualPath.isEmpty();
    }
}
