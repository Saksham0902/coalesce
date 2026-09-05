// Resolves a single contact against the corpus, the way the record page does.
// sf apex run --file scripts/apex/matchOneContact.apex --target-org trialorg
List<Contact> found = [
    SELECT Id, Name
    FROM Contact
    WHERE LastName = 'Tinkerson' AND FirstName = 'RyanDemo'
    LIMIT 1
];
if (found.isEmpty()) {
    System.debug('COALESCE| no seed contact found');
} else {
    Contact subject = found[0];
    CoalesceContactResolver.ContactMatchView view =
        CoalesceContactResolver.findMatchesFor(subject.Id, 500);

    System.debug('COALESCE| subject=' + view.subjectName);
    System.debug('COALESCE| compared=' + view.candidatesCompared +
        ' of ' + view.candidatesAvailable + ' candidates  cpu=' + view.cpuMillis + 'ms');
    System.debug('COALESCE| matches=' + view.matches.size() + '  reviews=' + view.reviews.size());
    for (CoalesceContactResolver.PairView pair : view.matches) {
        System.debug('COALESCE|   MATCH  ' + pair.rightName + ' :: ' + pair.explanation);
    }
    for (CoalesceContactResolver.PairView pair : view.reviews) {
        System.debug('COALESCE|   REVIEW ' + pair.rightName + ' :: ' + pair.explanation);
    }
    System.debug('COALESCE| open=/lightning/r/Contact/' + subject.Id + '/view');
}
