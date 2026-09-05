package com.coalesce.domain.match.comparator;

import com.coalesce.domain.match.AttributeComparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Cosine similarity over token frequency vectors — the comparator for multi-word text where word order
 * is not meaningful.
 *
 * <p>Addresses and organisation names are the motivating case. Character-level metrics rate
 * {@code "17 Oak Street, Apt 3"} against {@code "Apt 3, Oak Street 17"} as barely similar, because
 * almost every character has moved. Treating the value as a bag of tokens makes them near-identical,
 * which is the correct answer: they are the same address.
 *
 * <p><b>The known weakness: no IDF.</b> Every token counts equally here, so agreement on "Street"
 * counts as much as agreement on "Kowalczyk". That is plainly wrong — a token appearing in half the
 * corpus carries almost no evidence, while a rare one is nearly decisive. Fixing it requires
 * corpus-wide document frequencies, which means this comparator would need a statistics snapshot
 * injected and would stop being a pure function of its two arguments. That is a real design change
 * rather than a tweak, so it is scoped as the next iteration and stated here instead of glossed over.
 *
 * <p>In the meantime schemas mitigate it by pairing this with a high-signal exact-match attribute such
 * as postcode, so that shared stopwords cannot carry a pair on their own.
 */
public final class TokenCosineComparator implements AttributeComparator {

    @Override
    public String id() {
        return "token-cosine";
    }

    @Override
    public OptionalDouble compare(String left, String right) {
        Map<String, Integer> a = tokenise(left);
        Map<String, Integer> b = tokenise(right);
        if (a.isEmpty() || b.isEmpty()) {
            return OptionalDouble.empty();
        }
        if (a.equals(b)) {
            // Identical token bags are exactly parallel, so cosine is exactly 1. Short-circuiting says so
            // directly instead of arriving at 0.9999999999999998 through two square roots, which would
            // then fail any downstream threshold set at 1.0.
            return OptionalDouble.of(1.0);
        }

        // Walk the smaller vector; tokens absent from the other contribute nothing to the dot product.
        Map<String, Integer> smaller = a.size() <= b.size() ? a : b;
        Map<String, Integer> larger = a.size() <= b.size() ? b : a;

        long dot = 0;
        for (Map.Entry<String, Integer> entry : smaller.entrySet()) {
            Integer other = larger.get(entry.getKey());
            if (other != null) {
                dot += (long) entry.getValue() * other;
            }
        }
        if (dot == 0) {
            return OptionalDouble.of(0.0);
        }

        double magnitude = Math.sqrt(squaredNorm(a)) * Math.sqrt(squaredNorm(b));
        return OptionalDouble.of(Math.min(1.0, dot / magnitude));
    }

    private static Map<String, Integer> tokenise(String value) {
        Map<String, Integer> counts = new HashMap<>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isEmpty()) {
                counts.merge(token, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static double squaredNorm(Map<String, Integer> vector) {
        long total = 0;
        for (int count : vector.values()) {
            total += (long) count * count;
        }
        return total;
    }
}
