// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.db.resource;

import dev.vertique.db.exception.ConnectionException;
import dev.vertique.rest.core.ProblemDetail;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

/**
 * Maps {@link ConnectionException} to HTTP 503 Service Unavailable.
 *
 * <p>Returned when the application cannot establish a connection to the database, indicating
 * a transient infrastructure failure. Clients should retry the request after a short delay.
 */
public class ConnectionExceptionMapper implements ExceptionMapper<ConnectionException> {

    /**
     * Creates a new {@code ConnectionExceptionMapper}.
     */
    @Inject
    public ConnectionExceptionMapper() {}

    /**
     * Converts a {@link ConnectionException} into a 503 Service Unavailable response.
     *
     * @param exception the connection exception
     * @return a 503 Service Unavailable response with RFC 9457 problem detail body
     */
    @Override
    public Response toResponse(ConnectionException exception) {
        return Response.status(503)
                .entity(ProblemDetail.of(503, "Database temporarily unavailable"))
                .type("application/problem+json")
                .build();
    }
}
