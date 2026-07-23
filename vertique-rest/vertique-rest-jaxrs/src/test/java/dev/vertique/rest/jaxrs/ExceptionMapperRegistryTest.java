// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.rest.core.ProblemDetail;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExceptionMapperRegistry}.
 *
 * <p>Verifies hierarchy-aware exception mapping, user mapper precedence over
 * framework defaults, caching behaviour, and runtime registration.
 */
class ExceptionMapperRegistryTest {

    private DefaultExceptionMapper defaults;
    private ExceptionMapperRegistry registry;

    @BeforeEach
    void setUp() {
        defaults = new DefaultExceptionMapper()
                .on(WebApplicationException.class, ex -> {
                    Response original = ex.getResponse();
                    if (original.getEntity() != null) {
                        return original;
                    }
                    return Response.status(original.getStatus())
                            .entity(ProblemDetail.of(original.getStatus(), ex.getMessage()))
                            .type("application/problem+json")
                            .build();
                })
                .on(IllegalArgumentException.class, ex -> Response.status(400)
                        .entity(ProblemDetail.of(400, ex.getMessage()))
                        .type("application/problem+json")
                        .build())
                .on(Throwable.class, ex -> Response.status(500)
                        .entity(ProblemDetail.of(500, "Internal Server Error"))
                        .type("application/problem+json")
                        .build());
        registry = new ExceptionMapperRegistry(defaults, Set.of());
    }

    // --- Test helper classes ---

    /**
     * User-contributed mapper for {@code IllegalArgumentException} that returns 422
     * so tests can distinguish it from the framework default (400).
     */
    static class UserIllegalArgumentMapper implements ExceptionMapper<IllegalArgumentException> {
        @Override
        public Response toResponse(IllegalArgumentException exception) {
            return Response.status(422)
                    .entity(ProblemDetail.of(422, "user: " + exception.getMessage()))
                    .type("application/problem+json")
                    .build();
        }
    }

    /** User-contributed mapper for {@code NullPointerException}. */
    static class UserNullPointerMapper implements ExceptionMapper<NullPointerException> {
        @Override
        public Response toResponse(NullPointerException exception) {
            return Response.status(400)
                    .entity(ProblemDetail.of(400, "npe: " + exception.getMessage()))
                    .type("application/problem+json")
                    .build();
        }
    }

    // --- Default mapper tests ---

    @Test
    @DisplayName("Should map WebApplicationException to ProblemDetail response")
    void shouldMapWebApplicationException() {
        WebApplicationException ex = new WebApplicationException("forbidden", 403);
        Response response = registry.toResponse(ex);
        assertEquals(403, response.getStatus());
        assertInstanceOf(ProblemDetail.class, response.getEntity());
        ProblemDetail detail = (ProblemDetail) response.getEntity();
        assertEquals(403, detail.status());
        assertEquals("forbidden", detail.detail());
    }

    @Test
    @DisplayName("Should map IllegalArgumentException to 400 using registered mapper")
    void shouldMapIllegalArgumentExceptionTo400() {
        IllegalArgumentException ex = new IllegalArgumentException("invalid input");
        Response response = registry.toResponse(ex);
        assertEquals(400, response.getStatus());
        assertInstanceOf(ProblemDetail.class, response.getEntity());
        ProblemDetail detail = (ProblemDetail) response.getEntity();
        assertEquals(400, detail.status());
        assertEquals("invalid input", detail.detail());
    }

    @Test
    @DisplayName("Should map Throwable catch-all to 500 with sanitized message")
    void shouldMapThrowableCatchAllTo500() {
        RuntimeException ex = new RuntimeException("unexpected failure");
        Response response = registry.toResponse(ex);
        assertEquals(500, response.getStatus());
        assertInstanceOf(ProblemDetail.class, response.getEntity());
        ProblemDetail detail = (ProblemDetail) response.getEntity();
        assertEquals(500, detail.status());
        assertEquals("Internal Server Error", detail.detail());
    }

    @Test
    @DisplayName("Should walk superclass hierarchy (NumberFormatException -> IllegalArgumentException mapper)")
    void shouldWalkSuperclassHierarchy() {
        NumberFormatException ex = new NumberFormatException("not a number");
        Response response = registry.toResponse(ex);
        assertEquals(400, response.getStatus());
        assertInstanceOf(ProblemDetail.class, response.getEntity());
        ProblemDetail detail = (ProblemDetail) response.getEntity();
        assertEquals(400, detail.status());
        assertEquals("not a number", detail.detail());
    }

    @Test
    @DisplayName(
            "Should use most specific mapper when both RuntimeException and IllegalArgumentException are registered")
    void shouldUseMostSpecificMapper() {
        DefaultExceptionMapper twoLevelDefaults = new DefaultExceptionMapper()
                .on(RuntimeException.class, ex -> Response.status(503)
                        .entity(ProblemDetail.of(503, "runtime: " + ex.getMessage()))
                        .build())
                .on(IllegalArgumentException.class, ex -> Response.status(400)
                        .entity(ProblemDetail.of(400, "iae: " + ex.getMessage()))
                        .build());
        ExceptionMapperRegistry twoLevelRegistry = new ExceptionMapperRegistry(twoLevelDefaults, Set.of());

        IllegalArgumentException ex = new IllegalArgumentException("bad value");
        Response response = twoLevelRegistry.toResponse(ex);
        assertEquals(400, response.getStatus());
        ProblemDetail detail = (ProblemDetail) response.getEntity();
        assertEquals("iae: bad value", detail.detail());
    }

    @Test
    @DisplayName("Should fallback to 500 when DefaultExceptionMapper has no registrations and mapper set is empty")
    void shouldFallbackTo500WithEmptyRegistry() {
        ExceptionMapperRegistry emptyRegistry = new ExceptionMapperRegistry(new DefaultExceptionMapper(), Set.of());
        RuntimeException ex = new RuntimeException("no mapper");
        Response response = emptyRegistry.toResponse(ex);
        assertEquals(500, response.getStatus());
    }

    @Test
    @DisplayName("Should support runtime registration via register()")
    void shouldSupportRuntimeRegistration() {
        registry.register(UnsupportedOperationException.class, ex -> Response.status(501)
                .entity(ProblemDetail.of(501, ex.getMessage()))
                .build());

        UnsupportedOperationException ex = new UnsupportedOperationException("not implemented");
        Response response = registry.toResponse(ex);
        assertEquals(501, response.getStatus());
        assertInstanceOf(ProblemDetail.class, response.getEntity());
        ProblemDetail detail = (ProblemDetail) response.getEntity();
        assertEquals("not implemented", detail.detail());
    }

    @Test
    @DisplayName("Should cache mapper lookups (call twice with same exception type)")
    void shouldCacheMapperLookups() {
        NumberFormatException ex1 = new NumberFormatException("first");
        Response response1 = registry.toResponse(ex1);
        assertEquals(400, response1.getStatus());

        NumberFormatException ex2 = new NumberFormatException("second");
        Response response2 = registry.toResponse(ex2);
        assertEquals(400, response2.getStatus());
        assertInstanceOf(ProblemDetail.class, response2.getEntity());
        assertEquals("second", ((ProblemDetail) response2.getEntity()).detail());
    }

    @Test
    @DisplayName("Should handle NotFoundException (404 subclass of WebApplicationException)")
    void shouldHandleNotFoundException() {
        NotFoundException ex = new NotFoundException("item not found");
        Response response = registry.toResponse(ex);
        assertEquals(404, response.getStatus());
        assertInstanceOf(ProblemDetail.class, response.getEntity());
        ProblemDetail detail = (ProblemDetail) response.getEntity();
        assertEquals(404, detail.status());
        assertEquals("item not found", detail.detail());
    }

    @Test
    @DisplayName("Should preserve existing entity from WebApplicationException response")
    void shouldPreserveExistingEntityFromWebApplicationException() {
        String customEntity = "custom error body";
        Response customResponse = Response.status(409)
                .entity(customEntity)
                .type("application/json")
                .build();
        WebApplicationException ex = new WebApplicationException(customResponse);

        Response response = registry.toResponse(ex);

        assertEquals(409, response.getStatus());
        assertEquals(customEntity, response.getEntity());
    }

    // --- User mapper precedence tests ---

    @Test
    @DisplayName("User mapper wins over DefaultExceptionMapper for the same exception type")
    void userMapperWinsOverDefaults() {
        UserIllegalArgumentMapper userMapper = new UserIllegalArgumentMapper();
        ExceptionMapperRegistry registryWithUser = new ExceptionMapperRegistry(defaults, Set.of(userMapper));

        IllegalArgumentException ex = new IllegalArgumentException("conflict");
        Response response = registryWithUser.toResponse(ex);

        assertEquals(422, response.getStatus());
        assertInstanceOf(ProblemDetail.class, response.getEntity());
        assertEquals("user: conflict", ((ProblemDetail) response.getEntity()).detail());
    }

    @Test
    @DisplayName("DefaultExceptionMapper and user mapper coexist for different exception types")
    void bothSourcesCoexistForDifferentTypes() {
        UserNullPointerMapper userMapper = new UserNullPointerMapper();
        ExceptionMapperRegistry mixedRegistry = new ExceptionMapperRegistry(defaults, Set.of(userMapper));

        // DefaultExceptionMapper handles IAE -> 400
        IllegalArgumentException iae = new IllegalArgumentException("bad input");
        Response iaeResponse = mixedRegistry.toResponse(iae);
        assertEquals(400, iaeResponse.getStatus());
        assertEquals("bad input", ((ProblemDetail) iaeResponse.getEntity()).detail());

        // User mapper handles NPE -> 400 with "npe: " prefix
        NullPointerException npe = new NullPointerException("null ref");
        Response npeResponse = mixedRegistry.toResponse(npe);
        assertEquals(400, npeResponse.getStatus());
        assertEquals("npe: null ref", ((ProblemDetail) npeResponse.getEntity()).detail());
    }

    @Test
    @DisplayName("Empty user mapper set: DefaultExceptionMapper handles all exceptions")
    void emptyMappersSetUsesDefaults() {
        ExceptionMapperRegistry registryWithEmptyUser = new ExceptionMapperRegistry(defaults, Set.of());

        IllegalArgumentException ex = new IllegalArgumentException("same as defaults");
        Response response = registryWithEmptyUser.toResponse(ex);

        assertEquals(400, response.getStatus());
        assertInstanceOf(ProblemDetail.class, response.getEntity());
        assertEquals("same as defaults", ((ProblemDetail) response.getEntity()).detail());
    }

    @Test
    @DisplayName("Should find custom mapper when original exception type is passed directly (not wrapped)")
    void shouldFindCustomMapperForUnwrappedException() {
        // Register a mapper for a custom exception type
        ExceptionMapper<SecurityException> securityMapper = ex -> Response.status(403)
                .entity(ProblemDetail.of(403, "security: " + ex.getMessage()))
                .type("application/problem+json")
                .build();
        registry.register(SecurityException.class, securityMapper);

        // Pass the original exception directly (not wrapped in WebApplicationException)
        SecurityException ex = new SecurityException("access denied");
        Response response = registry.toResponse(ex);

        assertEquals(403, response.getStatus());
        assertInstanceOf(ProblemDetail.class, response.getEntity());
        assertEquals("security: access denied", ((ProblemDetail) response.getEntity()).detail());
    }
}
