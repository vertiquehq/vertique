// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.ProblemDetail;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
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
        @DisplayName("Fallback activates: 500 response + VERTX_STATUS_CODE_KEY=401 produces 401")
        void fallbackActivatesFor401() {
            ctxData.put(RequestInterceptor.VERTX_STATUS_CODE_KEY, 401);

            Future<Response> future = pipeline.mapToResponse(ctx, new RuntimeException("auth failed"));
            assertTrue(future.succeeded());

            Response response = future.result();
            assertEquals(401, response.getStatus());
            assertInstanceOf(ProblemDetail.class, response.getEntity());
            assertEquals(401, ((ProblemDetail) response.getEntity()).status());
        }

        @Test
        @DisplayName("Fallback activates: 500 response + VERTX_STATUS_CODE_KEY=403 produces 403")
        void fallbackActivatesFor403() {
            ctxData.put(RequestInterceptor.VERTX_STATUS_CODE_KEY, 403);

            Future<Response> future = pipeline.mapToResponse(ctx, new RuntimeException("forbidden"));
            assertTrue(future.succeeded());

            Response response = future.result();
            assertEquals(403, response.getStatus());
        }

        @Test
        @DisplayName("Fallback does NOT activate when no VERTX_STATUS_CODE_KEY is present")
        void noFallbackWithoutKey() {
            // No VERTX_STATUS_CODE_KEY in ctxData

            Future<Response> future = pipeline.mapToResponse(ctx, new RuntimeException("server error"));
            assertTrue(future.succeeded());

            Response response = future.result();
            assertEquals(500, response.getStatus());
        }

        @Test
        @DisplayName("Fallback does NOT activate when stored status is 500")
        void noFallbackWhenStatusIs500() {
            ctxData.put(RequestInterceptor.VERTX_STATUS_CODE_KEY, 500);

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

            ctxData.put(RequestInterceptor.VERTX_STATUS_CODE_KEY, 401);

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

            ctxData.put(RequestInterceptor.VERTX_STATUS_CODE_KEY, 401);

            Future<Response> future = customPipeline.mapToResponse(ctx, new IllegalArgumentException("bad token"));
            assertTrue(future.succeeded());

            Response response = future.result();
            assertEquals(401, response.getStatus()); // Vert.x fallback overrides default 400
        }

        @Test
        @DisplayName("ProblemDetail instance field is populated from request path")
        void problemDetailInstanceIsPopulated() {
            ctxData.put(RequestInterceptor.VERTX_STATUS_CODE_KEY, 401);

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

            ctxData.put(RequestInterceptor.VERTX_STATUS_CODE_KEY, 403);

            Future<Response> future = customPipeline.mapToResponse(ctx, new RuntimeException("forbidden"));
            assertTrue(future.succeeded());

            Response response = future.result();
            assertEquals(403, response.getStatus());
            assertEquals("plain error", response.getEntity()); // Entity unchanged
        }
    }
}
