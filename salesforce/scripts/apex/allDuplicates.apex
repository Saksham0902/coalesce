// Full-corpus duplicate scan over every contact, using blocking to stay inside the CPU budget.
// All-pairs on 156 contacts is 12,090 comparisons and exhausts the limit around 130 records.
// Blocking generates candidates only within buckets that share a cheap key, which makes the
// comparison count proportional to bucket sizes rather than to the square of the corpus.
//
// sf apex run --file scripts/apex/allDuplicates.apex --target-org trialorg

CoalesceScorer.Schema schema = new CoalesceScorer.Schema(
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

List<Contact> corpus = [
    SELECT Id, Name, FirstName, LastName, Email, Phone, Birthdate, MailingPostalCode, MailingStreet
    FROM Contact
    WHERE LastName != NULL
    WITH USER_MODE
    LIMIT 2000
];

Map<Id, Contact> byId = new Map<Id, Contact>();
Map<Id, Map<String, Object>> attributesById = new Map<Id, Map<String, Object>>();
for (Contact record : corpus) {
    byId.put(record.Id, record);
    String first = record.FirstName == null ? '' : record.FirstName.trim();
    String last = record.LastName == null ? '' : record.LastName.trim();
    String combined = (first + ' ' + last).trim();
    attributesById.put(
        record.Id,
        new Map<String, Object>{
            'email' => record.Email,
            'full_name' => String.isBlank(combined) ? null : combined,
            'birthdate' => record.Birthdate,
            'phone' => record.Phone,
            'postcode' => record.MailingPostalCode,
            'street' => record.MailingStreet
        }
    );
}

// Blocking keys. Three of them, because any single key misses real duplicates: an exact email key
// misses people recorded under two addresses, and a surname key misses "Cheong" versus
// "CheongTinkerson". A record joins every bucket it qualifies for, and a pair becomes a candidate if
// it shares at least one. This trades a larger candidate set for recall.
Map<String, List<Id>> buckets = new Map<String, List<Id>>();
for (Contact record : corpus) {
    List<String> keys = new List<String>();
    if (String.isNotBlank(record.Email)) {
        keys.add('email:' + record.Email.trim().toLowerCase());
    }
    String last = record.LastName == null ? '' : record.LastName.trim().toLowerCase();
    if (last != '') {
        keys.add('surname:' + last);
        keys.add('surname4:' + (last.length() <= 4 ? last : last.substring(0, 4)));
    }
    for (String key : keys) {
        if (!buckets.containsKey(key)) {
            buckets.put(key, new List<Id>());
        }
        buckets.get(key).add(record.Id);
    }
}

// Candidate pairs, deduplicated: a pair sharing two keys must only be scored once.
Set<String> candidateKeys = new Set<String>();
List<Id> leftIds = new List<Id>();
List<Id> rightIds = new List<Id>();
for (List<Id> bucket : buckets.values()) {
    if (bucket.size() < 2) {
        continue;
    }
    for (Integer i = 0; i < bucket.size(); i++) {
        for (Integer j = i + 1; j < bucket.size(); j++) {
            Id a = bucket[i];
            Id b = bucket[j];
            String pairKey = a < b ? a + '|' + b : b + '|' + a;
            if (candidateKeys.add(pairKey)) {
                leftIds.add(a < b ? a : b);
                rightIds.add(a < b ? b : a);
            }
        }
    }
}

// Score the candidates and record which pairs join.
Map<Id, Set<Id>> adjacency = new Map<Id, Set<Id>>();
List<String> matchLines = new List<String>();
List<String> reviewLines = new List<String>();
Integer matchCount = 0;
Integer reviewCount = 0;

for (Integer index = 0; index < leftIds.size(); index++) {
    Id a = leftIds[index];
    Id b = rightIds[index];
    CoalesceScorer.ScoredPair scored = CoalesceScorer.score(
        schema,
        attributesById.get(a),
        attributesById.get(b)
    );
    if (scored.verdict == CoalesceScorer.Decision.MATCH) {
        matchCount++;
        if (!adjacency.containsKey(a)) {
            adjacency.put(a, new Set<Id>());
        }
        if (!adjacency.containsKey(b)) {
            adjacency.put(b, new Set<Id>());
        }
        adjacency.get(a).add(b);
        adjacency.get(b).add(a);
    } else if (scored.verdict == CoalesceScorer.Decision.REVIEW) {
        reviewCount++;
        if (reviewCount <= 15) {
            reviewLines.add(
                byId.get(a).Name + ' <-> ' + byId.get(b).Name + ' :: ' + scored.explanation
            );
        }
    }
}

// Connected components over the joining pairs: breadth-first, since anonymous Apex cannot declare
// the recursive helper a union-find would want.
Set<Id> visited = new Set<Id>();
List<List<Id>> clusters = new List<List<Id>>();
for (Id start : adjacency.keySet()) {
    if (visited.contains(start)) {
        continue;
    }
    List<Id> component = new List<Id>();
    List<Id> queue = new List<Id>{ start };
    visited.add(start);
    while (!queue.isEmpty()) {
        Id current = queue.remove(queue.size() - 1);
        component.add(current);
        for (Id neighbour : adjacency.get(current)) {
            if (!visited.contains(neighbour)) {
                visited.add(neighbour);
                queue.add(neighbour);
            }
        }
    }
    clusters.add(component);
}

System.debug('COALESCE| contacts=' + corpus.size() +
    ' buckets=' + buckets.size() +
    ' candidatePairs=' + leftIds.size() +
    ' (all-pairs would be ' + (corpus.size() * (corpus.size() - 1) / 2) + ')');
System.debug('COALESCE| matched=' + matchCount + ' review=' + reviewCount +
    ' clusters=' + clusters.size() + ' cpu=' + Limits.getCpuTime() + 'ms');

Integer clusterIndex = 0;
for (List<Id> component : clusters) {
    component.sort();
    clusterIndex++;
    List<String> labels = new List<String>();
    for (Id memberId : component) {
        Contact member = byId.get(memberId);
        String email = String.isBlank(member.Email) ? 'no email' : member.Email;
        labels.add(member.Name + ' [' + email + '] ' + memberId);
    }
    System.debug('COALESCE| CLUSTER ' + clusterIndex + ' size=' + component.size());
    for (String label : labels) {
        System.debug('COALESCE|    ' + label);
    }
}

for (String line : reviewLines) {
    System.debug('COALESCE| REVIEW ' + line);
}
