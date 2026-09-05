package com.coalesce.domain.match;

import com.coalesce.domain.model.RecordId;
import java.util.Objects;

/**
 * An unordered pair of records proposed for comparison.
 *
 * <p>Canonicalised at construction so that {@code (A,B)} and {@code (B,A)} are one value. That is not
 * cosmetic. Several blocking strategies are unioned together and each discovers pairs in its own
 * traversal order, so without a canonical form the composite would score most pairs twice, overstate
 * the candidate count that the reduction ratio is computed from, and store two links for one pair in
 * the ledger — where a later un-merge would then retract only one of them and leave the cluster
 * silently intact.
 *
 * <p>Ordering is by the {@code (source, local)} tuple rather than by hash code, so pair identity is
 * stable across JVM runs. Hash-ordered pairs would be reproducible within a process and different
 * after a restart, which is the worst version of this bug: a stored link would no longer match a
 * freshly computed one.
 */
public record CandidatePair(RecordId left, RecordId right) {

    public CandidatePair {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (left.equals(right)) {
            // A self-pair is always a perfect match and always meaningless. Rejecting it here means no
            // blocking strategy has to remember to filter the diagonal, and a strategy bug that emits
            // one fails loudly instead of inflating precision.
            throw new IllegalArgumentException("a record cannot be paired with itself: " + left);
        }
        if (compare(left, right) > 0) {
            RecordId lower = right;
            right = left;
            left = lower;
        }
    }

    public static CandidatePair of(RecordId a, RecordId b) {
        return new CandidatePair(a, b);
    }

    public boolean involves(RecordId id) {
        return left.equals(id) || right.equals(id);
    }

    /** @throws IllegalArgumentException if {@code id} is not in this pair, which is a caller bug */
    public RecordId other(RecordId id) {
        if (left.equals(id)) {
            return right;
        }
        if (right.equals(id)) {
            return left;
        }
        throw new IllegalArgumentException(id + " is not part of " + this);
    }

    private static int compare(RecordId a, RecordId b) {
        int bySource = a.source().compareTo(b.source());
        return bySource != 0 ? bySource : a.local().compareTo(b.local());
    }

    @Override
    public String toString() {
        return left.qualified() + " <-> " + right.qualified();
    }
}
