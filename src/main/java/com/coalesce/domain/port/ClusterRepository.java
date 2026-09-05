package com.coalesce.domain.port;

import com.coalesce.domain.cluster.EntityCluster;
import com.coalesce.domain.model.RecordId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for the derived cluster assignment.
 *
 * <p>This table is a <b>cache</b>, and the API is shaped to say so. {@link #replaceAll} is the only
 * write: a resolution run recomputes the assignment wholesale and swaps it, because incremental
 * cluster updates would require knowing which clusters a link changed, which is the state the link
 * graph already holds authoritatively. There is deliberately no {@code save(cluster)}, because
 * mutating one cached cluster in isolation is how a cache drifts from its source.
 *
 * <p>It exists at all only for read performance: answering "which cluster contains this record" from
 * the link graph means a traversal, and an API serving that per request should not traverse. The
 * trade-off is staleness between runs, which is acceptable for a lookup and would not be for a merge
 * decision — so nothing in the resolution path reads from here.
 */
public interface ClusterRepository {

    /** Atomically swaps the cached assignment for a freshly computed one. */
    void replaceAll(Collection<EntityCluster> clusters);

    Optional<EntityCluster> findContaining(RecordId id);

    List<EntityCluster> findAll();

    long count();
}
