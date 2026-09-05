package com.coalesce.domain.match.comparator;

import static org.assertj.core.api.Assertions.assertThat;

import com.coalesce.domain.match.AttributeComparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Covers the remaining comparators, plus the parts of the {@link AttributeComparator} contract that every
 * implementation must honour.
 */
class ComparatorBehaviourTest {

    private static List<AttributeComparator> all() {
        return List.of(new ExactComparator(), new JaroWinklerComparator(), new LevenshteinComparator(),
                new SoundexComparator(), new TokenCosineComparator(), new NumericComparator(),
                new DateComparator());
    }

    /**
     * Clustering treats similarity as an undirected graph. An asymmetric comparator would make the
     * resulting clusters depend on the order records happened to be ingested, producing a system that
     * gives different answers on a re-run of the same data — close to undebuggable in production.
     */
    @ParameterizedTest(name = "{0} is symmetric")
    @MethodSource("all")
    void everyComparatorIsSymmetric(AttributeComparator comparator) {
        List<String> values = List.of("Smith", "Smythe", "17 Oak Street", "Oak Street 17", "1985-03-02",
                "02/03/1985", "1200.50", "1,200.5", "n/a", "AB-12 34 CD");

        for (String left : values) {
            for (String right : values) {
                assertThat(comparator.compare(left, right))
                        .as("%s(%s, %s) must equal the reverse", comparator.id(), left, right)
                        .isEqualTo(comparator.compare(right, left));
            }
        }
    }

    /**
     * Input is untrusted text from arbitrary source systems. A comparator that throws on one malformed
     * cell would abort a batch of millions of comparisons, so every implementation must be total.
     */
    @ParameterizedTest(name = "{0} never throws and stays within [0,1]")
    @MethodSource("all")
    void everyComparatorIsTotalAndBounded(AttributeComparator comparator) {
        List<String> hostile = List.of("unknown", "???", "0", "-1", "\u0000\u0001", "9999-99-99",
                "NaN", "Infinity", "\uD83D\uDE00", "a".repeat(500));

        for (String left : hostile) {
            for (String right : hostile) {
                var result = comparator.compare(left, right);
                if (result.isPresent()) {
                    assertThat(result.getAsDouble())
                            .as("%s(%s, %s)", comparator.id(), left, right)
                            .isBetween(0.0, 1.0);
                }
            }
        }
    }

    @Nested
    class Levenshtein {

        private final LevenshteinComparator comparator = new LevenshteinComparator();

        @Test
        @DisplayName("computes the textbook distances")
        void distances() {
            assertThat(LevenshteinComparator.distance("kitten", "sitting")).isEqualTo(3);
            assertThat(LevenshteinComparator.distance("flaw", "lawn")).isEqualTo(2);
            assertThat(LevenshteinComparator.distance("saturday", "sunday")).isEqualTo(3);
            assertThat(LevenshteinComparator.distance("", "abc")).isEqualTo(3);
            assertThat(LevenshteinComparator.distance("same", "same")).isZero();
        }

        @Test
        @DisplayName("normalisation makes the score comparable across attribute lengths")
        void normalisation() {
            // The same two-character difference is near-identity on a long value and near-nonsense on a
            // short one. An un-normalised distance could not be weighed against other attributes at all.
            double onShortCode = comparator.compare("ab", "xy").orElseThrow();
            double onLongAddress = comparator.compare("221b baker street london", "221b baker street londox")
                    .orElseThrow();

            assertThat(onShortCode).isZero();
            assertThat(onLongAddress).isGreaterThan(0.9);
        }
    }

    @Nested
    class TokenCosine {

        private final TokenCosineComparator comparator = new TokenCosineComparator();

        @Test
        @DisplayName("is insensitive to word order, which is the entire point for addresses")
        void wordOrderDoesNotMatter() {
            assertThat(comparator.compare("17 Oak Street, Apt 3", "Apt 3, Oak Street 17").orElseThrow())
                    .isEqualTo(1.0);

            // A character-level metric rates the same pair poorly, because nearly every character moved.
            assertThat(JaroWinklerComparator.similarity("17 oak street, apt 3", "apt 3, oak street 17"))
                    .isLessThan(0.8);
        }

        @Test
        @DisplayName("scores partial token overlap between zero and one")
        void partialOverlap() {
            double score = comparator.compare("12 Oak Street", "12 Oak Avenue").orElseThrow();
            assertThat(score).isStrictlyBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("scores disjoint token sets at zero and unparseable values as no opinion")
        void edges() {
            assertThat(comparator.compare("alpha beta", "gamma delta").orElseThrow()).isZero();
            assertThat(comparator.compare("!!!", "???")).isEmpty();
        }
    }

    @Nested
    class Numeric {

        private final NumericComparator comparator = new NumericComparator();

        @Test
        @DisplayName("decays relative to magnitude rather than by a fixed tolerance")
        void scaleFree() {
            // A gap of 5 is negligible against 50,000 and enormous against 10. One fixed tolerance could
            // not be right for both, which is why the denominator is the larger magnitude.
            assertThat(comparator.compare("50000", "50005").orElseThrow()).isGreaterThan(0.99);
            assertThat(comparator.compare("10", "15").orElseThrow()).isLessThan(0.7);
        }

        @Test
        @DisplayName("ignores formatting that carries no value")
        void ignoresFormatting() {
            assertThat(comparator.compare("1,200.50", "1200.5").orElseThrow()).isEqualTo(1.0);
            assertThat(comparator.compare("$99", "99").orElseThrow()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("treats unparseable input as no opinion, not as disagreement")
        void unparseable() {
            assertThat(comparator.compare("n/a", "500")).isEmpty();
            assertThat(comparator.compare("NaN", "1")).isEmpty();
        }
    }

    @Nested
    class Dates {

        private final DateComparator comparator = new DateComparator();

        @Test
        @DisplayName("scores a day/month transposition highly, since it is usually a locale artefact")
        void transposition() {
            // 27 days apart arithmetically, but almost certainly the same date read as US and as UK.
            double score = comparator.compare("1985-03-02", "1985-02-03").orElseThrow();
            assertThat(score).isEqualTo(0.9);

            // Below 1.0 on purpose: the pair might genuinely be two dates, and the scorer should see the
            // residual doubt rather than be handed a certainty.
            assertThat(score).isLessThan(1.0);
        }

        @Test
        @DisplayName("parses the common formats to the same instant")
        void multipleFormats() {
            assertThat(comparator.compare("1985-03-02", "02/03/1985").orElseThrow()).isEqualTo(1.0);
            assertThat(comparator.compare("1985-03-02", "02 Mar 1985").orElseThrow()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("decays with distance and bottoms out beyond the window")
        void decay() {
            assertThat(comparator.compare("1985-03-02", "1985-03-05").orElseThrow()).isGreaterThan(0.99);
            assertThat(comparator.compare("1985-03-02", "1986-03-02").orElseThrow()).isStrictlyBetween(0.4, 0.6);

            // A decade apart must be zero, not merely low. Being generous here is how a patient-matching
            // system merges a parent with a child who shares their name and address.
            assertThat(comparator.compare("1985-03-02", "1955-03-02").orElseThrow()).isZero();
        }

        @Test
        @DisplayName("returns no opinion on unparseable dates")
        void unparseable() {
            assertThat(comparator.compare("unknown", "1985-03-02")).isEmpty();
            assertThat(comparator.compare("9999-99-99", "1985-03-02")).isEmpty();
        }
    }

    @Nested
    class Exact {

        private final ExactComparator comparator = new ExactComparator();

        @Test
        @DisplayName("gives no partial credit, because near-misses on identifiers carry no information")
        void binary() {
            // Two emails differing by one character are two different mailboxes. Scoring that at 0.95
            // would let a partial match on a supposedly unique key drag a wrong pair over the threshold.
            assertThat(comparator.compare("jane@example.com", "jane@example.con").orElseThrow()).isZero();
            assertThat(comparator.compare("jane@example.com", "JANE@Example.com").orElseThrow()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("normalises away meaningless punctuation and spacing")
        void normalisesFormatting() {
            assertThat(comparator.compare("AB-12 34 CD", "ab123 4cd").orElseThrow()).isEqualTo(1.0);
        }
    }
}
