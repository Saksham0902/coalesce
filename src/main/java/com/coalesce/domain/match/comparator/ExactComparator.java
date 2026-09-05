package com.coalesce.domain.match.comparator;

import com.coalesce.domain.match.AttributeComparator;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Case- and punctuation-insensitive equality, for attributes where near-misses carry no information.
 *
 * <p>Identifiers are the case: a national insurance number, an ISBN, an email address. Two emails that
 * differ by one character are two different mailboxes, and scoring them as 0.95 similar would be a
 * category error — it would let a strong-looking partial match on a supposedly unique key drag an
 * incorrect pair over the match threshold.
 *
 * <p>Normalisation is limited to case and non-alphanumerics, which handles the formatting variation
 * that carries no meaning ({@code "AB-12 34 CD"} versus {@code "ab123 4cd"}) without pretending that
 * two genuinely different values are close.
 */
public final class ExactComparator implements AttributeComparator {

    @Override
    public String id() {
        return "exact";
    }

    @Override
    public OptionalDouble compare(String left, String right) {
        return OptionalDouble.of(normalise(left).equals(normalise(right)) ? 1.0 : 0.0);
    }

    private static String normalise(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
