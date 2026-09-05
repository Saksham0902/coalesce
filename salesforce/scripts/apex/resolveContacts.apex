// Runs resolution over the org's Contacts and prints the result.
// sf apex run --file scripts/apex/resolveContacts.apex --target-org trialorg
CoalesceContactResolver.ResolutionView view = CoalesceContactResolver.findDuplicates(100);

System.debug('COALESCE| schema=' + view.schemaTag);
System.debug('COALESCE| scanned=' + view.contactsScanned + ' of ' + view.contactsAvailable + ' available');
System.debug('COALESCE| pairs=' + view.pairsCompared +
    ' match=' + view.matchCount + ' review=' + view.reviewCount + ' nomatch=' + view.noMatchCount);
System.debug('COALESCE| clusters=' + view.duplicateClusters +
    ' cpu=' + view.cpuMillis + 'ms stoppedEarly=' + view.stoppedEarly);
System.debug('COALESCE| note=' + view.note);

for (CoalesceContactResolver.ClusterView cluster : view.clusters) {
    List<String> names = new List<String>();
    for (CoalesceContactResolver.MemberView member : cluster.members) {
        names.add(member.name);
    }
    System.debug('COALESCE| CLUSTER size=' + cluster.size + ' :: ' + String.join(names, ' | '));
    for (CoalesceContactResolver.PairView pair : cluster.evidence) {
        System.debug('COALESCE|     ' + pair.explanation);
    }
}

Integer shown = 0;
for (CoalesceContactResolver.PairView pair : view.reviewQueue) {
    if (shown++ >= 6) {
        break;
    }
    System.debug('COALESCE| REVIEW ' + pair.leftName + ' <-> ' + pair.rightName + ' :: ' + pair.explanation);
}
