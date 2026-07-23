// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

/**
 * JAX-RS {@link ExceptionMapper} that converts a {@link GreetingLimitExceededException}
 * into a 429 Too Many Requests response with a {@link GreetingLimitProblemDetail} body.
 *
 * <p>This mapper is registered via Dagger multibinding into {@code Set<ExceptionMapper<?>>}
 * in {@link ResourceModule}, where it is contributed to the framework's
 * {@link dev.vertique.rest.jaxrs.ExceptionMapperRegistry}.
 */
public class GreetingLimitExceptionMapper implements ExceptionMapper<GreetingLimitExceededException> {

    /**
     * Constructs a new {@code GreetingLimitExceptionMapper}. Called by Dagger.
     */
    @Inject
    public GreetingLimitExceptionMapper() {}

    /**
     * Maps the given {@link GreetingLimitExceededException} to a 429 Too Many Requests
     * {@link Response} containing a {@link GreetingLimitProblemDetail} body.
     *
     * @param ex the exception to map
     * @return a 429 response with an {@code application/problem+json} body
     */
    @Override
    public Response toResponse(GreetingLimitExceededException ex) {
        return Response.status(429)
                .entity(GreetingLimitProblemDetail.of(ex.limit()))
                .type("application/problem+json")
                .build();
    }
}
