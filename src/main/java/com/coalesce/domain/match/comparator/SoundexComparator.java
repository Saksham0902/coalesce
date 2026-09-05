package com.coalesce.domain.match.comparator;

import com.coalesce.domain.match.AttributeComparator;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Phonetic agreement via Soundex: does this pair of names plausibly <i>sound</i> the same.
 *
 * <p>This catches the error class that no character-level metric reliably catches — a name transcribed
 * by ear. "Smith" and "Smyth" collide, as do "Robert" and "Rupert" and "Jackson" and "Jaxon". Robert
 * and Rupert are the useful illustration: Jaro-Winkler scores that pair around 0.80, because the vowels
 * and one consonant all differ, while Soundex sees one code and calls it a match.
 *
 * <p><b>The output is deliberately near-binary.</b> Soundex codes either match or they do not; there is
 * no meaningful notion of two codes being 60% alike, and inventing one would be dressing up a coarse
 * signal as a precise one. So this returns 1.0 or 0.0 and is given a modest weight in schemas, where it
 * acts as corroborating evidence rather than as a decision on its own.
 *
 * <h2>Honest limitations</h2>
 *
 * <p>Soundex is from 1918 and it shows. It is anglocentric — it models English consonant phonology and
 * degrades badly on names of South Asian, Chinese or Slavic origin, which is a real fairness problem in
 * any system that decides whether two people are the same person. Its 4-character output is so lossy
 * that unrelated names collide constantly, which is why it must never be the deciding signal.
 *
 * <p>The sharpest limitation is that it keeps the first letter verbatim, so any homophone pair differing
 * at the initial fails: "Karl"/"Carl" and "Catherine"/"Katherine" both score 0 here. That is
 * unfortunate, because those are exactly the pairs Jaro-Winkler also handles badly — it is least
 * forgiving at the prefix — so for that specific error class the two comparators fail together rather
 * than covering for each other. A nickname and spelling-variant lookup table is the honest fix, and it
 * is not implemented yet.
 *
 * <p>Double Metaphone handles all three of these better and is the right upgrade; it is a few hundred
 * lines of rules rather than thirty, so it is deferred rather than skipped. Soundex is here because it
 * is honest about being a cheap corroborating signal, and because its failure modes are well understood
 * enough to be worth stating out loud.
 */
public final class SoundexComparator implements AttributeComparator {

    private static final int CODE_LENGTH = 4;

    /** Marks a vowel: it breaks up runs of same-coded consonants, so "Tymczak" keeps both its Zs distinct. */
    private static final char VOWEL = '0';

    /** Marks H and W: these are transparent — they neither encode nor break a run. This is the rule most implementations get wrong. */
    private static final char TRANSPARENT = '-';

    @Override
    public String id() {
        return "soundex";
    }

    @Override
    public OptionalDouble compare(String left, String right) {
        String a = encode(left);
        String b = encode(right);
        if (a.isEmpty() || b.isEmpty()) {
            // No alphabetic content to encode — a numeric or symbolic value was routed to a phonetic
            // comparator. That is a schema mistake, and reporting "no opinion" is more useful than
            // reporting a mismatch the scorer would treat as evidence.
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(a.equals(b) ? 1.0 : 0.0);
    }

    static String encode(String value) {
        String letters = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        if (letters.isEmpty()) {
            return "";
        }

        StringBuilder code = new StringBuilder(CODE_LENGTH);
        code.append(letters.charAt(0));
        char previous = classify(letters.charAt(0));

        for (int i = 1; i < letters.length() && code.length() < CODE_LENGTH; i++) {
            char current = classify(letters.charAt(i));
            if (current == TRANSPARENT) {
                // Skip entirely without disturbing `previous`, so "Ashcraft" treats the S and C either
                // side of the H as adjacent and collapses them to one code.
                continue;
            }
            if (current == VOWEL) {
                previous = VOWEL;
                continue;
            }
            if (current != previous) {
                code.append(current);
            }
            previous = current;
        }

        while (code.length() < CODE_LENGTH) {
            code.append('0');
        }
        return code.toString();
    }

    private static char classify(char letter) {
        return switch (letter) {
            case 'B', 'F', 'P', 'V' -> '1';
            case 'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> '2';
            case 'D', 'T' -> '3';
            case 'L' -> '4';
            case 'M', 'N' -> '5';
            case 'R' -> '6';
            case 'H', 'W' -> TRANSPARENT;
            default -> VOWEL;
        };
    }
}
