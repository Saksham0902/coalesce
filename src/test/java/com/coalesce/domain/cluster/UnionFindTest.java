package com.coalesce.domain.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UnionFindTest {

    @Test
    @DisplayName("merges sets and reports connectivity transitively")
    void transitiveConnectivity() {
        UnionFind<String> sets = new UnionFind<>();

        assertThat(sets.union("a", "b")).isTrue();
        assertThat(sets.union("b", "c")).isTrue();

        // The whole purpose of the structure: a link between a and c was never added, yet they are
        // connected. This is also precisely the behaviour that needs policing at a higher layer.
        assertThat(sets.connected("a", "c")).isTrue();
        assertThat(sets.find("a")).isEqualTo(sets.find("c"));
    }

    @Test
    @DisplayName("reports a redundant link as no merge")
    void redundantUnionReturnsFalse() {
        UnionFind<String> sets = new UnionFind<>();
        sets.union("a", "b");
        sets.union("b", "c");

        // a and c are already connected transitively, so linking them explicitly changes nothing. The
        // caller needs to know that, otherwise a resolution run overstates how many merges it performed.
        assertThat(sets.union("a", "c")).isFalse();
        assertThat(sets.setCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("tracks set count and cluster size without materialising sets")
    void countsAndSizes() {
        UnionFind<String> sets = new UnionFind<>();
        List.of("a", "b", "c", "d", "e").forEach(sets::add);
        assertThat(sets.setCount()).isEqualTo(5);

        sets.union("a", "b");
        sets.union("c", "d");
        assertThat(sets.setCount()).isEqualTo(3);
        assertThat(sets.clusterSize("a")).isEqualTo(2);
        assertThat(sets.clusterSize("e")).isEqualTo(1);

        sets.union("b", "c");
        assertThat(sets.setCount()).isEqualTo(2);
        assertThat(sets.clusterSize("d")).isEqualTo(4);
    }

    @Test
    @DisplayName("adding an element twice is idempotent")
    void addIsIdempotent() {
        UnionFind<String> sets = new UnionFind<>();
        sets.add("a");
        sets.add("a");

        assertThat(sets.elementCount()).isEqualTo(1);
        assertThat(sets.setCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("elements are interned on first reference, so find and union auto-register")
    void implicitRegistration() {
        UnionFind<String> sets = new UnionFind<>();

        assertThat(sets.find("unseen")).isEqualTo("unseen");
        assertThat(sets.elementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("groups every element under its representative")
    void materialisesClusters() {
        UnionFind<String> sets = new UnionFind<>();
        sets.union("a", "b");
        sets.union("c", "d");
        sets.add("e");

        var clusters = sets.clusters();

        assertThat(clusters).hasSize(3);
        assertThat(clusters.values()).anySatisfy(members -> assertThat(members).containsExactlyInAnyOrder("a", "b"));
        assertThat(clusters.values()).anySatisfy(members -> assertThat(members).containsExactlyInAnyOrder("c", "d"));
        assertThat(clusters.values()).anySatisfy(members -> assertThat(members).containsExactly("e"));
        assertThat(clusters.values().stream().mapToInt(List::size).sum()).isEqualTo(5);
    }

    /**
     * A chain built in the worst possible order for a naive implementation. Without union by rank the
     * forest degenerates into a 20,000-node linked list and {@code find} becomes O(n); with rank and path
     * halving it stays flat. The assertion is on correctness, but the test would take visibly long to run
     * if the optimisations were removed, which is the practical signal.
     */
    @Test
    @DisplayName("stays flat under an adversarial merge order")
    void adversarialChain() {
        UnionFind<Integer> sets = new UnionFind<>();
        int size = 20_000;
        for (int i = 1; i < size; i++) {
            sets.union(i - 1, i);
        }

        assertThat(sets.setCount()).isEqualTo(1);
        assertThat(sets.clusterSize(0)).isEqualTo(size);
        assertThat(sets.connected(0, size - 1)).isTrue();
    }

    @Test
    @DisplayName("grows capacity beyond the initial backing array")
    void growsBeyondInitialCapacity() {
        UnionFind<Integer> sets = new UnionFind<>();
        for (int i = 0; i < 100; i++) {
            sets.add(i);
        }

        assertThat(sets.elementCount()).isEqualTo(100);
        assertThat(sets.setCount()).isEqualTo(100);
    }
}
