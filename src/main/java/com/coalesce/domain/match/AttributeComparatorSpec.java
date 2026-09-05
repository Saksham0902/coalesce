package com.coalesce.domain.match;

/**
 * How one attribute participates in matching: which comparator interprets it, and how much its
 * agreement counts.
 *
 * @param attribute  attribute name as it appears in {@code SourceRecord.attributes}
 * @param comparator comparator id, resolved through {@code ComparatorRegistry}
 * @param weight     relative importance, strictly positive. Weights need not sum to one — the scorer
 *                   renormalises over whichever attributes are present on both records, which is
 *                   what makes a record with three of five attributes scoreable at all.
 * @param blocking   whether this attribute contributes to blocking-key generation. Marking a
 *                   high-cardinality, high-quality attribute (surname, postcode) as a blocking
 *                   attribute is what keeps candidate generation sub-quadratic; marking a
 *                   low-cardinality one (country, gender) would produce blocks so large that
 *                   blocking stops saving anything.
 */
public record AttributeComparatorSpec(String attribute, String comparator, double weight,
                                      boolean blocking) {

    public AttributeComparatorSpec {
        if (attribute == null || attribute.isBlank()) {
            throw new IllegalArgumentException("attribute name is required");
        }
        if (comparator == null || comparator.isBlank()) {
            throw new IllegalArgumentException("comparator id is required for attribute " + attribute);
        }
        if (!(weight > 0) || !Double.isFinite(weight)) {
            // A zero or negative weight means "this attribute cannot influence the decision", which
            // is better expressed by leaving it out of the schema than by encoding it as a number
            // that quietly disables a rule somebody thought they had configured.
            throw new IllegalArgumentException(
                    "weight for " + attribute + " must be finite and positive, was " + weight);
        }
    }

    public static AttributeComparatorSpec of(String attribute, String comparator, double weight) {
        return new AttributeComparatorSpec(attribute, comparator, weight, false);
    }

    public static AttributeComparatorSpec blockingOn(String attribute, String comparator, double weight) {
        return new AttributeComparatorSpec(attribute, comparator, weight, true);
    }
}
