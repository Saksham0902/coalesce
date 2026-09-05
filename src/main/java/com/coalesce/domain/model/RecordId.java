package com.coalesce.domain.model;

import java.util.Objects;

/**
 * Identity of a single incoming record, as supplied by the source system.
 *
 * <p>Scoped by source, because two systems will absolutely both use {@code "1001"}. A bare string id
 * is the kind of shortcut that silently merges an unrelated hospital patient with an unrelated bank
 * customer the first time a second source is onboarded.
 *
 * @param source the originating system, e.g. {@code "crm"}, {@code "billing"}
 * @param local  the identifier as that system knows it
 */
public record RecordId(String source, String local) {

    public RecordId {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(local, "local");
        if (source.isBlank() || local.isBlank()) {
            throw new IllegalArgumentException("record id needs a non-blank source and local id");
        }
        if (source.indexOf(':') >= 0) {
            // Without this, qualified() is not injective: source "a:b" with local "c" and source "a"
            // with local "b:c" both render as "a:b:c". Since qualified() is the database key and the
            // pair identity used in linkage provenance, that collision would merge two unrelated
            // records with no trace of why. Constraining the separator out of the source is cheaper
            // than escaping it, because source names are chosen by operators, not by source systems.
            throw new IllegalArgumentException("source must not contain ':' (would make ids ambiguous): " + source);
        }
    }

    public static RecordId of(String source, String local) {
        return new RecordId(source, local);
    }

    /** Stable single-string form, used as a database key and in linkage provenance. */
    public String qualified() {
        return source + ':' + local;
    }

    @Override
    public String toString() {
        return qualified();
    }
}
