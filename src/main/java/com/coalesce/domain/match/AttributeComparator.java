package com.coalesce.domain.match;

import java.util.OptionalDouble;

/**
 * Compares one attribute of two records.
 *
 * <h2>The contract, and why each clause is load-bearing</h2>
 *
 * <p><b>Returns a similarity in [0,1], not a boolean.</b> "Jon" versus "John" is neither a match nor a
 * mismatch; collapsing it to a boolean throws away the only information the scorer can actually use to
 * weigh evidence.
 *
 * <p><b>Returns empty when a value is absent, rather than zero.</b> This is the single most commonly
 * botched detail in entity resolution. If one record has no middle name, that is not evidence the two
 * people are different — it is the absence of evidence. Scoring a missing value as 0.0 systematically
 * penalises sparse records, which are exactly the records most in need of matching. Empty lets the
 * scorer renormalise over the attributes that are actually present.
 *
 * <p><b>Must be total.</b> Input is untrusted text from arbitrary source systems, so a date comparator
 * handed {@code "unknown"} must return empty rather than throw. A comparator that throws takes down a
 * batch of millions of comparisons over one malformed cell.
 *
 * <p><b>Must be symmetric.</b> {@code compare(a, b)} equals {@code compare(b, a)}. Clustering assumes
 * an undirected similarity graph; an asymmetric comparator makes the resulting clusters depend on
 * record insertion order, which is close to impossible to debug after the fact.
 *
 * <p>Implementations must be stateless and safe for concurrent use.
 */
public interface AttributeComparator {

    /** Stable identifier used to reference this comparator from schema configuration. */
    String id();

    /**
     * @param left  one attribute value, never null or blank
     * @param right the other attribute value, never null or blank
     * @return similarity in {@code [0,1]}, or empty if this comparator cannot interpret the inputs
     */
    OptionalDouble compare(String left, String right);
}
