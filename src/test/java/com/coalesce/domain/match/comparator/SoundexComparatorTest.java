package com.coalesce.domain.match.comparator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SoundexComparatorTest {

    private final SoundexComparator comparator = new SoundexComparator();

    /**
     * The canonical Soundex test vectors. {@code Ashcraft} and {@code Tymczak} are the two that separate
     * correct implementations from approximate ones, and both are included deliberately.
     */
    @Test
    @DisplayName("produces the canonical codes, including the cases most implementations get wrong")
    void canonicalCodes() {
        assertThat(SoundexComparator.encode("Robert")).isEqualTo("R163");
        assertThat(SoundexComparator.encode("Rupert")).isEqualTo("R163");
        assertThat(SoundexComparator.encode("Smith")).isEqualTo("S530");
        assertThat(SoundexComparator.encode("Pfister")).isEqualTo("P236");
        assertThat(SoundexComparator.encode("Honeyman")).isEqualTo("H555");

        // H is transparent: the S and C either side of it must collapse to a single 2. Treating H as a
        // separator instead yields A226 and quietly breaks a whole class of surnames.
        assertThat(SoundexComparator.encode("Ashcraft")).isEqualTo("A261");
        assertThat(SoundexComparator.encode("Ashcroft")).isEqualTo("A261");

        // A vowel *does* separate, so the two same-coded consonants around the A stay distinct.
        assertThat(SoundexComparator.encode("Tymczak")).isEqualTo("T522");
    }

    @Test
    @DisplayName("catches homophone spellings that Jaro-Winkler scores poorly")
    void catchesWhatCharacterMetricsMiss() {
        // The point of carrying a phonetic signal at all. Both vowels and one consonant differ, so the
        // character-level view is unimpressed, while phonetically these are one name.
        assertThat(comparator.compare("Robert", "Rupert").orElseThrow()).isEqualTo(1.0);
        assertThat(JaroWinklerComparator.similarity("robert", "rupert")).isLessThan(0.85);

        assertThat(comparator.compare("Jackson", "Jaxon").orElseThrow()).isEqualTo(1.0);
        assertThat(JaroWinklerComparator.similarity("jackson", "jaxon")).isLessThan(0.9);
    }

    @Test
    @DisplayName("returns no opinion when there is no alphabetic content to encode")
    void nonAlphabeticGivesEmpty() {
        // A schema mistake — a numeric column routed to a phonetic comparator. Reporting empty keeps the
        // scorer from treating the misconfiguration as evidence that two records differ.
        assertThat(comparator.compare("12345", "67890")).isEmpty();
        assertThat(comparator.compare("12345", "Smith")).isEmpty();
    }

    /**
     * Asserting a known weakness rather than a capability. These pairs are homophones that Soundex misses
     * because it preserves the initial letter verbatim, and Jaro-Winkler also misses them because it is
     * least forgiving at the prefix — so this is the one error class where the two comparators fail
     * together instead of covering for each other. Pinning it in a test means the gap is a recorded
     * limitation with a named fix (a nickname table) rather than something discovered in production.
     */
    @Test
    @DisplayName("keeps the first letter verbatim, so homophones with different initials do not collide")
    void documentedWeakness() {
        assertThat(comparator.compare("Karl", "Carl").orElseThrow()).isEqualTo(0.0);
        assertThat(comparator.compare("Catherine", "Katherine").orElseThrow()).isEqualTo(0.0);
        assertThat(JaroWinklerComparator.similarity("catherine", "katherine")).isLessThan(0.95);
    }

    @Test
    @DisplayName("truncates to four characters and pads short codes")
    void fixedWidthOutput() {
        assertThat(SoundexComparator.encode("Lee")).isEqualTo("L000");
        assertThat(SoundexComparator.encode("Washington")).hasSize(4);
    }
}
