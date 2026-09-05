package com.coalesce.eval;

import com.coalesce.domain.model.RecordId;
import com.coalesce.domain.model.SourceRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Records plus the ground truth of which of them are the same entity.
 *
 * <p>Ground truth is what separates a measurable engine from one that merely runs. Without labels the
 * only available claim is "it produced some clusters", and every threshold change becomes a matter of
 * taste. With labels, a comparator regression shows up as a number moving.
 *
 * <p>The fixture is hand-labelled rather than generated. Generated messy data has a circularity problem:
 * the noise model that corrupts the records is the same model the comparators are implicitly tuned
 * against, so the engine scores well on its own assumptions. Hand-labelled records taken from realistic
 * shapes — a shortened forename, a locale-swapped date, an address with the house number moved, a
 * genuinely different person who shares a surname and postcode — do not have that flaw.
 *
 * @param records    every record, in file order
 * @param trueEntity record id to its ground-truth entity label
 */
public record LabelledFixture(List<SourceRecord> records, Map<RecordId, String> trueEntity) {

    /** Columns that carry the labels and identity rather than comparable attributes. */
    private static final Set<String> NON_ATTRIBUTE_COLUMNS = Set.of("source", "local", "cluster");

    public static LabelledFixture load(Path csv) {
        List<String> lines;
        try {
            lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read fixture at " + csv.toAbsolutePath(), unreadable);
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("fixture at " + csv.toAbsolutePath() + " is empty");
        }

        List<String> headers = parseRow(lines.get(0));
        List<SourceRecord> records = new ArrayList<>();
        Map<RecordId, String> truth = new LinkedHashMap<>();

        for (int line = 1; line < lines.size(); line++) {
            String raw = lines.get(line);
            if (raw.isBlank()) {
                continue;
            }
            List<String> cells = parseRow(raw);
            if (cells.size() != headers.size()) {
                throw new IllegalArgumentException("fixture line %d has %d cells but the header has %d"
                        .formatted(line + 1, cells.size(), headers.size()));
            }

            Map<String, String> attributes = new LinkedHashMap<>();
            String source = null;
            String local = null;
            String cluster = null;
            for (int column = 0; column < headers.size(); column++) {
                String header = headers.get(column);
                String value = cells.get(column);
                switch (header) {
                    case "source" -> source = value;
                    case "local" -> local = value;
                    case "cluster" -> cluster = value;
                    default -> attributes.put(header, value);
                }
            }
            if (source == null || local == null || cluster == null) {
                throw new IllegalArgumentException(
                        "fixture must have source, local and cluster columns; found " + headers);
            }

            // SourceRecord drops blank attributes on construction, which is what makes an empty cell
            // read as "not on file" rather than as an empty string that comparators would try to match.
            SourceRecord record = new SourceRecord(RecordId.of(source, local), attributes);
            records.add(record);
            truth.put(record.id(), cluster);
        }
        return new LabelledFixture(List.copyOf(records), Map.copyOf(truth));
    }

    /** Attribute names present in the fixture, excluding the identity and label columns. */
    public Set<String> attributeNames() {
        Set<String> names = new LinkedHashSet<>();
        records.forEach(record -> names.addAll(record.attributes().keySet()));
        names.removeAll(NON_ATTRIBUTE_COLUMNS);
        return names;
    }

    public int trueEntityCount() {
        return new LinkedHashSet<>(trueEntity.values()).size();
    }

    /**
     * Minimal RFC-4180 handling: double quotes group a field so an address containing a comma survives,
     * and a doubled quote inside a quoted field is one literal quote. Enough for this fixture and
     * nothing more — a real ingest path would use a parser rather than trusting this.
     */
    private static List<String> parseRow(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString().trim());
        return cells;
    }
}
