package com.coalesce.domain.match;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves comparator ids from schema configuration to implementations.
 *
 * <p>This indirection is what makes the engine schema-agnostic: a schema is data ({@code "surname" ->
 * "jaro-winkler"}) rather than code, so onboarding a new domain is a configuration change. Adding a
 * comparator means adding one class and registering it, with nothing in the scorer or the resolution
 * pipeline to modify — the open/closed principle doing actual work rather than being cited.
 *
 * <p>Duplicate ids fail at construction. Two comparators silently competing for {@code "exact"} would
 * mean the semantics of a schema depended on classpath ordering, and the resulting wrong-but-plausible
 * match rates would be extremely difficult to trace back to their cause.
 */
public final class ComparatorRegistry {

    private final Map<String, AttributeComparator> byId;

    public ComparatorRegistry(List<AttributeComparator> comparators) {
        Map<String, AttributeComparator> resolved = new LinkedHashMap<>();
        for (AttributeComparator comparator : comparators) {
            AttributeComparator clash = resolved.putIfAbsent(comparator.id(), comparator);
            if (clash != null) {
                throw new IllegalStateException("duplicate comparator id '%s': %s and %s"
                        .formatted(comparator.id(), clash.getClass().getName(),
                                comparator.getClass().getName()));
            }
        }
        this.byId = Map.copyOf(resolved);
    }

    public AttributeComparator require(String id) {
        AttributeComparator comparator = byId.get(id);
        if (comparator == null) {
            throw new IllegalArgumentException(
                    "unknown comparator '%s'; registered: %s".formatted(id, byId.keySet()));
        }
        return comparator;
    }

    public Set<String> registered() {
        return byId.keySet();
    }
}
