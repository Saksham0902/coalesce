package com.coalesce.eval;

import com.coalesce.domain.cluster.ClusterPolicy;
import com.coalesce.domain.match.ComparatorRegistry;
import com.coalesce.domain.match.MatchDecision;
import com.coalesce.domain.match.PairScorer;
import com.coalesce.domain.match.ScoredPair;
import com.coalesce.domain.match.comparator.DateComparator;
import com.coalesce.domain.match.comparator.ExactComparator;
import com.coalesce.domain.match.comparator.JaroWinklerComparator;
import com.coalesce.domain.match.comparator.LevenshteinComparator;
import com.coalesce.domain.match.comparator.NumericComparator;
import com.coalesce.domain.match.comparator.SoundexComparator;
import com.coalesce.domain.match.comparator.TokenCosineComparator;
import com.coalesce.domain.model.RecordId;
import com.coalesce.domain.model.SourceRecord;
import com.coalesce.domain.schema.ResolutionSchema;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Runs the engine against the labelled fixture and prints what it did.
 *
 * <p>This exists because the REST API does not yet, and an engine nobody can watch run is impossible to
 * develop against. It also produces the comparison that justifies {@link ClusterPolicy}: the same
 * records resolved twice, once with the policy and once with matches applied straight into union-find
 * the way a naive implementation would.
 *
 * <pre>
 *   mvn -q compile exec:java
 *   mvn -q compile exec:java -Dexec.args="path\to\other.csv"
 * </pre>
 */
public final class ResolutionDemo {

    private static final Path DEFAULT_FIXTURE = Path.of("src/test/resources/fixtures/patients.csv");

    public static void main(String[] args) {
        Path fixturePath = args.length > 0 ? Path.of(args[0]) : DEFAULT_FIXTURE;
        LabelledFixture fixture = LabelledFixture.load(fixturePath);
        List<SourceRecord> records = fixture.records();
        List<RecordId> ids = records.stream().map(SourceRecord::id).toList();

        ResolutionSchema schema = patientSchema();
        PairScorer scorer = new PairScorer(registry());
        ClusterPolicy.Config policyConfig = ClusterPolicy.Config.defaults();
        ResolutionRun run = new ResolutionRun(schema, scorer, policyConfig);

        heading("Input");
        line("fixture", fixturePath.toString());
        line("records", String.valueOf(records.size()));
        line("sources", String.valueOf(records.stream().map(r -> r.id().source()).distinct().count()));
        line("true entities", String.valueOf(fixture.trueEntityCount()));
        line("attributes", String.join(", ", fixture.attributeNames()));

        heading("Schema: " + schema.versionTag());
        System.out.printf("  %-14s %-14s %8s  %s%n", "ATTRIBUTE", "COMPARATOR", "WEIGHT", "BLOCKING");
        schema.attributes().forEach(spec -> System.out.printf("  %-14s %-14s %8.1f  %s%n",
                spec.attribute(), spec.comparator(), spec.weight(), spec.blocking() ? "yes" : ""));
        System.out.printf("%n  match >= %.2f, review >= %.2f, evidence guard: %d attributes and %.0f%% of weight%n",
                schema.matchThreshold(), schema.reviewThreshold(),
                schema.minContributingAttributes(), schema.minEvidenceWeightShare() * 100);

        ResolutionRun.Result guarded = run.resolve(records, true);
        ResolutionRun.Result naive = run.resolve(records, false);

        heading("Pair scoring");
        line("pairs compared", "%,d  (all pairs - blocking is not implemented yet)"
                .formatted(guarded.pairsCompared()));
        for (MatchDecision decision : MatchDecision.values()) {
            line(decision.name().toLowerCase().replace('_', ' '),
                    String.valueOf(guarded.decisionCounts().getOrDefault(decision, 0)));
        }

        heading("Cluster policy verdicts");
        guarded.outcomeCounts().entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().name()))
                .forEach(e -> line(e.getKey().name().toLowerCase().replace('_', ' '), String.valueOf(e.getValue())));

        List<com.coalesce.domain.cluster.LinkVerdict> refusals = guarded.refusals();
        if (!refusals.isEmpty()) {
            System.out.println("\n  Refusals in full - every one explains itself:");
            refusals.forEach(v -> System.out.printf("    %s%n      %s%n", v.pair(), v.detail()));
        }

        heading("Result: policy vs naive union-find");
        QualityMetrics guardedPairs = QualityMetrics.pairwise(ids, guarded.clusters(), fixture.trueEntity());
        QualityMetrics naivePairs = QualityMetrics.pairwise(ids, naive.clusters(), fixture.trueEntity());
        var guardedClusters = QualityMetrics.exactClusters(guarded.clusters(), fixture.trueEntity());
        var naiveClusters = QualityMetrics.exactClusters(naive.clusters(), fixture.trueEntity());

        System.out.printf("  %-26s %>12s %12s%n".replace(">", ""), "", "WITH POLICY", "NAIVE");
        row("clusters found", guarded.clusterCount(), naive.clusterCount());
        row("true entities", fixture.trueEntityCount(), fixture.trueEntityCount());
        System.out.println();
        rowf("pairwise precision", guardedPairs.precision(), naivePairs.precision());
        rowf("pairwise recall", guardedPairs.recall(), naivePairs.recall());
        rowf("pairwise F1", guardedPairs.f1(), naivePairs.f1());
        System.out.println();
        row("wrongly merged pairs", (int) guardedPairs.falsePositives(), (int) naivePairs.falsePositives());
        row("wrongly split pairs", (int) guardedPairs.falseNegatives(), (int) naivePairs.falseNegatives());
        System.out.println();
        row("entities exactly right", guardedClusters.exactlyCorrect(), naiveClusters.exactlyCorrect());
        rowf("cluster-level F1", guardedClusters.f1(), naiveClusters.f1());

        heading("Largest clusters found (with policy)");
        guarded.clusters().clusters().values().stream()
                .sorted(Comparator.comparingInt((List<RecordId> m) -> m.size()).reversed())
                .limit(5)
                .forEach(members -> {
                    long distinctTruth = members.stream().map(id -> fixture.trueEntity().get(id)).distinct().count();
                    System.out.printf("    %d records, %s: %s%n", members.size(),
                            distinctTruth == 1 ? "all one true entity" : distinctTruth + " true entities mixed in",
                            members.stream().map(RecordId::qualified).sorted().toList());
                });

        List<ScoredPair> review = guarded.inReviewBand();
        heading("Review queue - " + review.size() + " pairs a human should decide");
        review.stream()
                .sorted(Comparator.comparingDouble(ScoredPair::score).reversed())
                .limit(8)
                .forEach(pair -> {
                    boolean same = fixture.trueEntity().get(pair.pair().left())
                            .equals(fixture.trueEntity().get(pair.pair().right()));
                    System.out.printf("    %.3f  %-28s %-28s  truth: %s%n", pair.score(),
                            pair.pair().left().qualified(), pair.pair().right().qualified(),
                            same ? "SAME entity"  : "different");
                    System.out.printf("           agreed on %s%n", pair.attributeScores().entrySet().stream()
                            .map(e -> "%s=%.2f".formatted(e.getKey(), e.getValue())).toList());
                    if (!pair.absentAttributes().isEmpty()) {
                        System.out.printf("           not comparable (renormalised over, not scored 0): %s%n",
                                pair.absentAttributes());
                    }
                });

        heading("Caveats");
        System.out.println("""
                  - All pairs compared. Blocking is unimplemented, so this does not scale past a few
                    thousand records; at 1M records this loop is ~500 billion comparisons.
                  - Weights and thresholds are hand-set, not learned. Fellegi-Sunter with EM would be
                    better calibrated and is not implemented.
                  - One small hand-labelled fixture is not a benchmark. Numbers here indicate the engine
                    works; they are not a claim about accuracy on real data.""");
    }

    /**
     * A patient-matching schema.
     *
     * <p>Weights encode how much each agreement is worth as evidence. National id dominates because
     * agreement on one is near-proof of identity, while postcode is deliberately low — thousands of
     * people share one, so it earns its keep as a blocking key rather than as evidence.
     *
     * <p>Postcode is the blocking attribute: high enough cardinality to cut the candidate space hard,
     * and stable enough that true matches rarely disagree on it. Surname would be the usual second
     * blocking key; it is not split out as its own attribute in this fixture.
     */
    private static ResolutionSchema patientSchema() {
        return ResolutionSchema.named("patient")
                .version(1)
                .compare("full_name", "jaro-winkler", 3.0)
                .compare("dob", "date", 2.5)
                .blockOn("postcode", "exact", 1.5)
                .compare("phone", "exact", 1.5)
                .compare("address", "token-cosine", 1.0)
                .compare("national_id", "exact", 4.0)
                .thresholds(0.70, 0.87)
                // Two comparable attributes and 35% of total weight. Without this, a pair sharing only
                // a postcode scores a perfect and meaningless 1.0 after renormalisation.
                .evidenceGuard(2, 0.35)
                .build();
    }

    private static ComparatorRegistry registry() {
        return new ComparatorRegistry(List.of(new ExactComparator(), new JaroWinklerComparator(),
                new LevenshteinComparator(), new SoundexComparator(), new TokenCosineComparator(),
                new NumericComparator(), new DateComparator()));
    }

    private static void heading(String title) {
        System.out.printf("%n%s%n%s%n", title.toUpperCase(), "-".repeat(Math.max(20, title.length())));
    }

    private static void line(String label, String value) {
        System.out.printf("  %-22s %s%n", label, value);
    }

    private static void row(String label, int guarded, int naive) {
        System.out.printf("  %-26s %12d %12d%n", label, guarded, naive);
    }

    private static void rowf(String label, double guarded, double naive) {
        System.out.printf("  %-26s %12.3f %12.3f%n", label, guarded, naive);
    }

    private ResolutionDemo() {
    }
}
