package com.coalesce.domain.error;

/**
 * The request is well-formed and the entities exist, but the current state cannot honour it.
 *
 * <p>Distinct from a validation failure because the caller did nothing wrong and retrying with
 * different input will not help — the canonical case is a steward asserting two records are different
 * entities while a chain of other links still connects them, which needs those links retracted before
 * the assertion can take effect on the clustering. Reporting that as a validation error would tell the
 * caller to fix their request; reporting it as success would silently leave the assertion unenforced.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
