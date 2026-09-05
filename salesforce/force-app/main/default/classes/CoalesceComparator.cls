/**
 * String similarity measures, ported from the Java engine's comparator package.
 *
 * <p>Every method returns a similarity in [0,1], or null when the pair cannot be compared at all.
 * The null return is the load-bearing part of the contract: it distinguishes "these two disagree"
 * from "there was nothing here to compare", which are opposite conclusions that a single number
 * cannot express. CoalesceScorer relies on that distinction to renormalise rather than penalise.
 *
 * <p>Ported rather than called over HTTP because Apex cannot host a JVM, and a callout would make
 * every scored pair a network round trip inside a governor-limited transaction. The cost of the
 * port is two implementations of the same arithmetic that can drift; CoalesceResolutionTest asserts
 * the published reference values from Winkler's papers so drift in this copy is caught here.
 */
public with sharing class CoalesceComparator {

    /**
     * Winkler applies the prefix bonus only to pairs that already look similar. Without this gate,
     * unrelated names sharing an initial ("Smith"/"Sanchez") get lifted toward the match threshold,
     * which is the wrong direction to be wrong in.
     */
    private static final Double BOOST_THRESHOLD = 0.7;

    /** Winkler's own per-character scaling. 4 * 0.1 caps the bonus so the result stays <= 1. */
    private static final Double PREFIX_SCALE = 0.1;

    private static final Integer MAX_PREFIX = 4;

    /**
     * Jaro-Winkler similarity, the default choice for personal names.
     *
     * <p>Preferred over edit distance for names because it charges half a transposition for an
     * adjacent-letter swap ("Micheal"/"Michael") where Levenshtein charges two edits, and because it
     * rewards a shared prefix -- people mistype the ends of names far more than the beginnings.
     *
     * <p>Wrong tool for addresses: a character-level view cannot see that "17 Oak Street" and
     * "Oak Street 17" are the same place. Use tokenCosine there. It also cannot know "Bill" is
     * "William"; that needs a nickname table, which this does not have.
     */
    public static Double jaroWinkler(String left, String right) {
        if (String.isBlank(left) || String.isBlank(right)) {
            return null;
        }
        String a = left.trim().toLowerCase();
        String b = right.trim().toLowerCase();
        Double base = jaro(a, b);
        if (base < BOOST_THRESHOLD) {
            return base;
        }
        Integer prefix = commonPrefixLength(a, b);
        Double boosted = base + prefix * PREFIX_SCALE * (1 - base);
        if (boosted > 1) {
            boosted = 1;
        }
        return boosted;
    }

    @TestVisible
    private static Double jaro(String a, String b) {
        if (a.equals(b)) {
            return 1;
        }
        Integer lenA = a.length();
        Integer lenB = b.length();
        if (lenA == 0 || lenB == 0) {
            return 0;
        }

        // Characters may only match within this window of their own index. It is what stops "abcdef"
        // and "fedcba" scoring as a perfect anagram match.
        Integer window = Math.max(0, Math.max(lenA, lenB) / 2 - 1);

        List<Boolean> matchedA = falses(lenA);
        List<Boolean> matchedB = falses(lenB);
        Integer matches = 0;

        for (Integer i = 0; i < lenA; i++) {
            Integer windowStart = Math.max(0, i - window);
            Integer windowEnd = Math.min(lenB, i + window + 1);
            for (Integer j = windowStart; j < windowEnd; j++) {
                if (matchedB.get(j) || a.charAt(i) != b.charAt(j)) {
                    continue;
                }
                matchedA.set(i, true);
                matchedB.set(j, true);
                matches++;
                break;
            }
        }
        if (matches == 0) {
            return 0;
        }

        // Walk the matched characters of both strings in parallel. Every position where the two
        // sequences disagree is half a transposition, since each swap shows up twice.
        Integer transpositions = 0;
        Integer cursor = 0;
        for (Integer i = 0; i < lenA; i++) {
            if (!matchedA.get(i)) {
                continue;
            }
            while (!matchedB.get(cursor)) {
                cursor++;
            }
            if (a.charAt(i) != b.charAt(cursor)) {
                transpositions++;
            }
            cursor++;
        }

        Double m = matches;
        Double halfTranspositions = transpositions;
        halfTranspositions = halfTranspositions / 2;
        return ((m / lenA) + (m / lenB) + ((m - halfTranspositions) / m)) / 3;
    }

    /**
     * Cosine similarity over token frequency vectors -- the right measure for addresses.
     *
     * <p>Order-insensitive by construction, so "17 Oak Street" and "Oak Street 17" score 1.0 where a
     * character-level metric would punish the reordering. The trade-off is that it is blind to
     * spelling: "Oak" and "Oek" are simply different tokens, contributing nothing. A production
     * version would compare tokens fuzzily and take the best assignment, which is quadratic in
     * tokens and not implemented here.
     */
    public static Double tokenCosine(String left, String right) {
        if (String.isBlank(left) || String.isBlank(right)) {
            return null;
        }
        Map<String, Integer> a = termFrequency(left);
        Map<String, Integer> b = termFrequency(right);
        if (a.isEmpty() || b.isEmpty()) {
            // Present but containing no usable token -- punctuation only. Absence, not disagreement.
            return null;
        }

        Double dot = 0;
        for (String term : a.keySet()) {
            Integer inB = b.get(term);
            if (inB != null) {
                dot += a.get(term) * inB;
            }
        }
        if (dot == 0) {
            return 0;
        }
        Double similarity = dot / (magnitude(a) * magnitude(b));
        if (similarity > 1) {
            similarity = 1;
        }
        return similarity;
    }

    /**
     * Normalised equality, for identifiers where partial credit is meaningless.
     *
     * <p>A postcode one character out is not 90% the same postcode -- it is a different place.
     * Returning a graded score for identifiers would let near-misses accumulate into a match.
     */
    public static Double exactMatch(String left, String right) {
        if (String.isBlank(left) || String.isBlank(right)) {
            return null;
        }
        return left.trim().equalsIgnoreCase(right.trim()) ? 1 : 0;
    }

    /**
     * Compares phone numbers on their last ten digits.
     *
     * <p>Formatting is not signal: "+1 (415) 555-0100", "415-555-0100" and "4155550100" are one
     * number. Comparing the tail rather than the whole string absorbs country and trunk prefixes
     * that one source records and another omits. Ten digits is a North-American assumption and is
     * the wrong rule for other numbering plans; a real system would parse to E.164 first.
     */
    public static Double phoneMatch(String left, String right) {
        String a = digitsOnly(left);
        String b = digitsOnly(right);
        if (a == null || b == null) {
            return null;
        }
        return tail(a, 10) == tail(b, 10) ? 1 : 0;
    }

    /** Exact date equality. Null for either side absent, so a missing birthdate never counts against a pair. */
    public static Double dateMatch(Date left, Date right) {
        if (left == null || right == null) {
            return null;
        }
        return left == right ? 1 : 0;
    }

    private static Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> counts = new Map<String, Integer>();
        for (String token : text.toLowerCase().split('[^a-z0-9]+')) {
            if (String.isBlank(token)) {
                continue;
            }
            Integer seen = counts.get(token);
            counts.put(token, seen == null ? 1 : seen + 1);
        }
        return counts;
    }

    private static Double magnitude(Map<String, Integer> vector) {
        Integer sumOfSquares = 0;
        for (Integer occurrences : vector.values()) {
            sumOfSquares += occurrences * occurrences;
        }
        return Math.sqrt(sumOfSquares);
    }

    private static String digitsOnly(String value) {
        if (String.isBlank(value)) {
            return null;
        }
        String digits = value.replaceAll('[^0-9]', '');
        return String.isBlank(digits) ? null : digits;
    }

    private static String tail(String value, Integer digitsToKeep) {
        return value.length() <= digitsToKeep ? value : value.substring(value.length() - digitsToKeep);
    }

    private static Integer commonPrefixLength(String a, String b) {
        Integer ceiling = Math.min(MAX_PREFIX, Math.min(a.length(), b.length()));
        Integer prefix = 0;
        while (prefix < ceiling && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        return prefix;
    }

    private static List<Boolean> falses(Integer size) {
        List<Boolean> flags = new List<Boolean>();
        for (Integer i = 0; i < size; i++) {
            flags.add(false);
        }
        return flags;
    }
}
