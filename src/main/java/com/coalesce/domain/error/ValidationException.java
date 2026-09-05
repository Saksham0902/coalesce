package com.coalesce.domain.error;

/**
 * Caller-supplied data the domain refuses: a malformed record id, a schema with contradictory
 * thresholds, an ingest batch with duplicate ids.
 *
 * <p>Maps to 422 rather than 400 because the request itself parsed correctly — the JSON was valid and
 * the fields were the right types. What failed was a domain rule, and the distinction is worth keeping
 * for anyone reading access logs to work out whether a client is malfunctioning or merely sending data
 * the engine will not accept.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
