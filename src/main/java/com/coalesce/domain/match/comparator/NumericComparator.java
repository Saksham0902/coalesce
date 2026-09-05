package com.coalesce.domain.match.comparator;

import com.coalesce.domain.match.AttributeComparator;
import java.util.OptionalDouble;

/**
 * Scale-free numeric closeness: agreement decays with the <i>relative</i> gap between two values.
 *
 * <p>Relative rather than absolute, because the engine does not know what it is comparing. A £5
 * difference is nothing on an annual salary and everything on a unit price, so any fixed tolerance
 * would be correct for exactly one attribute and wrong for the rest. Dividing by the larger magnitude
 * keeps the output comparable across attributes without per-attribute configuration.
 *
 * <p>Unparseable input yields empty rather than a mismatch. Real source data has {@code "n/a"},
 * {@code "~500"} and {@code ""} in numeric columns, and treating those as disagreement would
 * manufacture evidence out of a data quality problem.
 */
public final class NumericComparator implements AttributeComparator {

    @Override
    public String id() {
        return "numeric";
    }

    @Override
    public OptionalDouble compare(String left, String right) {
        OptionalDouble a = parse(left);
        OptionalDouble b = parse(right);
        if (a.isEmpty() || b.isEmpty()) {
            return OptionalDouble.empty();
        }

        double x = a.getAsDouble();
        double y = b.getAsDouble();
        if (x == y) {
            return OptionalDouble.of(1.0);
        }

        double scale = Math.max(Math.abs(x), Math.abs(y));
        if (scale == 0.0) {
            // Both zero is caught by the equality check above, so reaching here means the values differ
            // yet the larger magnitude is zero — impossible for finite input, but guard rather than
            // divide by zero.
            return OptionalDouble.of(0.0);
        }
        return OptionalDouble.of(Math.max(0.0, 1.0 - (Math.abs(x - y) / scale)));
    }

    private static OptionalDouble parse(String value) {
        // Strip thousands separators, currency symbols and stray whitespace before parsing, since those
        // are formatting rather than value.
        String cleaned = value.replaceAll("[,_\\s\\p{Sc}]", "");
        try {
            double parsed = Double.parseDouble(cleaned);
            return Double.isFinite(parsed) ? OptionalDouble.of(parsed) : OptionalDouble.empty();
        } catch (NumberFormatException notANumber) {
            return OptionalDouble.empty();
        }
    }
}
