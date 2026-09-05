// Measures wall-clock time, CPU and SOQL usage for the record-page path at several scan sizes.
// sf apex run --file scripts/apex/benchmarkMatch.apex --target-org trialorg
Contact subject = [
    SELECT Id FROM Contact WHERE LastName = 'Tinkerson' AND FirstName = 'RyanDemo' LIMIT 1
];

for (Integer scan : new List<Integer>{ 25, 155, 500 }) {
    Integer queriesBefore = Limits.getQueries();
    Integer cpuBefore = Limits.getCpuTime();
    Long startedAt = System.currentTimeMillis();

    CoalesceContactResolver.ContactMatchView view =
        CoalesceContactResolver.findMatchesFor(subject.Id, scan);

    Long wall = System.currentTimeMillis() - startedAt;
    System.debug('COALESCE| scan=' + scan +
        ' compared=' + view.candidatesCompared +
        ' wall=' + wall + 'ms' +
        ' cpu=' + (Limits.getCpuTime() - cpuBefore) + 'ms' +
        ' soql=' + (Limits.getQueries() - queriesBefore) +
        ' matches=' + view.matches.size());
}
