package com.coalesce.eval;

import com.coalesce.domain.cluster.ClusterState;
import com.coalesce.domain.model.RecordId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Precision, recall and F1 against ground truth, measured two ways.
 *
 * <h2>Why both, and why they disagree</h2>
 *
 * <p><b>Pairwise</b> metrics treat the task as classifying every record pair as same-entity or not. They
 * are the standard measure and they have a known bias: a cluster of n records contributes n(n-1)/2
 * pairs, so a single large entity dominates the score. Getting one 20-record cluster right earns 190
 * true positives, while getting nineteen 2-record clusters right earns 19. A system can therefore post
 * strong pairwise numbers while being wrong about most <i>entities</i>.
 *
 * <p><b>Cluster-level</b> metrics count an entity as correct only when the predicted cluster matches the
 * true one exactly — no missing member, no extra. It is unforgiving, and deliberately so: it is the
 * measure that corresponds to what a user of the system experiences, since a patient record with one
 * visit missing is a wrong answer regardless of how many pairs were right.
 *
 * <p>Reporting only one of them is how a resolution system ends up sounding better than it is. The gap
 * between them is itself diagnostic: high pairwise with low cluster-level means the errors are
 * concentrated in a few large entities, which is a different problem from errors scattered everywhere.
 *
 * @param truePositives  pairs correctly placed together
 * @param falsePositives pairs wrongly placed together — over-merging
 * @param falseNegatives pairs wrongly kept apart — under-merging
 */
public record QualityMetrics(long truePositives, long falsePositives, long falseNegatives) {

    public double precision() {
        long predicted = truePositives + falsePositives;
        return predicted == 0 ? 1.0 : (double) truePositives / predicted;
    }

    public double recall() {
        long actual = truePositives + falseNegatives;
        return actual == 0 ? 1.0 : (double) truePositives / actual;
    }

    public double f1() {
        double p = precision();
        double r = recall();
        return (p + r) == 0.0 ? 0.0 : 2 * p * r / (p + r);
    }

    /**
     * Pairwise scoring over every record pair.
     *
     * <p>Deliberately O(n²) and exhaustive rather than sampled — this is measurement, not the hot path,
     * and an approximate quality number would defeat the purpose of measuring at all.
     */
    public static QualityMetrics pairwise(List<RecordId> records, ClusterState predicted,
                                         Map<RecordId, String> truth) {
        long tp = 0;
        long fp = 0;
        long fn = 0;

        for (int i = 0; i < records.size(); i++) {
            for (int j = i + 1; j < records.size(); j++) {
                RecordId a = records.get(i);
                RecordId b = records.get(j);
                boolean together = predicted.connected(a, b);
                boolean shouldBe = truth.get(a).equals(truth.get(b));

                if (together && shouldBe) {
                    tp++;
                } else if (together) {
                    fp++;
                } else if (shouldBe) {
                    fn++;
                }
            }
        }
        return new QualityMetrics(tp, fp, fn);
    }

    /**
     * Exact-cluster scoring: a predicted cluster counts only if it equals a true cluster member for
     * member.
     *
     * <p>There is no partial credit, which makes this stricter than B-cubed or the variation-of-information
     * measures used in the literature. Those spread credit across near-misses and are better for tracking
     * incremental progress; exact matching is used here because it answers the blunter question a user
     * actually asks — how many entities did you get completely right.
     */
    public static ClusterAccuracy exactClusters(ClusterState predicted, Map<RecordId, String> truth) {
        Map<RecordId, List<RecordId>> predictedClusters = predicted.clusters();

        Set<Set<RecordId>> trueClusters = new HashSet<>();
        Map<String, Set<RecordId>> byLabel = new java.util.LinkedHashMap<>();
        truth.forEach((id, label) -> byLabel.computeIfAbsent(label, key -> new HashSet<>()).add(id));
        trueClusters.addAll(byLabel.values());

        int exact = 0;
        for (List<RecordId> members : predictedClusters.values()) {
            if (trueClusters.contains(new HashSet<>(members))) {
                exact++;
            }
        }
        return new ClusterAccuracy(exact, predictedClusters.size(), trueClusters.size());
    }

    /**
     * @param exactlyCorrect predicted clusters identical to a true cluster
     * @param predicted      clusters the engine produced
     * @param actual         clusters the ground truth contains
     */
    public record ClusterAccuracy(int exactlyCorrect, int predicted, int actual) {

        public double precision() {
            return predicted == 0 ? 1.0 : (double) exactlyCorrect / predicted;
        }

        public double recall() {
            return actual == 0 ? 1.0 : (double) exactlyCorrect / actual;
        }

        public double f1() {
            double p = precision();
            double r = recall();
            return (p + r) == 0.0 ? 0.0 : 2 * p * r / (p + r);
        }
    }
}
