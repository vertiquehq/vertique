// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.db.resource;

import dev.vertique.db.exception.UniqueConstraintViolationException;
import dev.vertique.rest.core.ProblemDetail;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

/**
 * Maps {@link UniqueConstraintViolationException} to HTTP 409 Conflict.
 *
 * <p>Returned when a database unique constraint is violated, for example when attempting to
 * create an item with a name that already exists.
 */
public class UniqueConstraintExceptionMapper implements ExceptionMapper<UniqueConstraintViolationException> {

    /**
     * Creates a new {@code UniqueConstraintExceptionMapper}.
     */
    @Inject
    public UniqueConstraintExceptionMapper() {}

    /**
     * Converts a {@link UniqueConstraintViolationException} into a 409 Conflict response.
     *
     * @param exception the unique constraint violation exception
     * @return a 409 Conflict response with RFC 9457 problem detail body
     */
    @Override
    public Response toResponse(UniqueConstraintViolationException exception) {
        return Response.status(409)
                .entity(ProblemDetail.of(409, exception.getMessage()))
                .type("application/problem+json")
                .build();
    }
}
