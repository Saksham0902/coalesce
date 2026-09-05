package com.coalesce.domain.match.comparator;

import com.coalesce.domain.match.AttributeComparator;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Date closeness, with an explicit allowance for the two errors that dominate real date-of-birth data.
 *
 * <h2>Day/month transposition</h2>
 *
 * <p>{@code 1985-03-02} and {@code 1985-02-03} are 27 days apart by arithmetic, which a plain decay
 * function would score as a near-mismatch. In practice they are overwhelmingly likely to be the same
 * date entered by one person reading {@code 02/03/1985} as American and another reading it as British.
 * This is the most common single defect in cross-border date data, so it gets a deliberate high score
 * rather than being left to a generic distance curve.
 *
 * <p>It is scored at 0.9 rather than 1.0 because the pair genuinely might be two different dates, and
 * the scorer should be able to see the residual doubt.
 *
 * <h2>Why a two-year decay window</h2>
 *
 * <p>Beyond the transposition case, similarity falls linearly to zero over 730 days. Dates a few days
 * apart are usually a typo in the day field and deserve partial credit; dates a decade apart are
 * different people, and an over-generous curve there would merge parents with children who share a name
 * and address — a failure mode with real consequences in patient matching.
 *
 * <h2>Format handling</h2>
 *
 * <p>ISO is tried first, then unambiguous day-first and month-first patterns. Ambiguous input such as
 * {@code 02/03/1985} is parsed day-first, which is a guess; the transposition allowance above is what
 * keeps that guess from being harmful, since both readings score highly against each other anyway.
 */
public final class DateComparator implements AttributeComparator {

    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/uuuu"),
            DateTimeFormatter.ofPattern("dd-MM-uuuu"),
            DateTimeFormatter.ofPattern("uuuu/MM/dd"),
            DateTimeFormatter.ofPattern("dd MMM uuuu"),
            DateTimeFormatter.ofPattern("MMM dd uuuu"));

    private static final double TRANSPOSED_SCORE = 0.9;

    private static final double DECAY_DAYS = 730.0;

    @Override
    public String id() {
        return "date";
    }

    @Override
    public OptionalDouble compare(String left, String right) {
        Optional<LocalDate> a = parse(left);
        Optional<LocalDate> b = parse(right);
        if (a.isEmpty() || b.isEmpty()) {
            return OptionalDouble.empty();
        }

        LocalDate first = a.get();
        LocalDate second = b.get();
        if (first.equals(second)) {
            return OptionalDouble.of(1.0);
        }
        if (isDayMonthTransposition(first, second)) {
            return OptionalDouble.of(TRANSPOSED_SCORE);
        }

        long daysApart = Math.abs(ChronoUnit.DAYS.between(first, second));
        return OptionalDouble.of(Math.max(0.0, 1.0 - (daysApart / DECAY_DAYS)));
    }

    private static boolean isDayMonthTransposition(LocalDate first, LocalDate second) {
        return first.getYear() == second.getYear()
                && first.getDayOfMonth() == second.getMonthValue()
                && first.getMonthValue() == second.getDayOfMonth();
    }

    private static Optional<LocalDate> parse(String value) {
        String trimmed = value.trim();
        for (DateTimeFormatter format : FORMATS) {
            try {
                return Optional.of(LocalDate.parse(trimmed, format));
            } catch (DateTimeParseException wrongFormat) {
                // Expected while probing formats; fall through to the next candidate.
            }
        }
        return Optional.empty();
    }
}
