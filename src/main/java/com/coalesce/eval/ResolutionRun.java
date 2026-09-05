package com.coalesce.eval;

import com.coalesce.domain.cluster.ClusterPolicy;
import com.coalesce.domain.cluster.ClusterState;
import com.coalesce.domain.cluster.LinkVerdict;
import com.coalesce.domain.match.MatchDecision;
import com.coalesce.domain.match.PairScorer;
import com.coalesce.domain.match.ScoredPair;
import com.coalesce.domain.model.RecordId;
import com.coalesce.domain.model.SourceRecord;
import com.coalesce.domain.schema.ResolutionSchema;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * One end-to-end resolution over a fixed record set: score every pair, gate each match through
 * {@link ClusterPolicy}, apply the ones allowed.
 *
 * <p><b>This compares all pairs.</b> Blocking is not implemented yet, so candidate generation is
 * n(n-1)/2 — acceptable at fixture size and hopeless beyond a few thousand records, which is stated
 * plainly rather than hidden behind the word "resolver". When blocking lands it replaces the pair loop
 * here and nothing else, which is the point of having kept scoring and clustering separate.
 *
 * <p>The run can be executed with the policy engaged or bypassed. Bypassing it applies every {@code
 * MATCH} straight into union-find, which is what a naive implementation does, and comparing the two is
 * the most direct demonstration of what the policy is for.
 */
public final class ResolutionRun {

    /** Everything a caller needs to report on, judge quality against, or explain a decision from. */
    public record Result(
            ClusterState clusters,
            List<ScoredPair> scored,
            List<LinkVerdict> verdicts,
            Map<MatchDecision, Integer> decisionCounts,
            Map<LinkVerdict.Outcome, Integer> outcomeCounts,
            int pairsCompared,
            long comparisonsAvoided) {

        public int clusterCount() {
            return clusters.clusterCount();
        }

        public List<ScoredPair> inReviewBand() {
            return scored.stream().filter(pair -> pair.decision() == MatchDecision.REVIEW).toList();
        }

        public List<LinkVerdict> refusals() {
            return verdicts.stream().filter(LinkVerdict::refused).toList();
        }
    }

    private final ResolutionSchema schema;
    private final PairScorer scorer;
    private final ClusterPolicy.Config policyConfig;

    public ResolutionRun(ResolutionSchema schema, PairScorer scorer, ClusterPolicy.Config policyConfig) {
        this.schema = schema;
        this.scorer = scorer;
        this.policyConfig = policyConfig;
    }

    public Result resolve(List<SourceRecord> records, boolean applyPolicy) {
        Map<RecordId, SourceRecord> byId = new HashMap<>();
        records.forEach(record -> byId.put(record.id(), record));

        ClusterState clusters = new ClusterState(byId.keySet());

        // The policy needs scores for pairs the main loop may not have reached yet, so scoring is
        // memoised and shared. Without the cache, cohesion sampling would rescore the same boundary
        // pairs repeatedly and dominate the run.
        Map<String, OptionalDouble> cache = new HashMap<>();
        ClusterPolicy.ScoreOracle oracle = (left, right) -> {
            String key = left.qualified().compareTo(right.qualified()) <= 0
                    ? left.qualified() + '|' + right.qualified()
                    : right.qualified() + '|' + left.qualified();
            return cache.computeIfAbsent(key, ignored -> {
                SourceRecord a = byId.get(left);
                SourceRecord b = byId.get(right);
                if (a == null || b == null) {
                    return OptionalDouble.empty();
                }
                ScoredPair pair = scorer.score(schema, a, b);
                // An unscoreable pair reports empty rather than 0.0, so cohesion averages over pairs
                // that were actually comparable instead of being dragged down by missing data.
                return pair.attributeScores().isEmpty()
                        ? OptionalDouble.empty()
                        : OptionalDouble.of(pair.score());
            });
        };

        ClusterPolicy policy = new ClusterPolicy(policyConfig, oracle);

        List<ScoredPair> scored = new ArrayList<>();
        List<LinkVerdict> verdicts = new ArrayList<>();
        Map<MatchDecision, Integer> decisions = new EnumMap<>(MatchDecision.class);
        Map<LinkVerdict.Outcome, Integer> outcomes = new EnumMap<>(LinkVerdict.Outcome.class);

        int pairsCompared = 0;
        for (int i = 0; i < records.size(); i++) {
            for (int j = i + 1; j < records.size(); j++) {
                SourceRecord left = records.get(i);
                SourceRecord right = records.get(j);

                ScoredPair pair = scorer.score(schema, left, right);
                pairsCompared++;
                scored.add(pair);
                decisions.merge(pair.decision(), 1, Integer::sum);

                if (pair.decision() != MatchDecision.MATCH) {
                    continue;
                }

                if (!applyPolicy) {
                    clusters.union(left.id(), right.id());
                    continue;
                }

                LinkVerdict verdict = policy.evaluate(pair, clusters);
                verdicts.add(verdict);
                outcomes.merge(verdict.outcome(), 1, Integer::sum);
                if (verdict.allowed()) {
                    clusters.union(left.id(), right.id());
                }
            }
        }

        long allPairs = (long) records.size() * (records.size() - 1) / 2;
        return new Result(clusters, List.copyOf(scored), List.copyOf(verdicts), Map.copyOf(decisions),
                Map.copyOf(outcomes), pairsCompared, allPairs - pairsCompared);
    }
}
