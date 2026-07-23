// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.rest.core.ProblemDetail;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultExceptionMapper}.
 *
 * <p>Verifies handler registration, hierarchy walking, most-specific-match
 * semantics, the 500 fallback, fluent chaining, and lookup caching.
 */
class DefaultExceptionMapperTest {

    @Test
    @DisplayName("Should use the registered handler for a directly registered exception type")
    void shouldUseRegisteredHandler() {
        DefaultExceptionMapper mapper = new DefaultExceptionMapper()
                .on(IllegalArgumentException.class, ex -> Response.status(400)
                        .entity(ProblemDetail.of(400, ex.getMessage()))
                        .type("application/problem+json")
                        .build());

        Response response = mapper.toResponse(new IllegalArgumentException("bad value"));

        assertEquals(400, response.getStatus());
        assertInstanceOf(ProblemDetail.class, response.getEntity());
        assertEquals("bad value", ((ProblemDetail) response.getEntity()).detail());
    }

    @Test
    @DisplayName("Should walk the hierarchy and use a superclass handler when no exact match exists")
    void shouldWalkHierarchy() {
        DefaultExceptionMapper mapper = new DefaultExceptionMapper()
                .on(RuntimeException.class, ex -> Response.status(503)
                        .entity(ProblemDetail.of(503, "re: " + ex.getMessage()))
                        .build());

        // IllegalArgumentException extends RuntimeException — should use the RE handler
        Response response = mapper.toResponse(new IllegalArgumentException("sub-type"));

        assertEquals(503, response.getStatus());
        assertInstanceOf(ProblemDetail.class, response.getEntity());
        assertEquals("re: sub-type", ((ProblemDetail) response.getEntity()).detail());
    }

    @Test
    @DisplayName("Should use the most specific handler when both a superclass and a subclass are registered")
    void shouldUseMostSpecificHandler() {
        DefaultExceptionMapper mapper = new DefaultExceptionMapper()
                .on(RuntimeException.class, ex -> Response.status(503)
                        .entity(ProblemDetail.of(503, "re: " + ex.getMessage()))
                        .build())
                .on(IllegalArgumentException.class, ex -> Response.status(400)
                        .entity(ProblemDetail.of(400, "iae: " + ex.getMessage()))
                        .build());

        Response response = mapper.toResponse(new IllegalArgumentException("specific"));

        assertEquals(400, response.getStatus());
        assertInstanceOf(ProblemDetail.class, response.getEntity());
        assertEquals("iae: specific", ((ProblemDetail) response.getEntity()).detail());
    }

    @Test
    @DisplayName("Should fallback to 500 ProblemDetail with sanitized message when no handler is registered")
    void shouldFallbackTo500WhenNoHandler() {
        DefaultExceptionMapper mapper = new DefaultExceptionMapper();

        Response response = mapper.toResponse(new RuntimeException("unhandled"));

        assertEquals(500, response.getStatus());
        assertInstanceOf(ProblemDetail.class, response.getEntity());
        ProblemDetail detail = (ProblemDetail) response.getEntity();
        assertEquals(500, detail.status());
        assertEquals("Internal Server Error", detail.detail());
    }

    @Test
    @DisplayName("Should map WebApplicationException subclass to ProblemDetail with correct status")
    void shouldMapWebApplicationExceptionToProblemDetail() {
        DefaultExceptionMapper mapper = new DefaultExceptionMapper()
                .on(WebApplicationException.class, ex -> Response.status(
                                ex.getResponse().getStatus())
                        .entity(ProblemDetail.of(ex.getResponse().getStatus(), ex.getMessage()))
                        .type("application/problem+json")
                        .build());

        Response response = mapper.toResponse(new NotFoundException("not found"));

        assertEquals(404, response.getStatus());
        assertInstanceOf(ProblemDetail.class, response.getEntity());
        ProblemDetail detail = (ProblemDetail) response.getEntity();
        assertEquals(404, detail.status());
        assertEquals("Not Found", detail.title());
        assertEquals("not found", detail.detail());
    }

    @Test
    @DisplayName("Should preserve existing response entity for WebApplicationException")
    void shouldPreserveExistingResponseEntityForWebApplicationException() {
        DefaultExceptionMapper mapper = new DefaultExceptionMapper().on(WebApplicationException.class, ex -> {
            Response original = ex.getResponse();
            if (original.getEntity() != null) {
                return original;
            }
            return Response.status(original.getStatus())
                    .entity(ProblemDetail.of(original.getStatus(), ex.getMessage()))
                    .type("application/problem+json")
                    .build();
        });

        String customEntity = "custom error body";
        Response customResponse = Response.status(409)
                .entity(customEntity)
                .type("application/json")
                .build();
        WebApplicationException ex = new WebApplicationException(customResponse);

        Response response = mapper.toResponse(ex);

        assertEquals(409, response.getStatus());
        assertEquals(customEntity, response.getEntity());
    }

    @Test
    @DisplayName("Should return the same instance from on() for fluent chaining")
    void shouldReturnThisForFluentChaining() {
        DefaultExceptionMapper mapper = new DefaultExceptionMapper();

        DefaultExceptionMapper returned =
                mapper.on(RuntimeException.class, ex -> Response.status(500).build());

        assertSame(mapper, returned);
    }

    @Test
    @DisplayName("Should cache handler lookups: calling toResponse() twice with the same type both work correctly")
    void shouldCacheHandlerLookups() {
        DefaultExceptionMapper mapper = new DefaultExceptionMapper()
                .on(IllegalArgumentException.class, ex -> Response.status(400)
                        .entity(ProblemDetail.of(400, ex.getMessage()))
                        .build());

        // First call — populates cache
        Response first = mapper.toResponse(new IllegalArgumentException("first call"));
        assertEquals(400, first.getStatus());
        assertEquals("first call", ((ProblemDetail) first.getEntity()).detail());

        // Second call — uses cached handler but processes the new exception
        Response second = mapper.toResponse(new IllegalArgumentException("second call"));
        assertEquals(400, second.getStatus());
        assertEquals("second call", ((ProblemDetail) second.getEntity()).detail());
    }
}
