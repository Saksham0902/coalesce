package com.coalesce.domain.cluster;

import com.coalesce.domain.model.RecordId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A resolved entity: the set of source records believed to describe one real-world thing.
 *
 * <h2>Why the id is the smallest member id rather than the union-find root</h2>
 *
 * <p>The union-find representative is whichever node won by rank, which depends on the order links
 * were applied. Feed the same links in a different order — a different blocking strategy ordering, a
 * parallel run, a re-run after adding one record — and the same cluster gets a different id. Every
 * downstream system holding that id would then need to re-key, and cluster-id diffs between benchmark
 * runs would be pure noise.
 *
 * <p>Taking the lexicographically smallest member id makes the id a pure function of the membership
 * set: same members, same id, always. The trade-off is that adding a record with a smaller id renames
 * the cluster, so this is a stable content hash rather than a durable surrogate key. A production
 * system needs a real surrogate key plus a history table mapping it through merges and splits; that is
 * a schema this project does not have, and the property being bought here — reproducible output — is
 * the one that matters for a benchmark harness.
 *
 * @param clusterId  derived from membership; see above for why it is not a surrogate key
 * @param members    every record in the cluster, sorted, never empty
 * @param computedAt when this membership was derived, since clusters are a cache over the link set
 */
public record EntityCluster(String clusterId, List<RecordId> members, Instant computedAt) {

    public EntityCluster {
        Objects.requireNonNull(clusterId, "clusterId");
        Objects.requireNonNull(members, "members");
        Objects.requireNonNull(computedAt, "computedAt");
        if (members.isEmpty()) {
            throw new IllegalArgumentException("a cluster with no members has no meaning");
        }
        members = List.copyOf(members);
    }

    public static EntityCluster of(Collection<RecordId> members, Instant computedAt) {
        List<RecordId> sorted = new ArrayList<>(members);
        sorted.sort((a, b) -> {
            int bySource = a.source().compareTo(b.source());
            return bySource != 0 ? bySource : a.local().compareTo(b.local());
        });
        return new EntityCluster(sorted.get(0).qualified(), sorted, computedAt);
    }

    public int size() {
        return members.size();
    }

    public boolean isSingleton() {
        return members.size() == 1;
    }
}
