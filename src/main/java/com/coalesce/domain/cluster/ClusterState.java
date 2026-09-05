package com.coalesce.domain.cluster;

import com.coalesce.domain.model.RecordId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link UnionFind} plus the one thing a merge policy needs that union-find deliberately does not
 * provide: the members of a cluster, cheaply.
 *
 * <h2>Why this exists as a separate type</h2>
 *
 * <p>{@link ClusterPolicy} has to sample record pairs across the boundary between two clusters before
 * allowing them to merge, which means it needs both membership lists. Union-find answers "same
 * cluster?" in near-constant time but cannot enumerate a cluster without walking every element —
 * {@code UnionFind.clusters()} is O(n) per call, so calling it once per candidate link makes a run
 * quadratic in records, which is exactly the cost blocking exists to avoid.
 *
 * <p>Pushing membership tracking into {@code UnionFind} was the rejected alternative. It would have
 * added memory for every caller, including those that only ask about connectivity, and would have put
 * policy-serving concerns inside the graph algorithm — the separation that keeps both testable alone.
 *
 * <h2>Weighted merge, and why it is not a micro-optimisation</h2>
 *
 * <p>Membership lists merge smaller-into-larger, so any one element is copied at most log n times and
 * total membership work is O(n log n). Always appending B's list to A's is O(n) per merge and quadratic
 * overall, and it degrades in exactly the case that matters: one large cluster absorbing records one at
 * a time, which is what a real resolution run looks like.
 *
 * <p>The union-find root and the larger membership list can belong to opposite sides, because
 * union-find picks by rank and this picks by size. The merged list is therefore re-keyed to whichever
 * root union-find chose, so the two structures agree on identity while disagreeing on which side won.
 *
 * <p>Not thread-safe: a resolution run owns its own instance.
 */
public final class ClusterState {

    private final UnionFind<RecordId> sets = new UnionFind<>();
    private final Map<RecordId, List<RecordId>> membersByRoot = new HashMap<>();
    private final Set<RecordId> known = new HashSet<>();

    public ClusterState() {
    }

    public ClusterState(Collection<RecordId> records) {
        records.forEach(this::add);
    }

    /** Registers a record as a singleton cluster. Idempotent, so callers need not track what they added. */
    public void add(RecordId id) {
        if (!known.add(id)) {
            return;
        }
        sets.add(id);
        List<RecordId> singleton = new ArrayList<>();
        singleton.add(id);
        membersByRoot.put(id, singleton);
    }

    public boolean connected(RecordId a, RecordId b) {
        return known.contains(a) && known.contains(b) && sets.connected(a, b);
    }

    public RecordId representative(RecordId id) {
        add(id);
        return sets.find(id);
    }

    public int clusterSize(RecordId id) {
        add(id);
        return sets.clusterSize(id);
    }

    /** Members of {@code id}'s cluster, including {@code id} itself. */
    public List<RecordId> members(RecordId id) {
        add(id);
        return List.copyOf(membersByRoot.get(sets.find(id)));
    }

    /**
     * @return true if a merge happened; false if the two were already in one cluster. Callers use the
     *         distinction so that redundant links are not counted as work performed.
     */
    public boolean union(RecordId a, RecordId b) {
        add(a);
        add(b);
        RecordId rootA = sets.find(a);
        RecordId rootB = sets.find(b);
        if (rootA.equals(rootB)) {
            return false;
        }

        List<RecordId> listA = membersByRoot.get(rootA);
        List<RecordId> listB = membersByRoot.get(rootB);
        List<RecordId> larger = listA.size() >= listB.size() ? listA : listB;
        List<RecordId> smaller = larger == listA ? listB : listA;
        larger.addAll(smaller);

        sets.union(a, b);
        membersByRoot.remove(rootA);
        membersByRoot.remove(rootB);
        membersByRoot.put(sets.find(a), larger);
        return true;
    }

    public int clusterCount() {
        return membersByRoot.size();
    }

    public int recordCount() {
        return known.size();
    }

    /** Every cluster as representative-to-members. For reporting and assertions, not the inner loop. */
    public Map<RecordId, List<RecordId>> clusters() {
        Map<RecordId, List<RecordId>> snapshot = new LinkedHashMap<>();
        membersByRoot.forEach((root, members) -> snapshot.put(root, List.copyOf(members)));
        return snapshot;
    }
}
