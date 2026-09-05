/**
 * Runs entity resolution over Contacts and returns the clusters, with the evidence for each merge.
 *
 * <h3>Why all-pairs, and why that is the honest limitation</h3>
 *
 * <p>Every contact is compared with every other, which is n(n-1)/2 comparisons. At 200 contacts that
 * is 19,900 pairs and roughly 120,000 comparator calls, which is already a meaningful fraction of the
 * 10-second synchronous Apex CPU budget. The quadratic term is why the ceiling here is a couple of
 * hundred records rather than a couple of hundred thousand.
 *
 * <p>The fix is <b>blocking</b>: generate candidate pairs only within buckets that share something
 * cheap and discriminating (a postcode, a name key, a MinHash band), so the comparison count grows
 * near-linearly instead of quadratically. It is not implemented here or in the Java engine, and this
 * class is the clearest demonstration of why it matters -- the governor limit turns an asymptotic
 * argument into a hard wall you can hit in a sandbox.
 *
 * <p>Until then there are two guards: a caller-supplied ceiling on records scanned, and a mid-loop
 * CPU check that stops scoring and reports partial results rather than letting the transaction die.
 * Reporting a truncated answer honestly beats an uncaught LimitException.
 *
 * <h3>Sharing and field access</h3>
 *
 * <p>Declared {@code with sharing} and queried {@code WITH USER_MODE}, so a user only ever resolves
 * contacts they can already see, through fields they can already read. Running this in system mode
 * would leak the existence and field values of restricted records through the duplicate report --
 * a resolution engine is an unusually effective data-exfiltration surface if it ignores sharing,
 * because its whole output is "here are records that are related to each other".
 */
public with sharing class CoalesceContactResolver {

    /** Hard ceiling regardless of what the caller asks for, so a bad parameter cannot blow the limit. */
    private static final Integer MAX_SCAN = 200;

    private static final Integer DEFAULT_SCAN = 100;

    /** Leaves headroom in the 10s budget to build and serialise the response after scoring stops. */
    private static final Integer CPU_BUDGET_MILLIS = 7000;

    /** A long review queue is not more useful than a short one; the top pairs are what get worked. */
    private static final Integer REVIEW_QUEUE_LIMIT = 25;

    /**
     * Ceilings for the one-against-many path, an order of magnitude above the all-pairs ceiling
     * because comparing one record to n others is linear rather than quadratic. The all-pairs report
     * exhausts its budget at roughly 130 contacts; this path covers thousands in the same time.
     */
    private static final Integer MAX_CANDIDATES = 2000;

    private static final Integer DEFAULT_CANDIDATES = 500;

    public class MemberView {
        @AuraEnabled public Id recordId { get; set; }
        @AuraEnabled public String name { get; set; }
        @AuraEnabled public String email { get; set; }
        @AuraEnabled public String phone { get; set; }
    }

    public class PairView {
        @AuraEnabled public Id leftId { get; set; }
        @AuraEnabled public Id rightId { get; set; }
        @AuraEnabled public String leftName { get; set; }
        @AuraEnabled public String rightName { get; set; }
        @AuraEnabled public Double score { get; set; }
        @AuraEnabled public String scoreText { get; set; }
        @AuraEnabled public String decision { get; set; }
        @AuraEnabled public String explanation { get; set; }
        @AuraEnabled public List<String> agreed { get; set; }
        @AuraEnabled public List<String> absentAttributes { get; set; }
    }

    public class ClusterView {
        @AuraEnabled public String clusterId { get; set; }
        @AuraEnabled public Integer size { get; set; }
        @AuraEnabled public List<MemberView> members { get; set; }
        @AuraEnabled public List<PairView> evidence { get; set; }
    }

    public class ResolutionView {
        @AuraEnabled public String schemaTag { get; set; }
        @AuraEnabled public Integer contactsScanned { get; set; }
        @AuraEnabled public Integer contactsAvailable { get; set; }
        @AuraEnabled public Integer pairsCompared { get; set; }
        @AuraEnabled public Integer matchCount { get; set; }
        @AuraEnabled public Integer reviewCount { get; set; }
        @AuraEnabled public Integer noMatchCount { get; set; }
        @AuraEnabled public Integer duplicateClusters { get; set; }
        @AuraEnabled public Integer cpuMillis { get; set; }
        @AuraEnabled public Boolean stoppedEarly { get; set; }
        @AuraEnabled public String note { get; set; }
        @AuraEnabled public List<ClusterView> clusters { get; set; }
        @AuraEnabled public List<PairView> reviewQueue { get; set; }
    }

    /**
     * The Contact schema. Weights total 13.5, matching the Java engine's patient schema so the two
     * can be compared directly.
     *
     * <p>Email carries the most weight because two contacts sharing an email address are almost
     * always the same person, while a shared postcode is weak evidence on its own -- thousands of
     * people share one. Name is weighted below email but above address because it is discriminating
     * and usually present. The point of the weights is that they encode which agreements are
     * actually informative, which a plain field-count comparison cannot express.
     */
    @TestVisible
    private static CoalesceScorer.Schema contactSchema() {
        return new CoalesceScorer.Schema(
            'contact',
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

    @AuraEnabled(cacheable=true)
    public static ResolutionView findDuplicates(Integer scanLimit) {
        Integer effectiveLimit = scanLimit == null || scanLimit <= 0
            ? DEFAULT_SCAN
            : Math.min(scanLimit, MAX_SCAN);

        List<Contact> contacts = [
            SELECT Id, FirstName, LastName, Name, Email, Phone, Birthdate, MailingPostalCode, MailingStreet
            FROM Contact
            WHERE LastName != NULL
            WITH USER_MODE
            ORDER BY CreatedDate DESC
            LIMIT :effectiveLimit
        ];
        Integer available = [SELECT COUNT() FROM Contact WHERE LastName != NULL WITH USER_MODE];

        return resolve(contacts, available, contactSchema());
    }

    /** What one contact's record page needs: who it looks like, and the evidence for each claim. */
    public class ContactMatchView {
        @AuraEnabled public Id subjectId { get; set; }
        @AuraEnabled public String subjectName { get; set; }
        @AuraEnabled public String schemaTag { get; set; }
        @AuraEnabled public Integer candidatesCompared { get; set; }
        @AuraEnabled public Integer candidatesAvailable { get; set; }
        @AuraEnabled public Integer cpuMillis { get; set; }
        @AuraEnabled public Boolean stoppedEarly { get; set; }
        @AuraEnabled public String note { get; set; }
        @AuraEnabled public List<PairView> matches { get; set; }
        @AuraEnabled public List<PairView> reviews { get; set; }
    }

    /**
     * Resolves a single contact against the rest of the corpus.
     *
     * <p>This is the shape a record page wants and it is also the cheap one. The all-pairs report
     * answers "who matches whom", which is n(n-1)/2 comparisons; this answers "who matches this one",
     * which is n-1. Dropping the quadratic term is what lets the candidate ceiling be thousands
     * rather than the ~130 contacts at which the report exhausts the Apex CPU budget.
     *
     * <p>Still a full scan of the candidate set, so it is linear rather than sublinear. Blocking --
     * comparing only against candidates sharing a cheap key -- is what would make it sublinear, and
     * remains unimplemented. At this scale the difference does not bite; at a million contacts the
     * SOQL row limit alone would stop this long before the CPU limit did.
     */
    /**
     * Accepts either a Contact id or a Person Account id, because in an org with person accounts
     * enabled a person is both, and navigating to the Contact redirects to the Account. A component
     * restricted to Contact record pages is therefore unreachable through normal navigation.
     *
     * <p>{@code PersonContactId} only exists on Account when the person-account feature is on, so it
     * is read through dynamic SOQL and guarded by a describe check. Referencing it statically would
     * make this class fail to compile in any org without the feature -- a portability cost paid for
     * nothing, since the field is needed only on the path where person accounts are in play.
     */
    private static Id resolveToContactId(Id recordId) {
        SObjectType kind = recordId.getSobjectType();
        if (kind == Contact.SObjectType) {
            return recordId;
        }
        if (kind != Account.SObjectType) {
            throw new AuraHandledException('This component works on contacts and person accounts.');
        }
        if (!Schema.SObjectType.Account.fields.getMap().containsKey('PersonContactId')) {
            throw new AuraHandledException('Person accounts are not enabled in this org.');
        }

        List<Account> rows = Database.queryWithBinds(
            'SELECT PersonContactId FROM Account WHERE Id = :recordId',
            new Map<String, Object>{ 'recordId' => recordId },
            AccessLevel.USER_MODE
        );
        if (rows.isEmpty()) {
            throw new AuraHandledException('That record is not available.');
        }
        Id personContactId = (Id) rows[0].get('PersonContactId');
        if (personContactId == null) {
            // A business account has no person behind it, so there is no one to deduplicate.
            throw new AuraHandledException('This is a business account, not a person.');
        }
        return personContactId;
    }

    @AuraEnabled(cacheable=true)
    public static ContactMatchView findMatchesFor(Id contactId, Integer scanLimit) {
        if (contactId == null) {
            throw new AuraHandledException('A record id is required.');
        }
        contactId = resolveToContactId(contactId);

        Integer effectiveLimit = scanLimit == null || scanLimit <= 0
            ? DEFAULT_CANDIDATES
            : Math.min(scanLimit, MAX_CANDIDATES);

        List<Contact> subjects = [
            SELECT Id, FirstName, LastName, Name, Email, Phone, Birthdate, MailingPostalCode, MailingStreet
            FROM Contact
            WHERE Id = :contactId
            WITH USER_MODE
            LIMIT 1
        ];
        if (subjects.isEmpty()) {
            // Either it does not exist or the running user cannot see it. Deliberately not
            // distinguished: saying which would leak the existence of records outside their sharing.
            throw new AuraHandledException('That contact is not available.');
        }
        Contact subject = subjects[0];

        List<Contact> candidates = [
            SELECT Id, FirstName, LastName, Name, Email, Phone, Birthdate, MailingPostalCode, MailingStreet
            FROM Contact
            WHERE Id != :contactId AND LastName != NULL
            WITH USER_MODE
            ORDER BY LastModifiedDate DESC
            LIMIT :effectiveLimit
        ];
        Integer available = [
            SELECT COUNT()
            FROM Contact
            WHERE Id != :contactId AND LastName != NULL
            WITH USER_MODE
        ];

        return matchOne(subject, candidates, available, contactSchema());
    }

    /**
     * The comparison itself, taking its candidates as an argument so a test can drive it with a
     * hand-built list and known ground truth.
     */
    @TestVisible
    private static ContactMatchView matchOne(
        Contact subject,
        List<Contact> candidates,
        Integer available,
        CoalesceScorer.Schema schema
    ) {
        Map<String, Object> subjectAttributes = attributesOf(subject);

        List<PairView> matches = new List<PairView>();
        List<PairView> reviews = new List<PairView>();
        Integer compared = 0;
        Boolean stoppedEarly = false;

        for (Contact candidate : candidates) {
            if (Limits.getCpuTime() > CPU_BUDGET_MILLIS) {
                stoppedEarly = true;
                break;
            }
            CoalesceScorer.ScoredPair scored = CoalesceScorer.score(
                schema,
                subjectAttributes,
                attributesOf(candidate)
            );
            compared++;

            if (scored.verdict == CoalesceScorer.Decision.MATCH) {
                matches.add(toPairView(subject, candidate, scored));
            } else if (scored.verdict == CoalesceScorer.Decision.REVIEW) {
                reviews.add(toPairView(subject, candidate, scored));
            }
        }

        ContactMatchView view = new ContactMatchView();
        view.subjectId = subject.Id;
        view.subjectName = subject.Name;
        view.schemaTag = schema.versionTag();
        view.candidatesCompared = compared;
        view.candidatesAvailable = available;
        view.stoppedEarly = stoppedEarly;
        view.matches = topByScore(matches, REVIEW_QUEUE_LIMIT);
        view.reviews = topByScore(reviews, REVIEW_QUEUE_LIMIT);
        view.cpuMillis = Limits.getCpuTime();
        view.note = stoppedEarly
            ? 'Stopped at the CPU budget after ' + compared + ' candidates.'
            : 'Compared against ' + compared + ' of ' + available + ' other contacts.';
        return view;
    }

    /**
     * The resolution itself, taking its records as an argument so a test can drive it with a
     * hand-built list and assert on known ground truth rather than on whatever the org contains.
     */
    @TestVisible
    private static ResolutionView resolve(
        List<Contact> contacts,
        Integer available,
        CoalesceScorer.Schema schema
    ) {
        Map<Id, Map<String, Object>> attributesById = new Map<Id, Map<String, Object>>();
        Map<Id, Contact> byId = new Map<Id, Contact>();
        for (Contact record : contacts) {
            byId.put(record.Id, record);
            attributesById.put(record.Id, attributesOf(record));
        }

        Map<Id, Id> parent = new Map<Id, Id>();
        for (Id recordId : byId.keySet()) {
            parent.put(recordId, recordId);
        }

        List<PairView> matched = new List<PairView>();
        List<PairView> reviews = new List<PairView>();
        Integer pairsCompared = 0;
        Integer matchCount = 0;
        Integer reviewCount = 0;
        Integer noMatchCount = 0;
        Boolean stoppedEarly = false;

        for (Integer i = 0; i < contacts.size() && !stoppedEarly; i++) {
            for (Integer j = i + 1; j < contacts.size(); j++) {
                if (Limits.getCpuTime() > CPU_BUDGET_MILLIS) {
                    // Out of budget. Returning what was scored so far, flagged, is more useful than
                    // an uncaught LimitException that discards the whole run.
                    stoppedEarly = true;
                    break;
                }
                Contact left = contacts[i];
                Contact right = contacts[j];
                CoalesceScorer.ScoredPair scored = CoalesceScorer.score(
                    schema,
                    attributesById.get(left.Id),
                    attributesById.get(right.Id)
                );
                pairsCompared++;

                if (scored.verdict == CoalesceScorer.Decision.MATCH) {
                    matchCount++;
                    matched.add(toPairView(left, right, scored));
                    union(parent, left.Id, right.Id);
                } else if (scored.verdict == CoalesceScorer.Decision.REVIEW) {
                    reviewCount++;
                    reviews.add(toPairView(left, right, scored));
                } else {
                    noMatchCount++;
                }
            }
        }

        ResolutionView view = new ResolutionView();
        view.schemaTag = schema.versionTag();
        view.contactsScanned = contacts.size();
        view.contactsAvailable = available;
        view.pairsCompared = pairsCompared;
        view.matchCount = matchCount;
        view.reviewCount = reviewCount;
        view.noMatchCount = noMatchCount;
        view.stoppedEarly = stoppedEarly;
        view.clusters = buildClusters(parent, byId, matched);
        view.duplicateClusters = view.clusters.size();
        view.reviewQueue = topByScore(reviews, REVIEW_QUEUE_LIMIT);
        view.cpuMillis = Limits.getCpuTime();
        view.note = buildNote(view);
        return view;
    }

    /**
     * Groups records by union-find root, keeps only the clusters with more than one member, and
     * attaches the matched pairs that justify each one.
     *
     * <p>Cluster ids are the smallest member id rather than the union-find root, because the root is
     * whichever node won by rank and therefore depends on the order links were applied. Deriving the
     * id from membership makes it a pure function of the set: same members, same id, every run.
     */
    private static List<ClusterView> buildClusters(
        Map<Id, Id> parent,
        Map<Id, Contact> byId,
        List<PairView> matched
    ) {
        Map<Id, List<Id>> membersByRoot = new Map<Id, List<Id>>();
        for (Id recordId : byId.keySet()) {
            Id root = find(parent, recordId);
            if (!membersByRoot.containsKey(root)) {
                membersByRoot.put(root, new List<Id>());
            }
            membersByRoot.get(root).add(recordId);
        }

        List<ClusterView> clusters = new List<ClusterView>();
        for (List<Id> members : membersByRoot.values()) {
            if (members.size() < 2) {
                continue;
            }
            members.sort();

            ClusterView cluster = new ClusterView();
            cluster.clusterId = String.valueOf(members[0]);
            cluster.size = members.size();
            cluster.members = new List<MemberView>();
            Set<Id> memberSet = new Set<Id>(members);
            for (Id memberId : members) {
                cluster.members.add(toMemberView(byId.get(memberId)));
            }
            cluster.evidence = new List<PairView>();
            for (PairView pair : matched) {
                if (memberSet.contains(pair.leftId) && memberSet.contains(pair.rightId)) {
                    cluster.evidence.add(pair);
                }
            }
            clusters.add(cluster);
        }

        // Largest first: the biggest cluster is both the most useful to review and the most likely to
        // be wrong, since over-merging shows up as one oversized entity.
        for (Integer i = 0; i < clusters.size(); i++) {
            for (Integer j = i + 1; j < clusters.size(); j++) {
                if (clusters[j].size > clusters[i].size) {
                    ClusterView swap = clusters[i];
                    clusters[i] = clusters[j];
                    clusters[j] = swap;
                }
            }
        }
        return clusters;
    }

    private static Id find(Map<Id, Id> parent, Id node) {
        Id root = node;
        while (parent.get(root) != root) {
            root = parent.get(root);
        }
        // Path compression: point everything on the walk straight at the root so repeated lookups
        // during cluster assembly stay near-constant instead of re-walking the chain.
        Id cursor = node;
        while (parent.get(cursor) != root) {
            Id next = parent.get(cursor);
            parent.put(cursor, root);
            cursor = next;
        }
        return root;
    }

    private static void union(Map<Id, Id> parent, Id a, Id b) {
        Id rootA = find(parent, a);
        Id rootB = find(parent, b);
        if (rootA != rootB) {
            parent.put(rootA, rootB);
        }
    }

    private static Map<String, Object> attributesOf(Contact record) {
        return new Map<String, Object>{
            'email' => record.Email,
            'full_name' => fullName(record),
            'birthdate' => record.Birthdate,
            'phone' => record.Phone,
            'postcode' => record.MailingPostalCode,
            'street' => record.MailingStreet
        };
    }

    private static String fullName(Contact record) {
        String first = record.FirstName == null ? '' : record.FirstName.trim();
        String last = record.LastName == null ? '' : record.LastName.trim();
        String combined = (first + ' ' + last).trim();
        return String.isBlank(combined) ? null : combined;
    }

    private static MemberView toMemberView(Contact record) {
        MemberView member = new MemberView();
        member.recordId = record.Id;
        member.name = record.Name;
        member.email = record.Email;
        member.phone = record.Phone;
        return member;
    }

    private static PairView toPairView(Contact left, Contact right, CoalesceScorer.ScoredPair scored) {
        PairView pair = new PairView();
        pair.leftId = left.Id;
        pair.rightId = right.Id;
        pair.leftName = left.Name;
        pair.rightName = right.Name;
        pair.score = scored.score;
        pair.scoreText = String.valueOf(Decimal.valueOf(scored.score).setScale(3, System.RoundingMode.HALF_UP));
        pair.decision = scored.decision;
        pair.explanation = scored.explanation;
        pair.agreed = scored.agreed;
        pair.absentAttributes = scored.absentAttributes;
        return pair;
    }

    private static List<PairView> topByScore(List<PairView> pairs, Integer keep) {
        for (Integer i = 0; i < pairs.size(); i++) {
            for (Integer j = i + 1; j < pairs.size(); j++) {
                if (pairs[j].score > pairs[i].score) {
                    PairView swap = pairs[i];
                    pairs[i] = pairs[j];
                    pairs[j] = swap;
                }
            }
        }
        if (pairs.size() <= keep) {
            return pairs;
        }
        List<PairView> top = new List<PairView>();
        for (Integer i = 0; i < keep; i++) {
            top.add(pairs[i]);
        }
        return top;
    }

    private static String buildNote(ResolutionView view) {
        if (view.stoppedEarly) {
            return 'Stopped at the CPU budget after ' + view.pairsCompared +
                ' pairs. This is the quadratic comparison count hitting the governor limit, which is ' +
                'exactly what blocking exists to prevent.';
        }
        if (view.contactsAvailable > view.contactsScanned) {
            return 'Scanned the ' + view.contactsScanned + ' most recent of ' + view.contactsAvailable +
                ' contacts. All-pairs comparison is quadratic, so the scan is capped rather than ' +
                'risking the CPU limit.';
        }
        return 'Compared every pair among all ' + view.contactsScanned + ' contacts.';
    }
}
