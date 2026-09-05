/**
 * Turns two records and a schema into a score, a decision, and the evidence both were derived from.
 *
 * <h3>Renormalisation, and why it is the whole point</h3>
 *
 * <p>The score is the weighted mean of the per-attribute similarities <b>over the attributes that
 * were actually comparable</b>, not over every attribute the schema configures. If a schema weights
 * six attributes and a pair shares three, the score divides by the weight of those three.
 *
 * <p>The alternative -- scoring an absent attribute 0.0 and dividing by the full weight -- is the
 * standard mistake, and it is worse than it looks. It does not add noise uniformly; it penalises
 * precisely the sparse records, which are disproportionately the ones that need matching, because a
 * record with half its fields empty is usually the one that got typed in twice. A pair agreeing
 * perfectly on name, birthdate and email would score 0.6 solely for having no address on file, and
 * would fall below any sensibly-set threshold.
 *
 * <h3>The failure mode renormalisation creates, and the guard for it</h3>
 *
 * <p>Renormalising over present attributes means the denominator shrinks with the evidence, so
 * confidence stops being tied to how much was known. Two records sharing only a country code score
 * 1.0 -- a perfect, meaningless match. Sparse records cluster together, so unguarded this merges
 * them into one component of "records we know nothing about".
 *
 * <p>Two schema-level guards close it: a minimum count of comparable attributes, and a minimum
 * fraction of the schema's total weight. The second is needed separately because a count alone
 * treats three weak attributes as stronger than one decisive one, inverting what the weights say.
 *
 * <p>A pair clearing the match threshold but failing a guard is demoted to REVIEW, not NO_MATCH. It
 * looks like a match on the evidence available; what is missing is evidence, and a human with access
 * to more of it is the right resolution. Demoting to NO_MATCH would treat absence as disagreement --
 * the same error, one layer up.
 *
 * <h3>Known limitation</h3>
 *
 * <p>Weights are hand-set, independent and additive. Fellegi-Sunter instead learns per-attribute
 * agreement and disagreement likelihoods and sums log-likelihood ratios, which is better calibrated
 * and can say that agreement on a rare surname is worth more than on a common one. That needs
 * labelled data or EM over the candidate set. The weighted mean is chosen because it is inspectable
 * and needs no training corpus, and its limits are stated rather than hidden.
 */
public with sharing class CoalesceScorer {

    public class SchemaException extends Exception {}

    public enum Decision { MATCH, REVIEW, NO_MATCH }

    /** One attribute, the comparator that reads it, and what its agreement is worth. */
    public class AttributeSpec {
        public String attribute;
        public String comparator;
        public Double weight;

        /**
         * Takes Decimal because that is what an Apex decimal literal is, so callers can write 3.0
         * rather than casting at every call site. Converted to Double immediately because the scoring
         * arithmetic is all Double.
         */
        public AttributeSpec(String attribute, String comparator, Decimal weight) {
            if (String.isBlank(attribute)) {
                throw new SchemaException('an attribute needs a name');
            }
            if (weight == null || weight <= 0) {
                throw new SchemaException('weight must be positive for ' + attribute);
            }
            this.attribute = attribute;
            this.comparator = comparator;
            this.weight = weight.doubleValue();
        }
    }

    /** Immutable-by-convention scoring rules. Validated on construction so a bad schema fails loudly. */
    public class Schema {
        public String name;
        public Integer version;
        public List<AttributeSpec> attributes;
        public Double matchThreshold;
        public Double reviewThreshold;
        public Integer minContributingAttributes;
        public Double minEvidenceWeightShare;

        public Schema(
            String name,
            Integer version,
            List<AttributeSpec> attributes,
            Decimal reviewThreshold,
            Decimal matchThreshold,
            Integer minContributingAttributes,
            Decimal minEvidenceWeightShare
        ) {
            if (attributes == null || attributes.isEmpty()) {
                throw new SchemaException('a schema with no attributes cannot score anything');
            }
            if (reviewThreshold > matchThreshold) {
                throw new SchemaException(
                    'reviewThreshold ' + reviewThreshold + ' above matchThreshold ' + matchThreshold +
                    ' would make every match also a review'
                );
            }
            this.name = name;
            this.version = version;
            this.attributes = attributes;
            this.reviewThreshold = reviewThreshold.doubleValue();
            this.matchThreshold = matchThreshold.doubleValue();
            this.minContributingAttributes = minContributingAttributes;
            this.minEvidenceWeightShare = minEvidenceWeightShare.doubleValue();
        }

        public Double totalWeight() {
            Double total = 0.0;
            for (AttributeSpec spec : attributes) {
                total += spec.weight;
            }
            return total;
        }

        public String versionTag() {
            return name + ':v' + version;
        }
    }

    /**
     * One attribute's contribution.
     *
     * <p>Sortable, and the breakdown is held as a sorted list rather than a Map deliberately: Apex
     * Map iteration order is unspecified, so a Map would make the explanation string reorder itself
     * between runs on identical data, which defeats diffing two runs against each other.
     */
    public class AttributeScore implements Comparable {
        public String attribute;
        public Double similarity;

        public AttributeScore(String attribute, Double similarity) {
            this.attribute = attribute;
            this.similarity = similarity;
        }

        public Integer compareTo(Object other) {
            return attribute.compareTo(((AttributeScore) other).attribute);
        }
    }

    /**
     * The full result of comparing two records.
     *
     * <p>The breakdown is part of the value, not a debug log. The question a steward asks is never
     * "what was the score" but "why", and a bare number cannot answer it. absentAttributes is as
     * prominent as the scores because it is the difference between "these disagree on address" and
     * "neither record has an address" -- opposite conclusions one number cannot distinguish.
     */
    public class ScoredPair {
        @AuraEnabled public Double score { get; set; }
        @AuraEnabled public String decision { get; set; }
        @AuraEnabled public Double evidenceWeightShare { get; set; }
        @AuraEnabled public String explanation { get; set; }
        @AuraEnabled public List<String> agreed { get; set; }
        @AuraEnabled public List<String> absentAttributes { get; set; }

        public Decision verdict;
    }

    /**
     * Scores one pair. Values arrive as a map so a Date can travel alongside Strings; the comparator
     * dispatch is what knows which type each attribute holds.
     */
    public static ScoredPair score(Schema schema, Map<String, Object> left, Map<String, Object> right) {
        List<AttributeScore> contributions = new List<AttributeScore>();
        List<String> absent = new List<String>();

        Double weightedSum = 0.0;
        Double presentWeight = 0.0;

        for (AttributeSpec spec : schema.attributes) {
            Double similarity = compare(spec.comparator, left.get(spec.attribute), right.get(spec.attribute));
            if (similarity == null) {
                // Either side missing, or present but uninterpretable. Indistinguishable as far as
                // evidence goes, so treated identically rather than scored 0.
                absent.add(spec.attribute);
                continue;
            }
            if (similarity < 0 || similarity > 1) {
                throw new SchemaException(
                    'comparator ' + spec.comparator + ' returned ' + similarity +
                    ' for ' + spec.attribute + ', outside [0,1]'
                );
            }
            contributions.add(new AttributeScore(spec.attribute, similarity));
            weightedSum += spec.weight * similarity;
            presentWeight += spec.weight;
        }

        contributions.sort();
        absent.sort();

        ScoredPair result = new ScoredPair();
        result.agreed = new List<String>();
        result.absentAttributes = absent;

        if (presentWeight == 0.0) {
            // No shared comparable attribute at all. Not a mismatch -- an unscoreable pair. Reported
            // as NO_MATCH because the engine must not merge it, but the empty breakdown is what tells
            // a reviewer that nothing was actually compared.
            result.score = 0.0;
            result.evidenceWeightShare = 0.0;
            result.verdict = Decision.NO_MATCH;
            result.decision = 'NO_MATCH';
            result.explanation = 'NO_MATCH score=0.000 evidence=0% nothing comparable on either side';
            return result;
        }

        Double score = clampToUnit(weightedSum / presentWeight);
        Double evidenceShare = clampToUnit(presentWeight / schema.totalWeight());
        Decision verdict = decide(schema, score, contributions.size(), evidenceShare);

        for (AttributeScore contribution : contributions) {
            result.agreed.add(contribution.attribute + '=' + format(contribution.similarity));
        }
        result.score = score;
        result.evidenceWeightShare = evidenceShare;
        result.verdict = verdict;
        result.decision = verdict.name();
        result.explanation = explain(verdict, score, evidenceShare, result.agreed, absent);
        return result;
    }

    /** Applies the thresholds, then the evidence guards. Order matters: the guards can only demote. */
    private static Decision decide(Schema schema, Double score, Integer contributing, Double evidenceShare) {
        if (score < schema.reviewThreshold) {
            return Decision.NO_MATCH;
        }
        if (score < schema.matchThreshold) {
            return Decision.REVIEW;
        }
        Boolean enoughEvidence = contributing >= schema.minContributingAttributes
            && evidenceShare >= schema.minEvidenceWeightShare;
        return enoughEvidence ? Decision.MATCH : Decision.REVIEW;
    }

    /**
     * One-line justification for a review queue.
     *
     * <p>Formatted rather than structured because the consumers are a human and a log line. Anything
     * computing on this should read the fields instead of parsing the string.
     */
    private static String explain(
        Decision verdict,
        Double score,
        Double evidenceShare,
        List<String> agreed,
        List<String> absent
    ) {
        String text = verdict.name() +
            ' score=' + format(score) +
            ' evidence=' + String.valueOf(Math.round(evidenceShare * 100)) + '%';
        if (!agreed.isEmpty()) {
            text += ' ' + String.join(agreed, ' ');
        }
        if (!absent.isEmpty()) {
            text += ' absent=[' + String.join(absent, ', ') + ']';
        }
        return text;
    }

    private static Double compare(String comparatorId, Object left, Object right) {
        if (comparatorId == 'jaro-winkler') {
            return CoalesceComparator.jaroWinkler(asText(left), asText(right));
        }
        if (comparatorId == 'exact') {
            return CoalesceComparator.exactMatch(asText(left), asText(right));
        }
        if (comparatorId == 'token-cosine') {
            return CoalesceComparator.tokenCosine(asText(left), asText(right));
        }
        if (comparatorId == 'phone') {
            return CoalesceComparator.phoneMatch(asText(left), asText(right));
        }
        if (comparatorId == 'date') {
            return CoalesceComparator.dateMatch(asDate(left), asDate(right));
        }
        // Failing loudly beats silently treating the attribute as absent, which would look like a
        // sparse dataset rather than a misconfigured schema.
        throw new SchemaException('unknown comparator: ' + comparatorId);
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Date asDate(Object value) {
        return value instanceof Date ? (Date) value : null;
    }

    /**
     * Guards against floating-point drift only. The weighted mean of values in [0,1] is
     * mathematically in [0,1], but summing and dividing can land a hair outside.
     */
    private static Double clampToUnit(Double value) {
        if (value < 0) {
            return 0;
        }
        if (value > 1) {
            return 1;
        }
        return value;
    }

    /** Apex Double has no setScale; rounding goes through Decimal. */
    private static String format(Double value) {
        return String.valueOf(Decimal.valueOf(value).setScale(3, System.RoundingMode.HALF_UP));
    }
}
