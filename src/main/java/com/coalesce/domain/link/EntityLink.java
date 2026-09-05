package com.coalesce.domain.link;

import com.coalesce.domain.match.CandidatePair;
import com.coalesce.domain.match.MatchDecision;
import com.coalesce.domain.match.ScoredPair;
import com.coalesce.domain.model.RecordId;
import com.coalesce.domain.schema.ResolutionSchema;
import java.time.Instant;
import java.util.Objects;

/**
 * One pairwise assertion about two records, stored as a first-class fact.
 *
 * <h2>Why links are stored and clusters are derived, rather than the reverse</h2>
 *
 * <p>The tempting design is to persist the cluster assignment — a {@code cluster_id} column on each
 * record. It is smaller, it makes "which cluster is this in" a single indexed read, and it is
 * unrecoverable. A cluster produced by transitive closure is a <em>conclusion</em>; the links are the
 * premises. Once only the conclusion is stored, no operation can undo part of it: removing one record
 * from a cluster cannot know whether the rest still belong together, because the evidence that put them
 * there is gone. Every merge becomes permanent, which in a system deciding whether two people are the
 * same person is not an acceptable property.
 *
 * <p>Storing links keeps clusters recomputable from evidence, which is what makes {@code
 * LinkLedger.unmerge} possible at all. The cost is a join and a graph traversal to answer "which
 * cluster", and a cluster table that is a cache rather than the truth.
 *
 * <h2>Why the schema version is on the link</h2>
 *
 * <p>A link is only explicable against the rules that produced it. Six months and two threshold changes
 * later, re-scoring an old pair against today's schema and finding it below threshold says nothing
 * about whether the original decision was correct. The version tag turns "why is this merged" into a
 * question with an answer.
 *
 * @param pair          the two records, canonically ordered so one pair has one identity
 * @param score         the score that produced the decision; 1.0 for a manual merge and 0.0 for a
 *                      manual separation, since a human assertion is not the output of a scorer
 * @param decision      {@code MATCH} joins, {@code NO_MATCH} from a human separates, {@code REVIEW}
 *                      records an unresolved pair without joining anything
 * @param schemaName    schema in force when this was decided
 * @param schemaVersion version of that schema, so the decision stays attributable across rule changes
 * @param recordedAt    when the assertion was made
 * @param origin        engine or human; see {@link LinkOrigin} for why this governs precedence
 */
public record EntityLink(
        CandidatePair pair,
        double score,
        MatchDecision decision,
        String schemaName,
        int schemaVersion,
        Instant recordedAt,
        LinkOrigin origin) {

    public EntityLink {
        Objects.requireNonNull(pair, "pair");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(schemaName, "schemaName");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(origin, "origin");
        if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be within [0,1], was " + score);
        }
        if (origin == LinkOrigin.MANUAL_OVERRIDE && decision == MatchDecision.REVIEW) {
            // A human who has looked at a pair has an answer. Persisting "a steward decided this needs
            // reviewing" would create a link that neither merges nor separates nor can be cleared,
            // which is a queue entry masquerading as a decision.
            throw new IllegalArgumentException("a manual override must resolve to MATCH or NO_MATCH");
        }
    }

    public static EntityLink automatic(ScoredPair scored, ResolutionSchema schema, Instant at) {
        return new EntityLink(scored.pair(), scored.score(), scored.decision(), schema.name(),
                schema.version(), at, LinkOrigin.AUTOMATIC);
    }

    /** A steward asserting two records are the same entity, at full confidence by definition. */
    public static EntityLink manualMerge(RecordId a, RecordId b, ResolutionSchema schema, Instant at) {
        return new EntityLink(CandidatePair.of(a, b), 1.0, MatchDecision.MATCH, schema.name(),
                schema.version(), at, LinkOrigin.MANUAL_OVERRIDE);
    }

    /** A steward asserting two records are different entities. The assertion the engine must never undo. */
    public static EntityLink manualSeparation(RecordId a, RecordId b, ResolutionSchema schema, Instant at) {
        return new EntityLink(CandidatePair.of(a, b), 0.0, MatchDecision.NO_MATCH, schema.name(),
                schema.version(), at, LinkOrigin.MANUAL_OVERRIDE);
    }

    public RecordId left() {
        return pair.left();
    }

    public RecordId right() {
        return pair.right();
    }

    /** Whether this link joins its two records into one cluster. */
    public boolean joins() {
        return decision == MatchDecision.MATCH;
    }

    /** Whether this link is a durable human assertion that the two records are different entities. */
    public boolean separates() {
        return origin == LinkOrigin.MANUAL_OVERRIDE && decision == MatchDecision.NO_MATCH;
    }

    public boolean isManual() {
        return origin == LinkOrigin.MANUAL_OVERRIDE;
    }

    public String schemaVersionTag() {
        return schemaName + ":v" + schemaVersion;
    }
}
