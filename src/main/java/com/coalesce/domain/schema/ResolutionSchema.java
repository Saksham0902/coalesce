package com.coalesce.domain.schema;

import com.coalesce.domain.match.AttributeComparatorSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The complete matching configuration for one entity type: which attributes count, how much each
 * counts, and where the decision boundaries sit.
 *
 * <p>This is the object that makes the engine schema-agnostic. Onboarding patient records, supplier
 * catalogues or KYC subjects is authoring one of these, not writing code, because the comparator ids
 * inside it are resolved through {@code ComparatorRegistry} at scoring time.
 *
 * <h2>Everything here is validated at construction, on purpose</h2>
 *
 * <p>A schema is the one place where a plausible-looking mistake produces wrong answers rather than a
 * crash. Duplicate attribute entries would double-weight one field; a review threshold above the match
 * threshold would create a band that swallows matches; a schema with no blocking attribute silently
 * degrades candidate generation to all pairs, which is O(n²) and therefore fine in the 500-record test
 * and fatal at a million. Each of those is caught here so that the schema author is told at startup
 * rather than inferring it from a production incident weeks later.
 *
 * <h2>Version is part of the schema, not metadata about it</h2>
 *
 * <p>Every link records the schema version that produced it. Without that, a threshold change makes the
 * existing link set unexplainable: a steward asking "why is this pair merged" gets scored against
 * today's rules and sees a score below today's threshold. Bumping the version is how re-resolution
 * becomes auditable instead of destructive.
 *
 * @param name                     entity type this schema resolves, e.g. {@code "patient"}
 * @param version                  incremented whenever weights or thresholds change, so links stay
 *                                 attributable to the rules that created them
 * @param attributes               attribute specs; at least one must be marked blocking
 * @param matchThreshold           at or above this score a pair may be merged automatically
 * @param reviewThreshold          at or above this score, but below {@code matchThreshold}, a pair is
 *                                 queued for a human instead of being discarded
 * @param minContributingAttributes minimum number of attributes that must actually be comparable on
 *                                 both records before {@code MATCH} is reachable. See
 *                                 {@code PairScorer} for the thin-evidence failure mode this closes.
 * @param minEvidenceWeightShare   minimum fraction of total schema weight that must be present on both
 *                                 records before {@code MATCH} is reachable, in {@code [0,1]}. Counting
 *                                 attributes is not enough on its own — three trivial attributes are
 *                                 weaker evidence than one decisive one, and this expresses that.
 */
public record ResolutionSchema(
        String name,
        int version,
        List<AttributeComparatorSpec> attributes,
        double matchThreshold,
        double reviewThreshold,
        int minContributingAttributes,
        double minEvidenceWeightShare) {

    public ResolutionSchema {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(attributes, "attributes");
        if (name.isBlank()) {
            throw new IllegalArgumentException("schema name is required");
        }
        if (version < 1) {
            throw new IllegalArgumentException("schema version starts at 1, was " + version);
        }
        if (attributes.isEmpty()) {
            throw new IllegalArgumentException("schema '" + name + "' has no attributes to compare");
        }

        attributes = List.copyOf(attributes);
        Set<String> seen = new HashSet<>();
        boolean anyBlocking = false;
        for (AttributeComparatorSpec spec : attributes) {
            if (!seen.add(spec.attribute())) {
                throw new IllegalArgumentException(
                        "attribute '" + spec.attribute() + "' is configured twice in schema '" + name
                                + "'; its weight would be counted twice");
            }
            anyBlocking |= spec.blocking();
        }
        if (!anyBlocking) {
            throw new IllegalArgumentException("schema '" + name
                    + "' marks no attribute as blocking, so candidate generation would compare all "
                    + "n(n-1)/2 pairs; mark a high-cardinality attribute such as surname or postcode");
        }

        requireProbability(matchThreshold, "matchThreshold");
        requireProbability(reviewThreshold, "reviewThreshold");
        requireProbability(minEvidenceWeightShare, "minEvidenceWeightShare");
        if (reviewThreshold > matchThreshold) {
            throw new IllegalArgumentException("reviewThreshold " + reviewThreshold
                    + " exceeds matchThreshold " + matchThreshold + ", which would leave no match band");
        }
        if (minContributingAttributes < 1) {
            throw new IllegalArgumentException(
                    "minContributingAttributes must be at least 1, was " + minContributingAttributes);
        }
        if (minContributingAttributes > attributes.size()) {
            // Reachability matters: a guard that no pair can ever satisfy makes MATCH unreachable and
            // routes the entire dataset to the review queue, which looks like a matching-quality
            // problem rather than the configuration error it is.
            throw new IllegalArgumentException("minContributingAttributes " + minContributingAttributes
                    + " exceeds the " + attributes.size() + " attributes in schema '" + name
                    + "', so no pair could ever match");
        }
    }

    private static void requireProbability(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be within [0,1], was " + value);
        }
    }

    /** Denominator for the evidence share: what a pair sharing every attribute would be scored over. */
    public double totalWeight() {
        double total = 0.0;
        for (AttributeComparatorSpec spec : attributes) {
            total += spec.weight();
        }
        return total;
    }

    public List<AttributeComparatorSpec> blockingAttributes() {
        List<AttributeComparatorSpec> blocking = new ArrayList<>();
        for (AttributeComparatorSpec spec : attributes) {
            if (spec.blocking()) {
                blocking.add(spec);
            }
        }
        return List.copyOf(blocking);
    }

    public Optional<AttributeComparatorSpec> attribute(String attributeName) {
        return attributes.stream().filter(spec -> spec.attribute().equals(attributeName)).findFirst();
    }

    /** Stable identity of these exact rules, stored on every link so decisions stay attributable. */
    public String versionTag() {
        return name + ":v" + version;
    }

    /**
     * Starts an otherwise-unreadable seven-argument construction.
     *
     * <p>Two of those arguments are unrelated thresholds in {@code [0,1]} and two more are guard
     * settings, so positional construction is exactly the shape that lets a schema author transpose
     * two numbers and get a working-but-wrong schema. The builder makes the mistake visible at the
     * call site instead.
     */
    public static Builder named(String name) {
        return new Builder(name);
    }

    /** Mutable, single-use, not thread-safe; hand it to one thread and call {@link Builder#build()} once. */
    public static final class Builder {

        private final String name;
        private final List<AttributeComparatorSpec> attributes = new ArrayList<>();
        private int version = 1;
        private double matchThreshold = 0.85;
        private double reviewThreshold = 0.70;
        private int minContributingAttributes = 2;
        private double minEvidenceWeightShare = 0.5;

        private Builder(String name) {
            this.name = name;
        }

        public Builder version(int value) {
            this.version = value;
            return this;
        }

        public Builder attribute(AttributeComparatorSpec spec) {
            this.attributes.add(spec);
            return this;
        }

        public Builder compare(String attribute, String comparator, double weight) {
            return attribute(AttributeComparatorSpec.of(attribute, comparator, weight));
        }

        public Builder blockOn(String attribute, String comparator, double weight) {
            return attribute(AttributeComparatorSpec.blockingOn(attribute, comparator, weight));
        }

        public Builder thresholds(double review, double match) {
            this.reviewThreshold = review;
            this.matchThreshold = match;
            return this;
        }

        public Builder evidenceGuard(int minAttributes, double minWeightShare) {
            this.minContributingAttributes = minAttributes;
            this.minEvidenceWeightShare = minWeightShare;
            return this;
        }

        public ResolutionSchema build() {
            return new ResolutionSchema(name, version, attributes, matchThreshold, reviewThreshold,
                    minContributingAttributes, minEvidenceWeightShare);
        }
    }
}
