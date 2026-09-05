// Verifies that resolution works when handed a Person Account id rather than a Contact id.
// sf apex run --file scripts/apex/matchPersonAccount.apex --target-org trialorg
List<Account> accounts = [
    SELECT Id, Name, PersonContactId
    FROM Account
    WHERE Name = 'RyanDemo Tinkerson' AND IsPersonAccount = TRUE
    LIMIT 1
];

if (accounts.isEmpty()) {
    System.debug('COALESCE| no person account named RyanDemo Tinkerson');
} else {
    Account person = accounts[0];
    System.debug('COALESCE| account=' + person.Id + ' personContact=' + person.PersonContactId);

    CoalesceContactResolver.ContactMatchView view =
        CoalesceContactResolver.findMatchesFor(person.Id, 500);

    System.debug('COALESCE| subject=' + view.subjectName +
        ' compared=' + view.candidatesCompared + ' cpu=' + view.cpuMillis + 'ms');
    System.debug('COALESCE| matches=' + view.matches.size() + ' reviews=' + view.reviews.size());
    for (CoalesceContactResolver.PairView pair : view.matches) {
        System.debug('COALESCE|   MATCH  ' + pair.rightName + ' :: ' + pair.explanation);
    }
    System.debug('COALESCE| open=/lightning/r/Account/' + person.Id + '/view');
}
