package com.coalesce.domain.match.comparator;

import com.coalesce.domain.match.AttributeComparator;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Edit distance, normalised into a similarity by dividing by the longer string's length.
 *
 * <p>Normalisation is what makes the number comparable across attributes. A raw distance of 2 is
 * near-identity for a 40-character address and near-nonsense for a 3-letter code, and a scorer that
 * mixes attributes cannot weigh un-normalised distances against each other at all.
 *
 * <p><b>Space.</b> The full DP table is O(n·m), which is wasteful when only the previous row is ever
 * read. This keeps two rows, giving O(min(n,m)) space. At entity-resolution volumes — hundreds of
 * millions of comparisons per batch — that is the difference between fitting in cache and not.
 *
 * <p><b>When to prefer this over Jaro-Winkler.</b> For codes and identifiers rather than names: SKUs,
 * licence plates, national insurance numbers. There is no reason to believe errors cluster at the end
 * of a machine-generated identifier, so Jaro-Winkler's prefix bonus is an unjustified thumb on the
 * scale, and plain edit distance is the more honest model.
 */
public final class LevenshteinComparator implements AttributeComparator {

    @Override
    public String id() {
        return "levenshtein";
    }

    @Override
    public OptionalDouble compare(String left, String right) {
        String a = left.toLowerCase(Locale.ROOT);
        String b = right.toLowerCase(Locale.ROOT);
        int longest = Math.max(a.length(), b.length());
        if (longest == 0) {
            return OptionalDouble.of(1.0);
        }
        return OptionalDouble.of(1.0 - ((double) distance(a, b) / longest));
    }

    static int distance(String left, String right) {
        if (left.equals(right)) {
            return 0;
        }
        if (left.isEmpty()) {
            return right.length();
        }
        if (right.isEmpty()) {
            return left.length();
        }

        // Iterate over the longer string and index rows by the shorter, so the two retained rows are
        // as small as possible.
        String longer = left.length() >= right.length() ? left : right;
        String shorter = left.length() >= right.length() ? right : left;

        int width = shorter.length() + 1;
        int[] previous = new int[width];
        int[] current = new int[width];

        for (int j = 0; j < width; j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= longer.length(); i++) {
            current[0] = i;
            char c = longer.charAt(i - 1);
            for (int j = 1; j < width; j++) {
                int substitution = previous[j - 1] + (c == shorter.charAt(j - 1) ? 0 : 1);
                int insertion = current[j - 1] + 1;
                int deletion = previous[j] + 1;
                current[j] = Math.min(substitution, Math.min(insertion, deletion));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[width - 1];
    }
}
