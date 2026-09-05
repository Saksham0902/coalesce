package com.coalesce.domain.model;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One record to be resolved: an id plus an untyped bag of attributes.
 *
 * <p>Named {@code SourceRecord} rather than {@code Record} to stay out of the way of Java's own
 * {@code java.lang.Record}, which would make every file that touches both read badly.
 *
 * <p><b>Why every attribute is a String.</b> The engine is schema-agnostic — it must accept patient
 * records, supplier catalogues and bank customers without recompilation, so it cannot have typed
 * fields. Typing lives in the schema's comparator assignment: a comparator declares how to interpret
 * the text it is given, so {@code "1985-03-02"} is a date to a date comparator and an opaque string to
 * an exact-match one. The cost is that malformed input surfaces at comparison time rather than at
 * ingest, which is why comparators must be total and never throw on garbage.
 *
 * <p>Absent and blank attributes are normalised to absent on construction. This matters more than it
 * looks: in entity resolution a missing value is <i>not</i> evidence of a mismatch, and the two must
 * be distinguishable for scoring to be correct.
 */
public record SourceRecord(RecordId id, Map<String, String> attributes) {

    public SourceRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(attributes, "attributes");
        attributes = attributes.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> entry.getValue().trim()));
    }

    public Optional<String> attribute(String name) {
        return Optional.ofNullable(attributes.get(name));
    }

    public boolean has(String name) {
        return attributes.containsKey(name);
    }
}
