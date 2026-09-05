package com.coalesce.domain.port;

import com.coalesce.domain.model.RecordId;
import com.coalesce.domain.model.SourceRecord;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for ingested records, as the domain needs it rather than as a database offers it.
 *
 * <p>Declared here and implemented in {@code infrastructure} so the dependency points inwards: the
 * resolution logic is compiled and tested without JPA, Hibernate or a driver on the path, and an
 * in-memory implementation in a test is a class with six short methods. The alternative — the
 * application layer depending on a Spring Data interface directly — would put entity mapping
 * annotations and a lazy-loading model in the middle of the matching code, and would make every
 * algorithm test a Spring context test.
 *
 * <p>Deliberately narrow. There is no paging, no query-by-attribute and no criteria API, because the
 * only access patterns resolution actually has are "load the corpus for a batch", "look one up to
 * explain a decision", and "count". Anything richer would be speculative surface that the adapter has
 * to implement and nothing calls.
 */
public interface RecordRepository {

    /** Upserts by {@link RecordId}: re-ingesting a record replaces its attributes. */
    void saveAll(Collection<SourceRecord> records);

    Optional<SourceRecord> find(RecordId id);

    /**
     * The whole corpus.
     *
     * <p>Honest limitation: batch resolution loads every record into memory, so the ceiling is heap.
     * Blocking is what makes the comparison count manageable, not the ingest. Streaming resolution
     * would need this to return a cursor and blocking to be reformulated as a keyed shuffle — see the
     * scaling section of INTERVIEW.md.
     */
    List<SourceRecord> findAll();

    long count();
}
