package com.coalesce.domain.link;

import com.coalesce.domain.cluster.ClusterPolicy;
import com.coalesce.domain.cluster.EntityCluster;
import com.coalesce.domain.error.NotFoundException;
import com.coalesce.domain.match.CandidatePair;
import com.coalesce.domain.match.MatchDecision;
import com.coalesce.domain.model.RecordId;
import com.coalesce.domain.schema.ResolutionSchema;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The append-only record of every pairwise assertion, and the authority on what the clusters currently
 * are.
 *
 * <h2>Clusters are derived here, not stored</h2>
 *
 * <p>Membership is computed from the surviving joining links by traversal, every time it is asked for.
 * That is more expensive than reading a {@code cluster_id} column and it is the only way un-merge can
 * work: see {@link EntityLink} for why storing the conclusion instead of the premises makes every merge
 * permanent. The cost is bounded by cluster size rather than dataset size, because the traversal only
 * ever walks one component.
 *
 * <h2>Why un-merge is possible at all</h2>
 *
 * <p>Transitive closure is not invertible — a cluster does not remember which links produced it, so
 * "remove record X from this cluster" has no well-defined answer. Retracting a <em>link</em> does:
 * delete that edge and recompute connected components. The key is that only one component can be
 * affected, since a link's endpoints are by definition in the same component and no edge crosses
 * component boundaries. So the rebuild is scoped to that component's members — O(cluster size), not
 * O(dataset). A 12-member cluster splitting in a database of 50 million records touches 12 records.
 *
 * <p>That is also why {@link #unmerge} takes two record ids rather than a cluster id and a record. It
 * is the honest operation: the steward is saying "this specific piece of evidence is wrong", which the
 * system can act on, rather than "this record does not belong", which it cannot.
 *
 * <h2>Human assertions outrank scores, enforced in two places</h2>
 *
 * <p>An automatic link that contradicts a manual separation is appended to history — provenance is
 * append-only, and the fact that the engine keeps proposing a merge is itself diagnostic — but is
 * refused effect. One check is not enough: this class blocks the direct link, and {@link ClusterPolicy}
 * blocks merges across any separated pair anywhere in the two clusters, which is what prevents
 * re-resolution from reconnecting them through a third record. Blocking only the direct link would let
 * the assertion be defeated transitively, which from the steward's point of view is the engine ignoring
 * them.
 *
 * <p>A later <em>manual</em> merge does clear an earlier separation. A steward correcting themselves is
 * the intended path; the rule is that scores never override people, not that decisions are immutable.
 *
 * <h2>Un-merge and separation are different operations, deliberately</h2>
 *
 * <p>{@link #unmerge} retracts evidence: the next resolution run may legitimately recreate that link,
 * because nothing recorded says the records are different. {@link #separate} asserts a fact that
 * survives every future run. Stewards need both, and collapsing them would mean either that every
 * retraction is permanent or that no assertion is.
 *
 * <p>Not thread-safe. Single-writer by design — see the class note in {@code ResolutionWorkflow} on
 * what would be needed to lift that.
 */
public final class LinkLedger implements ClusterPolicy.Separations {

    private final List<EntityLink> history = new ArrayList<>();
    private final Map<CandidatePair, EntityLink> joining = new LinkedHashMap<>();
    private final Map<CandidatePair, EntityLink> pendingReview = new LinkedHashMap<>();
    private final Map<CandidatePair, Instant> retracted = new LinkedHashMap<>();
    private final Map<RecordId, Set<RecordId>> separations = new HashMap<>();
    private final Map<RecordId, Set<RecordId>> adjacency = new HashMap<>();

    /**
     * Appends an assertion and applies it if it is allowed to take effect.
     *
     * @return whether the link changed the cluster graph or the separation set. False means the link is
     *         on record but inert: a review-band pair, which by definition must not merge anything, or
     *         an automatic merge across a manual separation.
     */
    public boolean record(EntityLink link) {
        Objects.requireNonNull(link, "link");
        history.add(link);
        CandidatePair pair = link.pair();

        if (link.separates()) {
            separations.computeIfAbsent(pair.left(), key -> new HashSet<>()).add(pair.right());
            separations.computeIfAbsent(pair.right(), key -> new HashSet<>()).add(pair.left());
            pendingReview.remove(pair);
            EntityLink existing = joining.remove(pair);
            if (existing != null) {
                detach(pair);
                retracted.put(pair, link.recordedAt());
            }
            return true;
        }

        if (link.decision() == MatchDecision.REVIEW) {
            pendingReview.put(pair, link);
            return false;
        }

        if (!link.joins()) {
            // An automatic NO_MATCH. Worth keeping — a pair the engine has considered and rejected is
            // the evidence that a later false merge was a regression — but it asserts nothing to apply.
            return false;
        }

        if (isSeparated(pair.left(), pair.right())) {
            if (!link.isManual()) {
                return false;
            }
            clearSeparation(pair);
        }

        pendingReview.remove(pair);
        retracted.remove(pair);
        joining.put(pair, link);
        adjacency.computeIfAbsent(pair.left(), key -> new LinkedHashSet<>()).add(pair.right());
        adjacency.computeIfAbsent(pair.right(), key -> new LinkedHashSet<>()).add(pair.left());
        return true;
    }

    public void recordAll(Collection<EntityLink> links) {
        links.forEach(this::record);
    }

    /**
     * Retracts one joining link and rebuilds only the affected cluster's components.
     *
     * @throws NotFoundException if no joining link exists for the pair. This is not defensive noise: the
     *         two records may well be in the same cluster through other links, and silently succeeding
     *         would tell the steward their retraction worked when nothing was removed.
     */
    public UnmergeResult unmerge(RecordId a, RecordId b, Instant at) {
        CandidatePair pair = CandidatePair.of(a, b);
        EntityLink link = joining.get(pair);
        if (link == null) {
            throw new NotFoundException("no joining link between " + a.qualified() + " and " + b.qualified()
                    + (connected(a, b) ? "; they are connected through other links, retract one of those" : ""));
        }

        Set<RecordId> affected = component(a);
        joining.remove(pair);
        detach(pair);
        retracted.put(pair, at);

        return new UnmergeResult(link, affected, componentsWithin(affected));
    }

    /** Records the durable assertion that two records are different entities. */
    public SeparationResult separate(RecordId a, RecordId b, ResolutionSchema schema, Instant at) {
        CandidatePair pair = CandidatePair.of(a, b);
        EntityLink existingDirect = joining.get(pair);
        EntityLink assertion = EntityLink.manualSeparation(a, b, schema, at);
        record(assertion);
        return new SeparationResult(assertion, Optional.ofNullable(existingDirect), pathBetween(a, b));
    }

    /** Records the assertion that two records are the same entity, overriding any earlier separation. */
    public EntityLink merge(RecordId a, RecordId b, ResolutionSchema schema, Instant at) {
        EntityLink assertion = EntityLink.manualMerge(a, b, schema, at);
        record(assertion);
        return assertion;
    }

    @Override
    public Set<RecordId> separatedFrom(RecordId id) {
        return Set.copyOf(separations.getOrDefault(id, Set.of()));
    }

    public boolean isSeparated(RecordId a, RecordId b) {
        return separations.getOrDefault(a, Set.of()).contains(b);
    }

    public boolean connected(RecordId a, RecordId b) {
        return a.equals(b) || component(a).contains(b);
    }

    /** The cluster containing {@code id}, computed from surviving links. A record with no links is alone. */
    public Set<RecordId> component(RecordId id) {
        Set<RecordId> visited = new LinkedHashSet<>();
        Deque<RecordId> frontier = new ArrayDeque<>();
        frontier.push(id);
        visited.add(id);
        while (!frontier.isEmpty()) {
            RecordId current = frontier.pop();
            for (RecordId neighbour : adjacency.getOrDefault(current, Set.of())) {
                if (visited.add(neighbour)) {
                    frontier.push(neighbour);
                }
            }
        }
        return visited;
    }

    /**
     * Every non-singleton cluster the links currently imply.
     *
     * <p>Records that were ingested but never linked do not appear, because the ledger only knows about
     * records that some link mentions. Callers wanting singletons included must union this with the
     * record set; {@code ResolutionWorkflow} does exactly that.
     */
    public List<EntityCluster> clusters(Instant at) {
        List<EntityCluster> clusters = new ArrayList<>();
        Set<RecordId> seen = new HashSet<>();
        for (RecordId id : adjacency.keySet()) {
            if (seen.contains(id)) {
                continue;
            }
            Set<RecordId> members = component(id);
            seen.addAll(members);
            clusters.add(EntityCluster.of(members, at));
        }
        return clusters;
    }

    /**
     * Shortest chain of joining links between two records, or empty if they are unconnected.
     *
     * <p>BFS rather than the union-find used during resolution, because the question here is not "are
     * they connected" but "through what" — and union-find cannot answer the second at all. It keeps
     * parent pointers by rank, which reconstruct nothing about the original edges. Reporting the actual
     * chain is what lets a steward see that A and D are joined via B and C and decide which link is the
     * wrong one.
     */
    public List<RecordId> pathBetween(RecordId from, RecordId to) {
        if (from.equals(to)) {
            return List.of(from);
        }
        Map<RecordId, RecordId> cameFrom = new HashMap<>();
        Deque<RecordId> queue = new ArrayDeque<>();
        queue.add(from);
        cameFrom.put(from, from);
        while (!queue.isEmpty()) {
            RecordId current = queue.poll();
            for (RecordId neighbour : adjacency.getOrDefault(current, Set.of())) {
                if (cameFrom.putIfAbsent(neighbour, current) != null) {
                    continue;
                }
                if (neighbour.equals(to)) {
                    return reconstruct(cameFrom, from, to);
                }
                queue.add(neighbour);
            }
        }
        return List.of();
    }

    public List<EntityLink> history() {
        return List.copyOf(history);
    }

    public List<EntityLink> joiningLinks() {
        return List.copyOf(joining.values());
    }

    /** Pairs awaiting a human decision, in the order they were scored. */
    public List<EntityLink> reviewQueue() {
        return List.copyOf(pendingReview.values());
    }

    public Map<CandidatePair, Instant> retractedPairs() {
        return Map.copyOf(retracted);
    }

    public int linkCount() {
        return joining.size();
    }

    private void clearSeparation(CandidatePair pair) {
        unlink(separations, pair.left(), pair.right());
        unlink(separations, pair.right(), pair.left());
    }

    private void detach(CandidatePair pair) {
        unlink(adjacency, pair.left(), pair.right());
        unlink(adjacency, pair.right(), pair.left());
    }

    private static void unlink(Map<RecordId, Set<RecordId>> index, RecordId key, RecordId value) {
        Set<RecordId> neighbours = index.get(key);
        if (neighbours == null) {
            return;
        }
        neighbours.remove(value);
        if (neighbours.isEmpty()) {
            // Dropping the empty entry keeps adjacency.keySet() an accurate list of linked records,
            // which clusters() iterates; a stale empty entry would emit a phantom singleton cluster.
            index.remove(key);
        }
    }

    /**
     * Connected components restricted to a known member set.
     *
     * <p>The restriction is the point. Recomputing every component in the database after one retraction
     * would be O(records + links); confining it to the members of the one cluster that could possibly
     * have changed makes un-merge an operation whose cost is set by the cluster, not the corpus.
     */
    private List<Set<RecordId>> componentsWithin(Set<RecordId> members) {
        List<Set<RecordId>> components = new ArrayList<>();
        Set<RecordId> seen = new HashSet<>();
        for (RecordId id : members) {
            if (seen.contains(id)) {
                continue;
            }
            Set<RecordId> component = component(id);
            seen.addAll(component);
            components.add(component);
        }
        return components;
    }

    private static List<RecordId> reconstruct(Map<RecordId, RecordId> cameFrom, RecordId from, RecordId to) {
        List<RecordId> reversed = new ArrayList<>();
        RecordId cursor = to;
        while (!cursor.equals(from)) {
            reversed.add(cursor);
            cursor = cameFrom.get(cursor);
        }
        reversed.add(from);
        List<RecordId> path = new ArrayList<>(reversed);
        java.util.Collections.reverse(path);
        return path;
    }
}
