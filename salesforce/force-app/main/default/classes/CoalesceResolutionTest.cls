/**
 * Tests for the Apex port.
 *
 * <p>The comparator assertions use the reference values published in Winkler's own papers rather
 * than values produced by running this code, so the test can actually fail if the port drifts from
 * the algorithm. A test asserting whatever the implementation currently returns proves only that the
 * implementation is deterministic.
 */
@IsTest
private class CoalesceResolutionTest {

    private static final Double TOLERANCE = 0.001;

    /**
     * Expected values arrive as Decimal because that is the type of an Apex decimal literal, while
     * the comparators return Double. Converting here keeps the literals readable at the call sites.
     */
    private static void assertClose(Decimal expected, Double actual, String label) {
        System.Assert.isNotNull(actual, label + ' should be comparable');
        System.Assert.isTrue(
            Math.abs(expected.doubleValue() - actual) < TOLERANCE,
            label + ': expected ' + expected + ' but got ' + actual
        );
    }

    /**
     * Reference pairs from the Jaro-Winkler literature. These are the canonical worked examples and
     * are independent of this implementation.
     */
    @IsTest
    private static void jaroWinklerMatchesPublishedReferenceValues() {
        assertClose(0.961, CoalesceComparator.jaroWinkler('MARTHA', 'MARHTA'), 'MARTHA/MARHTA');
        assertClose(0.813, CoalesceComparator.jaroWinkler('DIXON', 'DICKSONX'), 'DIXON/DICKSONX');
        assertClose(0.840, CoalesceComparator.jaroWinkler('DWAYNE', 'DUANE'), 'DWAYNE/DUANE');
    }

    @IsTest
    private static void jaroWinklerIsCaseInsensitiveAndSymmetric() {
        Double forward = CoalesceComparator.jaroWinkler('Michael', 'Micheal');
        Double backward = CoalesceComparator.jaroWinkler('micheal', 'MICHAEL');
        System.Assert.isTrue(
            Math.abs(forward - backward) < TOLERANCE,
            'comparison must be symmetric and case-insensitive: ' + forward + ' vs ' + backward
        );
        System.Assert.isTrue(forward > 0.95, 'an adjacent transposition should score very high');
    }

    /**
     * The distinction the whole design rests on: a missing value is not a disagreement, so it must be
     * reported as "not comparable" rather than as zero similarity.
     */
    @IsTest
    private static void absentValuesAreNotComparableRatherThanZero() {
        System.Assert.isNull(CoalesceComparator.jaroWinkler(null, 'Smith'), 'null name');
        System.Assert.isNull(CoalesceComparator.jaroWinkler('Smith', '   '), 'blank name');
        System.Assert.isNull(CoalesceComparator.exactMatch('SW1A1AA', null), 'null postcode');
        System.Assert.isNull(CoalesceComparator.dateMatch(Date.newInstance(1985, 3, 14), null), 'null date');
        System.Assert.isNull(CoalesceComparator.phoneMatch('4155550100', ''), 'blank phone');
        System.Assert.isNull(CoalesceComparator.tokenCosine('---', '17 Oak Street'), 'no usable token');
    }

    @IsTest
    private static void tokenCosineIgnoresTokenOrder() {
        assertClose(1.0, CoalesceComparator.tokenCosine('17 Oak Street', 'Oak Street 17'), 'reordered address');
        Double partial = CoalesceComparator.tokenCosine('17 Oak Street', '17 Oak Avenue');
        System.Assert.isTrue(partial > 0.5 && partial < 1.0, 'partial overlap should be partial: ' + partial);
    }

    @IsTest
    private static void exactMatchGivesNoPartialCredit() {
        assertClose(1.0, CoalesceComparator.exactMatch('sw1a 1aa', 'SW1A 1AA'), 'case and space folding');
        // A postcode one character out is a different place, not a 90% match.
        assertClose(0.0, CoalesceComparator.exactMatch('SW1A 1AA', 'SW1A 1AB'), 'near-miss postcode');
    }

    @IsTest
    private static void phoneComparesDigitsNotFormatting() {
        assertClose(1.0, CoalesceComparator.phoneMatch('+1 (415) 555-0100', '4155550100'), 'formatting');
        assertClose(0.0, CoalesceComparator.phoneMatch('4155550100', '4155550101'), 'different number');
    }

    /**
     * The renormalisation property. Two records agreeing perfectly on the two attributes they share
     * must score 1.0, not 2/6 -- otherwise sparse records, which are the ones most likely to be
     * duplicates, are penalised for being sparse.
     */
    @IsTest
    private static void scoreRenormalisesOverComparableAttributesOnly() {
        CoalesceScorer.Schema schema = testSchema();
        Map<String, Object> left = new Map<String, Object>{
            'email' => 'a@example.com',
            'full_name' => 'John Smith'
        };
        Map<String, Object> right = new Map<String, Object>{
            'email' => 'a@example.com',
            'full_name' => 'John Smith'
        };

        CoalesceScorer.ScoredPair scored = CoalesceScorer.score(schema, left, right);

        assertClose(1.0, scored.score, 'perfect agreement on the comparable attributes');
        System.Assert.areEqual(4, scored.absentAttributes.size(), 'four attributes had nothing to compare');
        assertClose(7.0 / 13.5, scored.evidenceWeightShare, 'evidence share is email plus name over total');
    }

    /**
     * The guard that makes renormalisation safe. A perfect score on too little evidence is held for
     * review rather than merged, and specifically is not demoted to NO_MATCH -- what is missing is
     * evidence, not agreement.
     */
    @IsTest
    private static void thinEvidenceDemotesAPerfectScoreToReview() {
        CoalesceScorer.Schema schema = testSchema();
        // postcode 1.5 + phone 1.5 = 3.0 of 13.5 = 22%, below the 35% floor, though it clears the
        // two-attribute minimum. This isolates the weight-share guard from the count guard.
        Map<String, Object> left = new Map<String, Object>{
            'postcode' => '94105',
            'phone' => '4155550100'
        };
        Map<String, Object> right = new Map<String, Object>{
            'postcode' => '94105',
            'phone' => '415-555-0100'
        };

        CoalesceScorer.ScoredPair scored = CoalesceScorer.score(schema, left, right);

        assertClose(1.0, scored.score, 'agreement on what was comparable is total');
        System.Assert.areEqual(
            'REVIEW',
            scored.decision,
            'a perfect score on 22% of the schema weight must not auto-merge'
        );
        System.Assert.areEqual(
            CoalesceScorer.Decision.REVIEW,
            scored.verdict,
            'demoted to REVIEW, never to NO_MATCH -- absence is not disagreement'
        );
    }

    @IsTest
    private static void sufficientEvidencePromotesToMatch() {
        CoalesceScorer.Schema schema = testSchema();
        // email 4.0 + name 3.0 = 7.0 of 13.5 = 52%, clearing the floor with two attributes.
        Map<String, Object> left = new Map<String, Object>{
            'email' => 'john.smith@example.com',
            'full_name' => 'John Smith'
        };
        Map<String, Object> right = new Map<String, Object>{
            'email' => 'john.smith@example.com',
            'full_name' => 'John Smith'
        };

        CoalesceScorer.ScoredPair scored = CoalesceScorer.score(schema, left, right);

        System.Assert.areEqual('MATCH', scored.decision, 'enough weight behind a perfect score to merge');
    }

    @IsTest
    private static void pairWithNothingComparableIsNoMatchWithEmptyBreakdown() {
        CoalesceScorer.Schema schema = testSchema();
        Map<String, Object> left = new Map<String, Object>{ 'email' => 'a@example.com' };
        Map<String, Object> right = new Map<String, Object>{ 'postcode' => '94105' };

        CoalesceScorer.ScoredPair scored = CoalesceScorer.score(schema, left, right);

        assertClose(0.0, scored.score, 'nothing was compared');
        System.Assert.areEqual('NO_MATCH', scored.decision, 'must not merge an unscoreable pair');
        System.Assert.isTrue(scored.agreed.isEmpty(), 'the empty breakdown is what signals nothing was compared');
    }

    /**
     * The explanation must be byte-identical across runs on identical input, or diffing two runs
     * against each other reports spurious changes. Apex Map iteration order is unspecified, which is
     * why the breakdown is held as a sorted list.
     */
    @IsTest
    private static void explanationOrdersAttributesDeterministically() {
        CoalesceScorer.Schema schema = testSchema();
        Map<String, Object> values = new Map<String, Object>{
            'email' => 'a@example.com',
            'full_name' => 'John Smith',
            'postcode' => '94105',
            'phone' => '4155550100'
        };

        CoalesceScorer.ScoredPair scored = CoalesceScorer.score(schema, values, values.clone());

        System.Assert.areEqual(
            new List<String>{ 'email', 'full_name', 'phone', 'postcode' },
            stripScores(scored.agreed),
            'attributes must appear in a stable sorted order'
        );
        System.Assert.isTrue(
            scored.explanation.startsWith('MATCH score=1.000'),
            'explanation should lead with the decision and score: ' + scored.explanation
        );
    }

    @IsTest
    private static void schemaRejectsInvertedThresholds() {
        try {
            new CoalesceScorer.Schema(
                'bad',
                1,
                new List<CoalesceScorer.AttributeSpec>{
                    new CoalesceScorer.AttributeSpec('email', 'exact', 1.0)
                },
                0.90,
                0.70,
                1,
                0.1
            );
            System.Assert.fail('a review threshold above the match threshold should be rejected');
        } catch (CoalesceScorer.SchemaException expected) {
            System.Assert.isTrue(
                expected.getMessage().contains('reviewThreshold'),
                'the message should name the offending setting: ' + expected.getMessage()
            );
        }
    }

    @IsTest
    private static void unknownComparatorFailsLoudly() {
        CoalesceScorer.Schema schema = new CoalesceScorer.Schema(
            'typo',
            1,
            new List<CoalesceScorer.AttributeSpec>{
                new CoalesceScorer.AttributeSpec('email', 'jaro-winklr', 1.0)
            },
            0.7,
            0.87,
            1,
            0.1
        );
        try {
            CoalesceScorer.score(
                schema,
                new Map<String, Object>{ 'email' => 'a@example.com' },
                new Map<String, Object>{ 'email' => 'a@example.com' }
            );
            System.Assert.fail('a misspelled comparator should not be treated as a missing value');
        } catch (CoalesceScorer.SchemaException expected) {
            System.Assert.isTrue(
                expected.getMessage().contains('jaro-winklr'),
                'the message should name the unknown comparator: ' + expected.getMessage()
            );
        }
    }

    /**
     * End to end against real records: three representations of one person and one unrelated
     * contact. The three must land in a single cluster and the fourth must not be pulled in.
     *
     * <p>Note that the third record shares no phone and no address with the others -- it is joined
     * transitively and on renormalised evidence, which is the behaviour the whole design exists for.
     */
    @IsTest
    private static void resolvesThreeRepresentationsOfOnePersonIntoOneCluster() {
        List<Contact> seed = new List<Contact>{
            newContact('John', 'Smith', 'john.smith@example.com', '4155550100', '94105', '17 Oak Street'),
            newContact('Jon', 'Smith', 'john.smith@example.com', '(415) 555-0100', '94105', 'Oak Street 17'),
            newContact('J', 'Smith', 'john.smith@example.com', null, '94105', null),
            newContact('Maria', 'Garcia', 'maria.garcia@example.com', '4155559999', '10001', '5 Elm Road')
        };
        insert seed;

        Test.startTest();
        CoalesceContactResolver.ResolutionView view = CoalesceContactResolver.findDuplicates(50);
        Test.stopTest();

        System.Assert.areEqual(4, view.contactsScanned, 'all four seeded contacts should be scanned');
        System.Assert.areEqual(6, view.pairsCompared, 'four records make six unordered pairs');
        System.Assert.isFalse(view.stoppedEarly, 'four records must not exhaust the CPU budget');
        System.Assert.areEqual(1, view.duplicateClusters, 'exactly one duplicate cluster expected');

        CoalesceContactResolver.ClusterView cluster = view.clusters[0];
        System.Assert.areEqual(3, cluster.size, 'the three John Smith records belong together');
        System.Assert.isFalse(cluster.evidence.isEmpty(), 'a cluster must carry the pairs that justify it');

        Set<String> clusteredNames = new Set<String>();
        for (CoalesceContactResolver.MemberView member : cluster.members) {
            clusteredNames.add(member.name);
        }
        System.Assert.isFalse(
            clusteredNames.contains('Maria Garcia'),
            'an unrelated contact must not be merged: ' + clusteredNames
        );
    }

    @IsTest
    private static void scanLimitIsClampedAndReported() {
        insert new List<Contact>{
            newContact('Ada', 'Lovelace', 'ada@example.com', '4155550111', '94105', '1 Byron Way'),
            newContact('Grace', 'Hopper', 'grace@example.com', '4155550222', '94106', '2 Navy Road')
        };

        CoalesceContactResolver.ResolutionView view = CoalesceContactResolver.findDuplicates(1);

        System.Assert.areEqual(1, view.contactsScanned, 'the caller-supplied ceiling should be honoured');
        System.Assert.areEqual(2, view.contactsAvailable, 'the full count should still be reported');
        System.Assert.areEqual(0, view.pairsCompared, 'a single record has no pair to compare');
        System.Assert.isTrue(
            view.note.contains('most recent'),
            'a truncated scan should say so: ' + view.note
        );
    }

    @IsTest
    private static void negativeScanLimitFallsBackToTheDefault() {
        insert newContact('Alan', 'Turing', 'alan@example.com', '4155550333', '94107', '3 Bombe Lane');

        CoalesceContactResolver.ResolutionView view = CoalesceContactResolver.findDuplicates(-5);

        System.Assert.areEqual(1, view.contactsScanned, 'a nonsensical limit should not return nothing');
        System.Assert.areEqual('contact:v1', view.schemaTag, 'the schema in force should be reported');
    }

    /**
     * The record-page path: one contact against the rest. Asserts both that the right records come
     * back and that the comparison count is n-1 rather than n(n-1)/2, which is the whole reason this
     * path exists alongside the report.
     */
    @IsTest
    private static void findsOtherRepresentationsOfTheSubjectContact() {
        Contact subject = newContact('John', 'Smith', 'john.smith@example.com', '4155550100', '94105', '17 Oak Street');
        List<Contact> seed = new List<Contact>{
            subject,
            newContact('Jon', 'Smith', 'john.smith@example.com', '(415) 555-0100', '94105', 'Oak Street 17'),
            newContact('J', 'Smith', 'john.smith@example.com', null, '94105', null),
            newContact('Maria', 'Garcia', 'maria.garcia@example.com', '4155559999', '10001', '5 Elm Road')
        };
        insert seed;

        Test.startTest();
        CoalesceContactResolver.ContactMatchView view =
            CoalesceContactResolver.findMatchesFor(subject.Id, 500);
        Test.stopTest();

        System.Assert.areEqual(subject.Id, view.subjectId, 'the subject should be echoed back');
        System.Assert.areEqual(3, view.candidatesAvailable, 'three other contacts exist');
        System.Assert.areEqual(
            3,
            view.candidatesCompared,
            'one against many is n-1 comparisons, not n(n-1)/2 -- that is the point of this path'
        );
        System.Assert.areEqual(2, view.matches.size(), 'the two other John Smith records should match');

        Set<String> matchedNames = new Set<String>();
        for (CoalesceContactResolver.PairView pair : view.matches) {
            matchedNames.add(pair.rightName);
            System.Assert.areEqual('MATCH', pair.decision, 'listed matches must carry a MATCH decision');
        }
        System.Assert.isFalse(
            matchedNames.contains('Maria Garcia'),
            'an unrelated contact must not be reported as the same person: ' + matchedNames
        );
    }

    @IsTest
    private static void subjectItselfIsNeverReportedAsItsOwnDuplicate() {
        Contact subject = newContact('Ada', 'Lovelace', 'ada@example.com', '4155550111', '94105', '1 Byron Way');
        insert subject;

        CoalesceContactResolver.ContactMatchView view =
            CoalesceContactResolver.findMatchesFor(subject.Id, 500);

        System.Assert.areEqual(0, view.candidatesAvailable, 'it is the only contact');
        System.Assert.areEqual(0, view.candidatesCompared, 'there is nothing to compare against');
        System.Assert.isTrue(view.matches.isEmpty(), 'a record must not match itself');
    }

    @IsTest
    private static void aContactTheUserCannotSeeIsRejectedWithoutLeakingWhy() {
        Contact gone = newContact('Grace', 'Hopper', 'grace@example.com', '4155550222', '94106', '2 Navy Road');
        insert gone;
        Id missingId = gone.Id;
        delete gone;

        try {
            CoalesceContactResolver.findMatchesFor(missingId, 500);
            System.Assert.fail('an unavailable contact should be rejected');
        } catch (AuraHandledException expected) {
            // The message deliberately does not distinguish "does not exist" from "you cannot see it",
            // since doing so would leak the existence of records outside the user's sharing.
            System.Assert.isNotNull(expected, 'the rejection should surface to the client');
        }
    }

    @IsTest
    private static void nullContactIdIsRejected() {
        try {
            CoalesceContactResolver.findMatchesFor(null, 500);
            System.Assert.fail('a null contact id should be rejected');
        } catch (AuraHandledException expected) {
            System.Assert.isNotNull(expected, 'the rejection should surface to the client');
        }
    }

    private static CoalesceScorer.Schema testSchema() {
        // Mirrors the Contact schema: weights total 13.5, review 0.70, match 0.87, guard 2 and 35%.
        return new CoalesceScorer.Schema(
            'test',
            1,
            new List<CoalesceScorer.AttributeSpec>{
                new CoalesceScorer.AttributeSpec('email', 'exact', 4.0),
                new CoalesceScorer.AttributeSpec('full_name', 'jaro-winkler', 3.0),
                new CoalesceScorer.AttributeSpec('birthdate', 'date', 2.5),
                new CoalesceScorer.AttributeSpec('phone', 'phone', 1.5),
                new CoalesceScorer.AttributeSpec('postcode', 'exact', 1.5),
                new CoalesceScorer.AttributeSpec('street', 'token-cosine', 1.0)
            },
            0.70,
            0.87,
            2,
            0.35
        );
    }

    private static List<String> stripScores(List<String> agreed) {
        List<String> names = new List<String>();
        for (String entry : agreed) {
            names.add(entry.substringBefore('='));
        }
        return names;
    }

    private static Contact newContact(
        String first,
        String last,
        String email,
        String phone,
        String postcode,
        String street
    ) {
        return new Contact(
            FirstName = first,
            LastName = last,
            Email = email,
            Phone = phone,
            Birthdate = Date.newInstance(1985, 3, 14),
            MailingPostalCode = postcode,
            MailingStreet = street
        );
    }
}
