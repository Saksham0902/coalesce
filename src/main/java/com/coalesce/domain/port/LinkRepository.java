package com.coalesce.domain.port;

import com.coalesce.domain.link.EntityLink;
import com.coalesce.domain.match.CandidatePair;
import com.coalesce.domain.model.RecordId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Persistence for pairwise assertions.
 *
 * <p>The distinction between {@link #findActive()} and {@link #findAll()} is the audit trail. A
 * retracted link is not deleted — it is marked, so that "who un-merged these and when" remains
 * answerable. Rebuilding the cluster graph reads only the active links; an investigation reads
 * everything. Hard-deleting on retraction would make the operation cheaper and the system
 * unauditable, which for a component that decides whether two people are the same person is the wrong
 * trade.
 */
public interface LinkRepository {

    /** Upserts by canonical pair, so re-resolving a pair supersedes its previous link rather than duplicating it. */
    void saveAll(Collection<EntityLink> links);

    /** Links currently in force: not retracted. This is what the cluster graph is rebuilt from. */
    List<EntityLink> findActive();

    /** Every link ever recorded, retracted ones included. For audit, not for clustering. */
    List<EntityLink> findAll();

    List<EntityLink> findInvolving(RecordId id);

    /** Marks a link retracted without losing it. Silently does nothing if the pair is unknown. */
    void markRetracted(CandidatePair pair, Instant at);

    long countActive();
}
