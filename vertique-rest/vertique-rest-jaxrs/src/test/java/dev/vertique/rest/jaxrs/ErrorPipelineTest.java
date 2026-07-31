// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.ProblemDetail;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.ValidationProblemDetail;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ErrorPipeline}.
 *
 * <p>Verifies the Vert.x status code fallback mechanism that preserves HTTP status codes
 * from unwrapped {@code HttpException} causes when no specific {@code ExceptionMapper} matches.
 */
class ErrorPipelineTest {

    private RoutingContext ctx;
    private HttpServerRequest request;
    private Map<String, Object> ctxData;
    private ErrorPipeline pipeline;

    @BeforeEach
    void setUp() {
        ctx = mock(RoutingContext.class);
        request = mock(HttpServerRequest.class);
        ctxData = new HashMap<>();
        lenient().when(ctx.data()).thenReturn(ctxData);
        lenient().when(ctx.request()).thenReturn(request);
        lenient().when(request.path()).thenReturn("/test");

        DefaultExceptionMapper defaults = new DefaultExceptionMapper().on(Throwable.class, ex -> Response.status(500)
                .entity(ProblemDetail.of(500, "Internal Server Error"))
                .type("application/problem+json")
                .build());

        ExceptionMapperRegistry registry = new ExceptionMapperRegistry(defaults, Set.of());
        RestExceptionMapper failureMapper = new RestExceptionMapper();

        pipeline = new ErrorPipeline(List.of(), List.of(), failureMapper, registry);
    }

    @Nested
    @DisplayName("Vert.x status code fallback")
    class VertxStatusCodeFallback {

        @Test
        @DisplayName("Fallback activates: 500 response + Vert.x failure status 401 produces 401")
        void fallbackActivatesFor401() {
            ctxData.put(VertxFailureStatus.KEY, 401);

            Future<Response> future = pipeline.mapToResponse(ctx, new RuntimeException("auth failed"));
            assertTrue(future.succeeded());

            Response response = future.result();
            assertEquals(401, response.getStatus());
            assertInstanceOf(ProblemDetail.class, response.getEntity());
            assertEquals(401, ((ProblemDetail) response.getEntity()).status());
        }

        @Test
        @DisplayName("Fallback activates: 500 response + Vert.x failure status 403 produces 403")
        void fallbackActivatesFor403() {
            ctxData.put(VertxFailureStatus.KEY, 403);

            Future<Response> future = pipeline.mapToResponse(ctx, new RuntimeException("forbidden"));
            assertTrue(future.succeeded());

            Response response = future.result();
            assertEquals(403, response.getStatus());
        }

        @Test
        @DisplayName("Fallback does NOT activate when no Vert.x failure status is present")
        void noFallbackWithoutKey() {
            // No Vert.x failure status in ctxData

            Future<Response> future = pipeline.mapToResponse(ctx, new RuntimeException("server error"));
            assertTrue(future.succeeded());

            Response response = future.result();
            assertEquals(500, response.getStatus());
        }

        @Test
        @DisplayName("Fallback does NOT activate when stored status is 500")
        void noFallbackWhenStatusIs500() {
            ctxData.put(VertxFailureStatus.KEY, 500);

            Future<Response> future = pipeline.mapToResponse(ctx, new RuntimeException("server error"));
            assertTrue(future.succeeded());

            Response response = future.result();
            assertEquals(500, response.getStatus());
        }

        @Test
        @DisplayName("Fallback does NOT activate when user-contributed mapper matches the exception type")
        void noFallbackWhenUserMapperMatches() {
            // User-contributed mapper registered directly (simulates Dagger multibinding)
            DefaultExceptionMapper defaults = new DefaultExceptionMapper()
                    .on(Throwable.class, ex -> Response.status(500)
                            .entity(ProblemDetail.of(500, "Internal Server Error"))
                            .type("application/problem+json")
                            .build());
            ExceptionMapperRegistry registry = new ExceptionMapperRegistry(defaults, Set.of());
            registry.register(IllegalArgumentException.class, ex -> Response.status(422)
                    .entity(ProblemDetail.of(422, ex.getMessage()))
                    .type("application/problem+json")
                    .build());
            RestExceptionMapper failureMapper = new RestExceptionMapper();
            ErrorPipeline customPipeline = new ErrorPipeline(List.of(), List.of(), failureMapper, registry);

            ctxData.put(VertxFailureStatus.KEY, 401);

            Future<Response> future = customPipeline.mapToResponse(ctx, new IllegalArgumentException("bad"));
            assertTrue(future.succeeded());

            Response response = future.result();
            assertEquals(422, response.getStatus()); // User mapper wins, not the Vert.x fallback
        }

        @Test
        @DisplayName(
                "Fallback activates when default framework mapper handles exception (IAE → 400, but Vert.x intended 401)")
        void fallbackOverridesDefaultMapperStatus() {
            // DefaultExceptionMapper has IAE → 400 internally, but Vert.x intended 401
            DefaultExceptionMapper defaults = new DefaultExceptionMapper()
                    .on(IllegalArgumentException.class, ex -> Response.status(400)
                            .entity(ProblemDetail.of(400, ex.getMessage()))
                            .type("application/problem+json")
                            .build())
                    .on(Throwable.class, ex -> Response.status(500)
                            .entity(ProblemDetail.of(500, "Internal Server Error"))
                            .type("application/problem+json")
                            .build());
            ExceptionMapperRegistry registry = new ExceptionMapperRegistry(defaults, Set.of());
            RestExceptionMapper failureMapper = new RestExceptionMapper();
            ErrorPipeline customPipeline = new ErrorPipeline(List.of(), List.of(), failureMapper, registry);

            ctxData.put(VertxFailureStatus.KEY, 401);

            Future<Response> future = customPipeline.mapToResponse(ctx, new IllegalArgumentException("bad token"));
            assertTrue(future.succeeded());

            Response response = future.result();
            assertEquals(401, response.getStatus()); // Vert.x fallback overrides default 400
        }

        @Test
        @DisplayName("Fallback re-derives the title when it overrides the status (500 body + key=400 → Bad Request)")
        void fallbackRewritesTitleOnStatusOverride() {
            ctxData.put(VertxFailureStatus.KEY, 400);

            Future<Response> future = pipeline.mapToResponse(ctx, new RuntimeException("boom"));
            assertTrue(future.succeeded());

            ProblemDetail pd =
                    assertInstanceOf(ProblemDetail.class, future.result().getEntity());
            assertEquals(
                    "Bad Request",
                    pd.title(),
                    "an overridden status must not leave the catch-all's title contradicting it");
        }

        @Test
        @DisplayName("Fallback clears the detail when it overrides the status (500 body + key=400 → no detail)")
        void fallbackClearsDetailOnStatusOverride() {
            ctxData.put(VertxFailureStatus.KEY, 400);

            Future<Response> future = pipeline.mapToResponse(ctx, new RuntimeException("boom"));
            assertTrue(future.succeeded());

            ProblemDetail pd =
                    assertInstanceOf(ProblemDetail.class, future.result().getEntity());
            assertNull(pd.detail(), "a detail written for the superseded status must not survive the override");
        }

        @Test
        @DisplayName("Fallback clears an authored detail on status override and re-derives the title")
        void fallbackClearsAuthoredDetailOnStatusOverride() {
            // The mapper authors a detail from an arbitrary application exception's message — exactly
            // what ctx.fail(401, e) feeds in from a claims validator. It must not reach the client.
            DefaultExceptionMapper defaults = new DefaultExceptionMapper()
                    .on(Throwable.class, ex -> Response.status(400)
                            .entity(ProblemDetail.of(400, "tenant 4711 is not permitted"))
                            .type("application/problem+json")
                            .build());
            ExceptionMapperRegistry registry = new ExceptionMapperRegistry(defaults, Set.of());
            RestExceptionMapper failureMapper = new RestExceptionMapper();
            ErrorPipeline customPipeline = new ErrorPipeline(List.of(), List.of(), failureMapper, registry);

            ctxData.put(VertxFailureStatus.KEY, 401);

            Future<Response> future = customPipeline.mapToResponse(ctx, new RuntimeException("rejected"));
            assertTrue(future.succeeded());

            Response response = future.result();
            assertEquals(401, response.getStatus());
            ProblemDetail pd = assertInstanceOf(ProblemDetail.class, response.getEntity());
            assertNull(pd.detail(), "an authored detail must be cleared, not carried into the overridden status");
            assertEquals("Unauthorized", pd.title(), "the title must be re-derived from the overriding status");
        }

        @Test
        @DisplayName("Fallback drops subclass fields on status override (ValidationProblemDetail errors[])")
        void fallbackDropsSubclassFieldsOnStatusOverride() {
            // A claims validator running bean validation produces a ValidationProblemDetail at 400 whose
            // errors[] name claim paths and messages. The Vert.x 401 supersedes that status — the errors[]
            // describe the superseded 400 and must not survive alongside the cleared detail.
            DefaultExceptionMapper defaults = new DefaultExceptionMapper().on(Throwable.class, ex -> Response.status(
                            400)
                    .entity(ValidationProblemDetail.of(
                            "Request validation failed",
                            List.of(ValidationErrorDetail.of("/claims/tenant_id", "tenant 4711 is not permitted"))))
                    .type("application/problem+json")
                    .build());
            ExceptionMapperRegistry registry = new ExceptionMapperRegistry(defaults, Set.of());
            RestExceptionMapper failureMapper = new RestExceptionMapper();
            ErrorPipeline customPipeline = new ErrorPipeline(List.of(), List.of(), failureMapper, registry);

            ctxData.put(VertxFailureStatus.KEY, 401);

            Future<Response> future = customPipeline.mapToResponse(ctx, new RuntimeException("rejected"));
            assertTrue(future.succeeded());

            Response response = future.result();
            assertEquals(401, response.getStatus());
            ProblemDetail pd = assertInstanceOf(ProblemDetail.class, response.getEntity());
            assertEquals(401, pd.status());
            assertEquals("Unauthorized", pd.title(), "the title must be re-derived from the overriding status");
            assertNull(pd.detail(), "a detail written for the superseded status must not survive the override");
            assertEquals(
                    ProblemDetail.class,
                    pd.getClass(),
                    "the override must publish a plain ProblemDetail — subclass fields such as "
                            + "ValidationProblemDetail.errors[] describe the superseded status");
        }

        @Test
        @DisplayName("Hint does NOT downgrade a mapped 403 to the stored 401")
        void hintDoesNotDowngradeForbiddenToUnauthorized() {
            // The shape a JwtClaimsValidator produces: it throws ForbiddenException and the contributor
            // fails the context with ctx.fail(401, e), so the hint is 401 while the framework's own
            // semantic mapping answers 403. The authorization decision must survive — answering 401
            // would tell the client to refresh a token that cannot lift the denial.
            ExceptionMapperRegistry registry =
                    new ExceptionMapperRegistry(RestModule.defaultExceptionMapper(), Set.of());
            ErrorPipeline customPipeline = new ErrorPipeline(List.of(), List.of(), new RestExceptionMapper(), registry);

            ctxData.put(VertxFailureStatus.KEY, 401);

            Future<Response> future = customPipeline.mapToResponse(
                    ctx, new dev.vertique.core.exception.ForbiddenException("tenant mismatch"));
            assertTrue(future.succeeded());

            Response response = future.result();
            assertEquals(403, response.getStatus(), "a 401 hint must not downgrade an authorization denial");
            ProblemDetail pd = assertInstanceOf(ProblemDetail.class, response.getEntity());
            assertEquals(403, pd.status());
            assertEquals("Forbidden", pd.title(), "the body must stay the one the mapper authored for 403");
            assertEquals("tenant mismatch", pd.detail(), "the guard returns the response untouched, body intact");
        }

        @Test
        @DisplayName("ProblemDetail instance field is populated from request path")
        void problemDetailInstanceIsPopulated() {
            ctxData.put(VertxFailureStatus.KEY, 401);

            Future<Response> future = pipeline.mapToResponse(ctx, new RuntimeException("auth failed"));
            assertTrue(future.succeeded());

            Response response = future.result();
            assertInstanceOf(ProblemDetail.class, response.getEntity());
            ProblemDetail pd = (ProblemDetail) response.getEntity();
            assertEquals("/test", pd.instance());
        }

        @Test
        @DisplayName("Fallback overrides status for non-ProblemDetail entity without modifying entity")
        void fallbackWithNonProblemDetailEntity() {
            // Use a mapper that returns a plain string entity (not ProblemDetail)
            DefaultExceptionMapper defaults = new DefaultExceptionMapper()
                    .on(Throwable.class, ex -> Response.status(500)
                            .entity("plain error")
                            .type("text/plain")
                            .build());
            ExceptionMapperRegistry registry = new ExceptionMapperRegistry(defaults, Set.of());
            RestExceptionMapper failureMapper = new RestExceptionMapper();
            ErrorPipeline customPipeline = new ErrorPipeline(List.of(), List.of(), failureMapper, registry);

            ctxData.put(VertxFailureStatus.KEY, 403);

            Future<Response> future = customPipeline.mapToResponse(ctx, new RuntimeException("forbidden"));
            assertTrue(future.succeeded());

            Response response = future.result();
            assertEquals(403, response.getStatus());
            assertEquals("plain error", response.getEntity()); // Entity unchanged
        }
    }
}
