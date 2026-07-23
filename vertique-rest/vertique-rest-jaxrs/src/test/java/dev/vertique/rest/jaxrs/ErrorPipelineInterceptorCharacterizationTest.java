// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.ProblemDetail;
import dev.vertique.rest.core.interceptor.ErrorInterceptor;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests for {@link ErrorPipeline}'s interceptor chains.
 *
 * <p>These tests pin the observable behavior of the three interceptor dispatch paths —
 * {@code beforeMapping} fold, {@code afterMapping} fold, and the {@code onError} sync
 * observer loop — so that the migration to {@link dev.vertique.core.async.Combinators} can be
 * verified GREEN-against-CURRENT before migration (step A) and GREEN-against-MIGRATED after
 * (step B) with no observable change.
 *
 * <p>Specifically characterized:
 * <ul>
 *   <li>FR-ERRPIPE-001: {@code beforeMapping} calls all interceptors in order, threading the
 *       transformed {@link Throwable}; on interceptor failure the chain continues with the previous
 *       throwable (not the failure), and the next interceptor still runs.</li>
 *   <li>FR-ERRPIPE-002: {@code afterMapping} calls all interceptors in order, threading the
 *       transformed {@link Response}; on interceptor failure the chain continues with the previous
 *       response, and the next interceptor still runs.</li>
 *   <li>FR-ERRPIPE-003: {@code onError} calls all {@link RequestInterceptor}s in order and swallows
 *       a thrown exception silently (no log), continuing with the next interceptor.</li>
 * </ul>
 */
class ErrorPipelineInterceptorCharacterizationTest {

    private RoutingContext ctx;
    private HttpServerRequest request;
    private Map<String, Object> ctxData;

    /** Standard catch-all registry mapping any {@code Throwable} to a 500 with a {@link ProblemDetail}. */
    private ExceptionMapperRegistry registry500;

    @BeforeEach
    void setUp() {
        ctx = mock(RoutingContext.class);
        request = mock(HttpServerRequest.class);
        ctxData = new HashMap<>();
        lenient().when(ctx.data()).thenReturn(ctxData);
        lenient().when(ctx.request()).thenReturn(request);
        lenient().when(request.path()).thenReturn("/test");

        DefaultExceptionMapper defaults = new DefaultExceptionMapper().on(Throwable.class, ex -> Response.status(500)
                .entity(ProblemDetail.of(500, ex.getMessage()))
                .type("application/problem+json")
                .build());
        registry500 = new ExceptionMapperRegistry(defaults, Set.of());
    }

    // --- FR-ERRPIPE-001: beforeMapping chain ---

    @Nested
    @DisplayName("FR-ERRPIPE-001: beforeMapping chain threads throwable in order and continues on interceptor failure")
    class BeforeMappingChain {

        @Test
        @DisplayName(
                "beforeMapping_singleInterceptor_transformsThrowable: single interceptor's replacement is used by mapper")
        void beforeMapping_singleInterceptor_transformsThrowable() {
            // Given a root cause and an interceptor that replaces it with a different exception
            RuntimeException root = new RuntimeException("original");
            IllegalStateException replacement = new IllegalStateException("replaced");

            ErrorInterceptor interceptor = new ErrorInterceptor() {
                @Override
                public Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
                    return Future.succeededFuture(replacement);
                }
            };

            ErrorPipeline pipeline =
                    new ErrorPipeline(List.of(interceptor), List.of(), new RestExceptionMapper(), registry500);

            // When the pipeline processes the root cause
            Future<Response> result = pipeline.mapToResponse(ctx, root);

            // Then the final response entity reflects the replaced exception message
            assertTrue(result.succeeded());
            ProblemDetail pd = (ProblemDetail) result.result().getEntity();
            // The mapper received the replaced throwable, not the original
            assertEquals("replaced", pd.detail());
        }

        @Test
        @DisplayName("beforeMapping_twoInterceptors_threadValueInOrder: second interceptor receives output of first")
        void beforeMapping_twoInterceptors_threadValueInOrder() {
            // Given two interceptors — first replaces root with stepA, second replaces stepA with stepB
            RuntimeException root = new RuntimeException("root");
            RuntimeException stepA = new RuntimeException("stepA");
            RuntimeException stepB = new RuntimeException("stepB");

            List<Throwable> receivedBySecond = new ArrayList<>();

            ErrorInterceptor first = new ErrorInterceptor() {
                @Override
                public Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
                    return Future.succeededFuture(stepA);
                }
            };
            ErrorInterceptor second = new ErrorInterceptor() {
                @Override
                public Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
                    receivedBySecond.add(throwable);
                    return Future.succeededFuture(stepB);
                }
            };

            ErrorPipeline pipeline =
                    new ErrorPipeline(List.of(first, second), List.of(), new RestExceptionMapper(), registry500);

            // When
            Future<Response> result = pipeline.mapToResponse(ctx, root);

            // Then the second interceptor received stepA (the output of the first, not the root)
            assertTrue(result.succeeded());
            assertEquals(1, receivedBySecond.size());
            assertSame(stepA, receivedBySecond.get(0));
        }

        @Test
        @DisplayName(
                "beforeMapping_interceptorFails_continuesWithPreviousThrowable: failed interceptor is recovered to current value")
        void beforeMapping_interceptorFails_continuesWithPreviousThrowable() {
            // Given: interceptor[0] succeeds and replaces the throwable with stepA;
            //        interceptor[1] FAILS (async failure);
            //        interceptor[2] succeeds and records what it receives.
            RuntimeException root = new RuntimeException("root");
            RuntimeException stepA = new RuntimeException("stepA");

            List<Throwable> receivedByThird = new ArrayList<>();

            ErrorInterceptor first = new ErrorInterceptor() {
                @Override
                public Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
                    return Future.succeededFuture(stepA);
                }
            };
            ErrorInterceptor failingMiddle = new ErrorInterceptor() {
                @Override
                public Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
                    return Future.failedFuture(new RuntimeException("interceptor meta-failure"));
                }
            };
            ErrorInterceptor third = new ErrorInterceptor() {
                @Override
                public Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
                    receivedByThird.add(throwable);
                    return Future.succeededFuture(throwable);
                }
            };

            ErrorPipeline pipeline = new ErrorPipeline(
                    List.of(first, failingMiddle, third), List.of(), new RestExceptionMapper(), registry500);

            // When
            Future<Response> result = pipeline.mapToResponse(ctx, root);

            // Then: the pipeline completes successfully (the failure was recovered)
            assertTrue(result.succeeded());
            // The third interceptor received stepA — the value AT THE FAILING STEP (the current
            // threaded value), NOT the meta-failure. This is the key continue-on-failure invariant.
            assertEquals(1, receivedByThird.size());
            assertSame(stepA, receivedByThird.get(0));
        }

        @Test
        @DisplayName(
                "beforeMapping_failingFirstInterceptor_continuesWithSeedThrowable: root passed unchanged after recover")
        void beforeMapping_failingFirstInterceptor_continuesWithSeedThrowable() {
            // Given: interceptor[0] FAILS; interceptor[1] records what it receives
            RuntimeException root = new RuntimeException("root");

            List<Throwable> receivedBySecond = new ArrayList<>();

            ErrorInterceptor failingFirst = new ErrorInterceptor() {
                @Override
                public Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
                    return Future.failedFuture(new RuntimeException("meta-failure"));
                }
            };
            ErrorInterceptor second = new ErrorInterceptor() {
                @Override
                public Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
                    receivedBySecond.add(throwable);
                    return Future.succeededFuture(throwable);
                }
            };

            ErrorPipeline pipeline =
                    new ErrorPipeline(List.of(failingFirst, second), List.of(), new RestExceptionMapper(), registry500);

            // When
            Future<Response> result = pipeline.mapToResponse(ctx, root);

            // Then: second interceptor received the root (the seed at the failing step),
            // not the meta-failure
            assertTrue(result.succeeded());
            assertEquals(1, receivedBySecond.size());
            assertSame(root, receivedBySecond.get(0));
        }

        @Test
        @DisplayName(
                "beforeMapping_synchronousThrow_continuesWithPreviousThrowable: a sync throw from beforeMapping is subject to the continue policy")
        void beforeMapping_synchronousThrow_continuesWithPreviousThrowable() {
            // Given: interceptor[0].beforeMapping THROWS SYNCHRONOUSLY (not a failed Future);
            //        interceptor[1] records the throwable it receives.
            // The continue-with-previous policy must treat the sync throw exactly like an async
            // failure: recover to the current (seed) throwable and still invoke interceptor[1].
            RuntimeException root = new RuntimeException("root");

            List<Throwable> receivedBySecond = new ArrayList<>();

            ErrorInterceptor throwingFirst = new ErrorInterceptor() {
                @Override
                public Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
                    throw new RuntimeException("sync explosion in beforeMapping");
                }
            };
            ErrorInterceptor second = new ErrorInterceptor() {
                @Override
                public Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
                    receivedBySecond.add(throwable);
                    return Future.succeededFuture(throwable);
                }
            };

            ErrorPipeline pipeline = new ErrorPipeline(
                    List.of(throwingFirst, second), List.of(), new RestExceptionMapper(), registry500);

            // When
            Future<Response> result = pipeline.mapToResponse(ctx, root);

            // Then: the fold did NOT abort — interceptor[1] still ran, receiving the previous/unchanged
            // root throwable, and the pipeline produced a normal mapped 500 response.
            assertTrue(result.succeeded());
            assertEquals(1, receivedBySecond.size());
            assertSame(root, receivedBySecond.get(0));
            assertEquals(500, result.result().getStatus());
        }

        @Test
        @DisplayName("beforeMapping_emptyInterceptors_completesWithSeedThrowable: pipeline handles empty list")
        void beforeMapping_emptyInterceptors_completesWithSeedThrowable() {
            RuntimeException root = new RuntimeException("root");
            ErrorPipeline pipeline = new ErrorPipeline(List.of(), List.of(), new RestExceptionMapper(), registry500);

            Future<Response> result = pipeline.mapToResponse(ctx, root);

            assertTrue(result.succeeded());
            assertEquals(500, result.result().getStatus());
        }
    }

    // --- FR-ERRPIPE-002: afterMapping chain ---

    @Nested
    @DisplayName("FR-ERRPIPE-002: afterMapping chain threads response in order and continues on interceptor failure")
    class AfterMappingChain {

        @Test
        @DisplayName(
                "afterMapping_singleInterceptor_transformsResponse: interceptor's replacement response is returned")
        void afterMapping_singleInterceptor_transformsResponse() {
            // Given an interceptor that replaces any response with a 201
            ErrorInterceptor interceptor = new ErrorInterceptor() {
                @Override
                public Future<Response> afterMapping(RoutingContext rc, Response response) {
                    return Future.succeededFuture(
                            Response.status(201).entity("intercepted").build());
                }
            };

            ErrorPipeline pipeline =
                    new ErrorPipeline(List.of(interceptor), List.of(), new RestExceptionMapper(), registry500);

            // When
            Future<Response> result = pipeline.mapToResponse(ctx, new RuntimeException("err"));

            // Then the final response is the interceptor's replacement
            assertTrue(result.succeeded());
            assertEquals(201, result.result().getStatus());
        }

        @Test
        @DisplayName("afterMapping_twoInterceptors_threadValueInOrder: second interceptor receives output of first")
        void afterMapping_twoInterceptors_threadValueInOrder() {
            // Given: first replaces with 201; second records what it received and returns 202
            List<Integer> receivedStatuses = new ArrayList<>();

            ErrorInterceptor first = new ErrorInterceptor() {
                @Override
                public Future<Response> afterMapping(RoutingContext rc, Response response) {
                    return Future.succeededFuture(Response.status(201).build());
                }
            };
            ErrorInterceptor second = new ErrorInterceptor() {
                @Override
                public Future<Response> afterMapping(RoutingContext rc, Response response) {
                    receivedStatuses.add(response.getStatus());
                    return Future.succeededFuture(Response.status(202).build());
                }
            };

            ErrorPipeline pipeline =
                    new ErrorPipeline(List.of(first, second), List.of(), new RestExceptionMapper(), registry500);

            // When
            Future<Response> result = pipeline.mapToResponse(ctx, new RuntimeException("err"));

            // Then: second received 201 (the output of first), final result is 202
            assertTrue(result.succeeded());
            assertEquals(1, receivedStatuses.size());
            assertEquals(201, receivedStatuses.get(0));
            assertEquals(202, result.result().getStatus());
        }

        @Test
        @DisplayName(
                "afterMapping_interceptorFails_continuesWithPreviousResponse: failed interceptor recovered to current response")
        void afterMapping_interceptorFails_continuesWithPreviousResponse() {
            // Given: first produces 201; second FAILS; third records what it receives
            List<Integer> receivedByThird = new ArrayList<>();

            ErrorInterceptor first = new ErrorInterceptor() {
                @Override
                public Future<Response> afterMapping(RoutingContext rc, Response response) {
                    return Future.succeededFuture(Response.status(201).build());
                }
            };
            ErrorInterceptor failingMiddle = new ErrorInterceptor() {
                @Override
                public Future<Response> afterMapping(RoutingContext rc, Response response) {
                    return Future.failedFuture(new RuntimeException("after-mapping meta-failure"));
                }
            };
            ErrorInterceptor third = new ErrorInterceptor() {
                @Override
                public Future<Response> afterMapping(RoutingContext rc, Response response) {
                    receivedByThird.add(response.getStatus());
                    return Future.succeededFuture(response);
                }
            };

            ErrorPipeline pipeline = new ErrorPipeline(
                    List.of(first, failingMiddle, third), List.of(), new RestExceptionMapper(), registry500);

            // When
            Future<Response> result = pipeline.mapToResponse(ctx, new RuntimeException("err"));

            // Then: the pipeline succeeds; third received 201 (the value AT the failing step,
            // not the meta-failure)
            assertTrue(result.succeeded());
            assertEquals(1, receivedByThird.size());
            assertEquals(201, receivedByThird.get(0));
        }

        @Test
        @DisplayName(
                "afterMapping_failingFirstInterceptor_continuesWithMapperResponse: pre-failure response passes through")
        void afterMapping_failingFirstInterceptor_continuesWithMapperResponse() {
            // Given: the mapper produces 500; interceptor[0] FAILS; interceptor[1] records what it gets
            List<Integer> receivedBySecond = new ArrayList<>();

            ErrorInterceptor failingFirst = new ErrorInterceptor() {
                @Override
                public Future<Response> afterMapping(RoutingContext rc, Response response) {
                    return Future.failedFuture(new RuntimeException("first fails"));
                }
            };
            ErrorInterceptor second = new ErrorInterceptor() {
                @Override
                public Future<Response> afterMapping(RoutingContext rc, Response response) {
                    receivedBySecond.add(response.getStatus());
                    return Future.succeededFuture(response);
                }
            };

            ErrorPipeline pipeline =
                    new ErrorPipeline(List.of(failingFirst, second), List.of(), new RestExceptionMapper(), registry500);

            // When
            Future<Response> result = pipeline.mapToResponse(ctx, new RuntimeException("err"));

            // Then: second received 500 (the mapper output, the seed at that step, not the meta-failure)
            assertTrue(result.succeeded());
            assertEquals(1, receivedBySecond.size());
            assertEquals(500, receivedBySecond.get(0));
        }

        @Test
        @DisplayName(
                "afterMapping_synchronousThrow_continuesWithPreviousResponse: a sync throw from afterMapping is subject to the continue policy")
        void afterMapping_synchronousThrow_continuesWithPreviousResponse() {
            // Given: the mapper produces 500; interceptor[0].afterMapping THROWS SYNCHRONOUSLY
            //        (not a failed Future); interceptor[1] records the response it receives.
            // The continue-with-previous policy must treat the sync throw like an async failure:
            // recover to the current response and still invoke interceptor[1], threading it unchanged.
            List<Integer> receivedBySecond = new ArrayList<>();

            ErrorInterceptor throwingFirst = new ErrorInterceptor() {
                @Override
                public Future<Response> afterMapping(RoutingContext rc, Response response) {
                    throw new RuntimeException("sync explosion in afterMapping");
                }
            };
            ErrorInterceptor second = new ErrorInterceptor() {
                @Override
                public Future<Response> afterMapping(RoutingContext rc, Response response) {
                    receivedBySecond.add(response.getStatus());
                    return Future.succeededFuture(response);
                }
            };

            ErrorPipeline pipeline = new ErrorPipeline(
                    List.of(throwingFirst, second), List.of(), new RestExceptionMapper(), registry500);

            // When
            Future<Response> result = pipeline.mapToResponse(ctx, new RuntimeException("err"));

            // Then: the fold did NOT abort — interceptor[1] still ran, receiving the previous/unchanged
            // 500 response, and the pipeline succeeded with that response threaded through.
            assertTrue(result.succeeded());
            assertEquals(1, receivedBySecond.size());
            assertEquals(500, receivedBySecond.get(0));
            assertEquals(500, result.result().getStatus());
        }
    }

    // --- FR-ERRPIPE-003: onError sync observer loop ---

    @Nested
    @DisplayName("FR-ERRPIPE-003: onError sync observer loop swallows exceptions silently and continues")
    class OnErrorObserverLoop {

        @Test
        @DisplayName("onError_singleInterceptor_invoked: onError is called with ctx and original cause")
        void onError_singleInterceptor_invoked() {
            // Given a request interceptor that records calls
            RuntimeException cause = new RuntimeException("original");
            List<Throwable> observed = new ArrayList<>();

            RequestInterceptor interceptor = new RequestInterceptor() {
                @Override
                public void onError(RoutingContext rc, Throwable error) {
                    observed.add(error);
                }
            };

            ErrorPipeline pipeline =
                    new ErrorPipeline(List.of(), List.of(interceptor), new RestExceptionMapper(), registry500);

            // When
            pipeline.mapToResponse(ctx, cause);

            // Then onError received the ORIGINAL cause (before beforeMapping)
            assertEquals(1, observed.size());
            assertSame(cause, observed.get(0));
        }

        @Test
        @DisplayName("onError_multipleInterceptors_allInvokedInOrder: all observers called in list order")
        void onError_multipleInterceptors_allInvokedInOrder() {
            // Given three request interceptors, each recording their invocation order
            RuntimeException cause = new RuntimeException("err");
            List<String> invocationOrder = new ArrayList<>();

            RequestInterceptor first = new RequestInterceptor() {
                @Override
                public void onError(RoutingContext rc, Throwable error) {
                    invocationOrder.add("first");
                }
            };
            RequestInterceptor second = new RequestInterceptor() {
                @Override
                public void onError(RoutingContext rc, Throwable error) {
                    invocationOrder.add("second");
                }
            };
            RequestInterceptor third = new RequestInterceptor() {
                @Override
                public void onError(RoutingContext rc, Throwable error) {
                    invocationOrder.add("third");
                }
            };

            ErrorPipeline pipeline =
                    new ErrorPipeline(List.of(), List.of(first, second, third), new RestExceptionMapper(), registry500);

            // When
            pipeline.mapToResponse(ctx, cause);

            // Then all three ran in list order
            assertEquals(List.of("first", "second", "third"), invocationOrder);
        }

        @Test
        @DisplayName("onError_throwingInterceptor_swallowedSilentlyAndNextStillRuns: exception does not stop iteration")
        void onError_throwingInterceptor_swallowedSilentlyAndNextStillRuns() {
            // Given: first interceptor throws; second records whether it was reached
            List<String> invocationOrder = new ArrayList<>();

            RequestInterceptor throwing = new RequestInterceptor() {
                @Override
                public void onError(RoutingContext rc, Throwable error) {
                    invocationOrder.add("throwing");
                    throw new RuntimeException("observer exploded");
                }
            };
            RequestInterceptor afterThrowing = new RequestInterceptor() {
                @Override
                public void onError(RoutingContext rc, Throwable error) {
                    invocationOrder.add("afterThrowing");
                }
            };

            ErrorPipeline pipeline = new ErrorPipeline(
                    List.of(), List.of(throwing, afterThrowing), new RestExceptionMapper(), registry500);

            // When — this must complete without throwing (the pipeline catches and swallows)
            assertDoesNotThrow(() -> pipeline.mapToResponse(ctx, new RuntimeException("err")));

            // Then both interceptors ran; the exception from "throwing" was swallowed silently
            assertEquals(List.of("throwing", "afterThrowing"), invocationOrder);
        }

        @Test
        @DisplayName(
                "onError_throwingInterceptor_pipelineSucceeds: a throwing observer does not fail the response future")
        void onError_throwingInterceptor_pipelineSucceeds() {
            // Given an observer that always throws
            RequestInterceptor throwing = new RequestInterceptor() {
                @Override
                public void onError(RoutingContext rc, Throwable error) {
                    throw new RuntimeException("observer exploded");
                }
            };

            ErrorPipeline pipeline =
                    new ErrorPipeline(List.of(), List.of(throwing), new RestExceptionMapper(), registry500);

            // When
            Future<Response> result = pipeline.mapToResponse(ctx, new RuntimeException("err"));

            // Then the response future still succeeds — the observer's throw was swallowed silently
            assertTrue(result.succeeded());
            assertEquals(500, result.result().getStatus());
        }

        @Test
        @DisplayName(
                "onError_observedThrowableIsOriginalBeforeMapping: onError fires BEFORE beforeMapping transforms it")
        void onError_observedThrowableIsOriginalBeforeMapping() {
            // Given: an error interceptor that replaces the throwable in beforeMapping;
            //        and a request interceptor that records what onError sees.
            RuntimeException original = new RuntimeException("original");
            RuntimeException replaced = new RuntimeException("replaced");
            List<Throwable> observedByOnError = new ArrayList<>();

            ErrorInterceptor errorInterceptor = new ErrorInterceptor() {
                @Override
                public Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
                    return Future.succeededFuture(replaced);
                }
            };
            RequestInterceptor requestInterceptor = new RequestInterceptor() {
                @Override
                public void onError(RoutingContext rc, Throwable error) {
                    observedByOnError.add(error);
                }
            };

            ErrorPipeline pipeline = new ErrorPipeline(
                    List.of(errorInterceptor), List.of(requestInterceptor), new RestExceptionMapper(), registry500);

            // When
            pipeline.mapToResponse(ctx, original);

            // Then onError observed the ORIGINAL throwable, not the replaced one
            assertEquals(1, observedByOnError.size());
            assertSame(original, observedByOnError.get(0));
        }
    }
}
