package com.coalesce.domain.error;

/**
 * A record, cluster, schema or link the caller named does not exist.
 *
 * <p>Defined in the domain rather than as a Spring exception so that the packages that throw it stay
 * framework-free; the API layer owns the translation to a status code. Unchecked because a missing
 * entity is almost never recoverable at the call site — the handler that turns it into a 404 is the
 * only code with anything useful to do about it.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
