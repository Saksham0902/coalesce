package com.coalesce.domain.match.comparator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JaroWinklerComparatorTest {

    private static final double TOLERANCE = 1e-4;

    private final JaroWinklerComparator comparator = new JaroWinklerComparator();

    /**
     * These three pairs are the values published with Winkler's own description of the metric, so they
     * pin the implementation to the real algorithm rather than to whatever this code happens to compute.
     * Without them, a subtly wrong transposition count would still produce plausible-looking numbers and
     * would only show up as a mysteriously poor match rate much later.
     */
    @Test
    @DisplayName("reproduces the reference values for Jaro and Jaro-Winkler")
    void referenceValues() {
        assertThat(JaroWinklerComparator.jaro("martha", "marhta")).isCloseTo(0.9444, within(TOLERANCE));
        assertThat(JaroWinklerComparator.similarity("martha", "marhta")).isCloseTo(0.9611, within(TOLERANCE));

        assertThat(JaroWinklerComparator.jaro("dixon", "dicksonx")).isCloseTo(0.7667, within(TOLERANCE));
        assertThat(JaroWinklerComparator.similarity("dixon", "dicksonx")).isCloseTo(0.8133, within(TOLERANCE));

        assertThat(JaroWinklerComparator.jaro("jellyfish", "smellyfish")).isCloseTo(0.8963, within(TOLERANCE));
    }

    @Test
    @DisplayName("charges a transposition as half an edit, unlike edit distance")
    void transpositionIsCheap() {
        double jaroWinkler = comparator.compare("Micheal", "Michael").orElseThrow();
        double levenshtein = new LevenshteinComparator().compare("Micheal", "Michael").orElseThrow();

        // Levenshtein sees two substitutions in a seven-character name; Jaro-Winkler sees one swap plus a
        // strong shared prefix. The gap between them is the entire reason this comparator is the default
        // for names.
        assertThat(jaroWinkler).isGreaterThan(levenshtein);
        assertThat(jaroWinkler).isGreaterThan(0.95);
    }

    @Test
    @DisplayName("applies the prefix bonus only above the boost threshold")
    void prefixBonusIsGated() {
        // Below the threshold the raw Jaro score must pass through untouched, so that two unrelated names
        // sharing initials are not nudged toward the match threshold.
        double dissimilar = JaroWinklerComparator.similarity("smith", "sanchez");
        assertThat(dissimilar).isEqualTo(JaroWinklerComparator.jaro("smith", "sanchez"));
        assertThat(dissimilar).isLessThan(0.7);

        // Above it, the bonus applies and must strictly increase the score.
        assertThat(JaroWinklerComparator.similarity("jonathan", "jonathon"))
                .isGreaterThan(JaroWinklerComparator.jaro("jonathan", "jonathon"));
    }

    @Test
    @DisplayName("the match window prevents anagrams from scoring as identical")
    void windowBoundsMatching() {
        // Both strings contain exactly the same characters. A window-free implementation would score this
        // as a perfect match; the window plus the transposition penalty holds it to 0.5.
        assertThat(JaroWinklerComparator.jaro("abcdefgh", "hgfedcba")).isLessThanOrEqualTo(0.5);
    }

    @Test
    @DisplayName("is symmetric and case-insensitive")
    void symmetricAndCaseInsensitive() {
        assertThat(comparator.compare("Katherine", "Kathryn").orElseThrow())
                .isEqualTo(comparator.compare("Kathryn", "Katherine").orElseThrow());
        assertThat(comparator.compare("SMITH", "smith").orElseThrow()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("scores identical strings at 1 and fully disjoint strings at 0")
    void bounds() {
        assertThat(comparator.compare("anastasia", "anastasia").orElseThrow()).isEqualTo(1.0);
        assertThat(comparator.compare("abc", "xyz").orElseThrow()).isEqualTo(0.0);
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}
