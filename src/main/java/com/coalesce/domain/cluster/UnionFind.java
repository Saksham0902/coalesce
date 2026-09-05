package com.coalesce.domain.cluster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Disjoint-set forest with path halving and union by rank, used to turn pairwise match decisions into
 * entity clusters.
 *
 * <h2>Why this structure</h2>
 *
 * <p>Matching produces edges: "record A is the same as record B". Resolution needs connected components
 * of that graph. The obvious alternatives are worse in specific ways: repeatedly running BFS is O(V+E)
 * per query, and materialising a {@code Map<Entity, Set<Record>>} and merging sets on every link is
 * O(n) per merge in the worst case, which turns a batch of a million links into quadratic work. Union-find
 * gives near-constant amortised cost — O(α(n)), where α is the inverse Ackermann function and is under 5
 * for any input that fits in the universe.
 *
 * <h2>The two optimisations, and why both are needed</h2>
 *
 * <p><b>Union by rank</b> attaches the shallower tree beneath the deeper one, so the forest cannot be
 * degenerated into a linked list by an adversarial merge order. <b>Path halving</b> points each node at
 * its grandparent during traversal, flattening the tree as a side effect of reading it. Either alone
 * gives O(log n); together they give the inverse-Ackermann bound. Implementations that skip path
 * compression are the usual reason a union-find "works but is slow" on real data.
 *
 * <p>Path halving is used rather than full two-pass compression because it flattens nearly as well in a
 * single pass, with no recursion and no second traversal — which matters here because {@code find} is
 * called far more often than {@code union}.
 *
 * <h2>The transitive closure trap</h2>
 *
 * <p>This class deliberately does <i>only</i> transitive closure, and that is not by itself a correct
 * resolution policy. If A matches B and B matches C, this will place A and C in one cluster even when A
 * and C are obviously different people. Chains of individually-plausible links are how naive entity
 * resolution collapses an entire dataset into one giant cluster.
 *
 * <p>Guarding against that is a policy decision and therefore lives outside this structure, in
 * {@code ClusterPolicy}, which vets a candidate link against the cluster it would create before the
 * union is allowed to happen. Keeping the graph algorithm free of policy is what makes both testable.
 *
 * <p>Not thread-safe: a resolution run owns its own instance.
 *
 * @param <T> element type; must have value-based {@code equals} and {@code hashCode}
 */
public final class UnionFind<T> {

    private static final int INITIAL_CAPACITY = 16;

    private final Map<T, Integer> slots = new HashMap<>();
    private final List<T> elements = new ArrayList<>();

    private int[] parent = new int[INITIAL_CAPACITY];
    private int[] rank = new int[INITIAL_CAPACITY];
    private int[] size = new int[INITIAL_CAPACITY];

    private int setCount;

    /** Registers an element as its own singleton set. Idempotent, so callers need not track what they have added. */
    public void add(T element) {
        slot(element);
    }

    /** @return the canonical representative of {@code element}'s set */
    public T find(T element) {
        return elements.get(root(slot(element)));
    }

    public boolean connected(T left, T right) {
        return root(slot(left)) == root(slot(right));
    }

    /**
     * Merges the sets containing the two elements.
     *
     * @return true if a merge happened; false if they were already in the same set. The caller needs
     *         this distinction: an already-connected pair is a redundant link, and counting those as
     *         merges would overstate how much work a resolution run actually did.
     */
    public boolean union(T left, T right) {
        int a = root(slot(left));
        int b = root(slot(right));
        if (a == b) {
            return false;
        }

        // Attach the shallower tree under the deeper one. On equal rank the choice is arbitrary, but the
        // winner's rank must then increase — that is the only place rank ever grows.
        if (rank[a] < rank[b]) {
            int swap = a;
            a = b;
            b = swap;
        }
        parent[b] = a;
        size[a] += size[b];
        if (rank[a] == rank[b]) {
            rank[a]++;
        }
        setCount--;
        return true;
    }

    /** Size of the set containing {@code element}, without materialising the set. */
    public int clusterSize(T element) {
        return size[root(slot(element))];
    }

    /** Number of distinct sets, including singletons. */
    public int setCount() {
        return setCount;
    }

    public int elementCount() {
        return elements.size();
    }

    /**
     * Materialises every set as representative-to-members.
     *
     * <p>O(n) and allocates the whole result, so this is for reporting and assertions rather than for the
     * inner loop of a resolution run. Insertion-ordered so that output is stable across runs, which makes
     * benchmark diffs readable.
     */
    public Map<T, List<T>> clusters() {
        Map<T, List<T>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < elements.size(); i++) {
            T representative = elements.get(root(i));
            grouped.computeIfAbsent(representative, key -> new ArrayList<>()).add(elements.get(i));
        }
        return grouped;
    }

    private int root(int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    private int slot(T element) {
        Objects.requireNonNull(element, "element");
        Integer existing = slots.get(element);
        if (existing != null) {
            return existing;
        }

        int index = elements.size();
        ensureCapacity(index + 1);
        slots.put(element, index);
        elements.add(element);
        parent[index] = index;
        rank[index] = 0;
        size[index] = 1;
        setCount++;
        return index;
    }

    private void ensureCapacity(int required) {
        if (required <= parent.length) {
            return;
        }
        int grown = Math.max(required, parent.length * 2);
        parent = java.util.Arrays.copyOf(parent, grown);
        rank = java.util.Arrays.copyOf(rank, grown);
        size = java.util.Arrays.copyOf(size, grown);
    }
}
