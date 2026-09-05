package com.coalesce.domain.match.comparator;

import com.coalesce.domain.match.AttributeComparator;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Jaro-Winkler similarity — the default choice for personal names.
 *
 * <h2>Why this and not edit distance</h2>
 *
 * <p>Jaro-Winkler is tuned for the way humans mistype short strings. Two properties matter:
 *
 * <ul>
 *   <li><b>It counts transpositions cheaply.</b> "Micheal" and "Michael" differ by a swap of adjacent
 *       letters; Levenshtein charges two edits for that, Jaro charges half a transposition. Given how
 *       common that specific error is in hand-entered names, the difference is not academic.
 *   <li><b>It boosts common prefixes.</b> People truncate and misspell the ends of names far more than
 *       the beginnings, so agreement on the first few characters is disproportionately strong evidence.
 *       That is what the Winkler modification encodes.
 * </ul>
 *
 * <p><b>Where it is the wrong tool.</b> It is unreliable on long strings — for addresses or company
 * names the character-level view misses that "17 Oak Street" and "Oak Street 17" are the same place, so
 * a token-based comparator belongs there instead. It also cannot see that "Bill" is "William", because
 * no character-level metric can; that needs a nickname lookup table, which is noted as future work
 * rather than pretended at here.
 */
public final class JaroWinklerComparator implements AttributeComparator {

    /**
     * Winkler applies the prefix bonus only to pairs that already look similar. Without this gate,
     * unrelated names that happen to share an initial ("Smith"/"Sanchez") get lifted toward the match
     * threshold, which is exactly the wrong direction to be wrong in.
     */
    private static final double BOOST_THRESHOLD = 0.7;

    /** Per-character prefix scaling. Winkler's own value; 4 * 0.1 caps the bonus so the result stays ≤ 1. */
    private static final double PREFIX_SCALE = 0.1;

    private static final int MAX_PREFIX = 4;

    @Override
    public String id() {
        return "jaro-winkler";
    }

    @Override
    public OptionalDouble compare(String left, String right) {
        String a = left.toLowerCase(Locale.ROOT);
        String b = right.toLowerCase(Locale.ROOT);
        return OptionalDouble.of(similarity(a, b));
    }

    static double similarity(String a, String b) {
        double jaro = jaro(a, b);
        if (jaro < BOOST_THRESHOLD) {
            return jaro;
        }
        int prefix = commonPrefixLength(a, b);
        return Math.min(1.0, jaro + prefix * PREFIX_SCALE * (1.0 - jaro));
    }

    static double jaro(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int lenA = a.length();
        int lenB = b.length();
        if (lenA == 0 || lenB == 0) {
            return 0.0;
        }

        // Characters may only match within this window of their own index. It is what stops "abcdef"
        // and "fedcba" from scoring as a perfect anagram match.
        int window = Math.max(0, Math.max(lenA, lenB) / 2 - 1);

        boolean[] matchedA = new boolean[lenA];
        boolean[] matchedB = new boolean[lenB];
        int matches = 0;

        for (int i = 0; i < lenA; i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(lenB, i + window + 1);
            for (int j = from; j < to; j++) {
                if (matchedB[j] || a.charAt(i) != b.charAt(j)) {
                    continue;
                }
                matchedA[i] = true;
                matchedB[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) {
            return 0.0;
        }

        // Walk the matched characters of both strings in parallel. Every position where the two
        // sequences disagree is half a transposition, since each swap shows up twice.
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < lenA; i++) {
            if (!matchedA[i]) {
                continue;
            }
            while (!matchedB[k]) {
                k++;
            }
            if (a.charAt(i) != b.charAt(k)) {
                transpositions++;
            }
            k++;
        }

        double m = matches;
        double t = transpositions / 2.0;
        return ((m / lenA) + (m / lenB) + ((m - t) / m)) / 3.0;
    }

    private static int commonPrefixLength(String a, String b) {
        int limit = Math.min(MAX_PREFIX, Math.min(a.length(), b.length()));
        int prefix = 0;
        while (prefix < limit && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        return prefix;
    }
}
