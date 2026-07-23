// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.response.ResponseSerializer;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.EncodeException;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests for the {@code transformResponse} chain and {@code afterResponse} sync
 * loops inside {@link ResponsePipeline}, pinning observable behavior against the current
 * implementation before the Combinators migration.
 *
 * <p>These tests must be GREEN against the pre-migration code and must remain GREEN after migration
 * to {@link dev.vertique.core.async.Combinators}. A test failure after migration indicates a
 * behavior regression.
 *
 * <p>Behaviors pinned:
 * <ol>
 *   <li>FR-CORE-001.1 — {@code transformResponse} runs interceptors in list order, threading the
 *       {@link Response} value from one interceptor to the next.</li>
 *   <li>FR-CORE-001.2 — A failed {@code transformResponse} short-circuits the chain: no subsequent
 *       interceptors are invoked and the returned future fails with that cause.</li>
 *   <li>FR-CORE-001.3 — {@code afterResponse} (normal path) invokes every interceptor in list
 *       order; a thrown exception is swallowed and iteration continues with the next interceptor.</li>
 *   <li>FR-CORE-001.4 — {@code afterResponse} (fallback-500 path) follows the same swallowing
 *       rule: every interceptor is invoked in order; a thrown exception does not prevent later
 *       interceptors from running or the bare-metal 500 from being written.</li>
 * </ol>
 */
class ResponsePipelineCharacterizationTest {

    // --- Test doubles ---

    /**
     * Configurable {@link RequestInterceptor} double that records invocations and can be configured
     * to transform the response, fail asynchronously, or throw synchronously from
     * {@code afterResponse}.
     */
    static class RecordingInterceptor implements RequestInterceptor {

        final String name;
        final List<String> transformCalls = new ArrayList<>();
        final List<String> afterCalls = new ArrayList<>();

        /** When set, {@code transformResponse} appends this suffix to the existing entity string. */
        String transformSuffix;

        /** When true, {@code transformResponse} returns a failed future. */
        boolean transformFails;

        /** When true, {@code afterResponse} throws a {@link RuntimeException}. */
        boolean afterThrows;

        RecordingInterceptor(String name) {
            this.name = name;
        }

        @Override
        public Future<Response> transformResponse(RoutingContext rc, Response response) {
            transformCalls.add("transform:" + name);
            if (transformFails) {
                return Future.failedFuture(new RuntimeException("transform-fail:" + name));
            }
            if (transformSuffix != null) {
                String current =
                        response.getEntity() == null ? "" : response.getEntity().toString();
                return Future.succeededFuture(Response.fromResponse(response)
                        .entity(current + transformSuffix)
                        .build());
            }
            return Future.succeededFuture(response);
        }

        @Override
        public void afterResponse(RoutingContext rc, Response response) {
            afterCalls.add("after:" + name);
            if (afterThrows) {
                throw new RuntimeException("after-throw:" + name);
            }
        }
    }

    // --- Fixtures ---

    private RoutingContext ctx;
    private HttpServerResponse httpResponse;
    private ResponseSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = mock(ResponseSerializer.class);
        ctx = mock(RoutingContext.class);
        httpResponse = mock(HttpServerResponse.class);

        var httpRequest = mock(io.vertx.core.http.HttpServerRequest.class);
        when(httpRequest.method()).thenReturn(HttpMethod.GET);
        when(ctx.request()).thenReturn(httpRequest);
        when(ctx.response()).thenReturn(httpResponse);
        when(httpResponse.setStatusCode(anyInt())).thenReturn(httpResponse);
        when(httpResponse.putHeader(anyString(), anyString())).thenReturn(httpResponse);
        when(httpResponse.headers()).thenReturn(io.vertx.core.MultiMap.caseInsensitiveMultiMap());

        // RequestPreconditions.from() caches in ctx.data()
        when(ctx.data()).thenReturn(new HashMap<>());
        when(httpRequest.getHeader("If-None-Match")).thenReturn(null);
        when(httpRequest.getHeader("If-Match")).thenReturn(null);
        when(httpRequest.getHeader("If-Modified-Since")).thenReturn(null);
        when(httpRequest.getHeader("If-Unmodified-Since")).thenReturn(null);
    }

    private ResponsePipeline pipeline(List<RequestInterceptor> hooks) {
        return new ResponsePipeline(Set.of(), hooks, serializer);
    }

    // --- FR-CORE-001.1: transformResponse runs in list order threading Response ---

    /**
     * Tests for the {@code transformResponse} chain ordering contract (FR-CORE-001.1).
     */
    @Nested
    @DisplayName("FR-CORE-001.1 — transformResponse runs interceptors in list order threading Response")
    class TransformOrder {

        @Test
        @DisplayName("Empty list: seed response passes through unchanged")
        void emptyListPassesThroughSeedResponse() {
            ResponsePipeline p = pipeline(List.of());
            Response seed = Response.ok("seed").build();

            // sendResponse with no interceptors should serialize the same response
            p.sendResponse(ctx, seed);

            verify(serializer).serialize(eq(ctx), argThat(r -> "seed".equals(r.getEntity())));
        }

        @Test
        @DisplayName("Single interceptor: transforms and threads the response to serializer")
        void singleInterceptorThreadsTransformation() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            i1.transformSuffix = "+A";
            ResponsePipeline p = pipeline(List.of(i1));
            Response seed = Response.ok("v").build();

            p.sendResponse(ctx, seed);

            assertEquals(List.of("transform:A"), i1.transformCalls);
            verify(serializer).serialize(eq(ctx), argThat(r -> "v+A".equals(r.getEntity())));
        }

        @Test
        @DisplayName("Multiple interceptors: each transforms in list order, threading the running value")
        void multipleInterceptorsRunInOrderThreading() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            RecordingInterceptor i3 = new RecordingInterceptor("C");
            i1.transformSuffix = "+A";
            i2.transformSuffix = "+B";
            i3.transformSuffix = "+C";
            ResponsePipeline p = pipeline(List.of(i1, i2, i3));
            Response seed = Response.ok("v").build();

            p.sendResponse(ctx, seed);

            // Each must be called exactly once in order
            assertEquals(List.of("transform:A"), i1.transformCalls);
            assertEquals(List.of("transform:B"), i2.transformCalls);
            assertEquals(List.of("transform:C"), i3.transformCalls);
            // The serializer sees the final accumulated transformation
            verify(serializer).serialize(eq(ctx), argThat(r -> "v+A+B+C".equals(r.getEntity())));
        }
    }

    // --- FR-CORE-001.2: failed transformResponse short-circuits the chain ---

    /**
     * Tests for the fail-fast short-circuit contract of the {@code transformResponse} chain
     * (FR-CORE-001.2).
     */
    @Nested
    @DisplayName("FR-CORE-001.2 — failed transformResponse short-circuits; no subsequent interceptors invoked")
    class TransformShortCircuit {

        @Test
        @DisplayName("When first interceptor fails, second is never called and fallback-500 fires")
        void firstFailureSkipsRemainingAndTriggersFallback500() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            i1.transformFails = true;
            ResponsePipeline p = pipeline(List.of(i1, i2));
            when(httpResponse.ended()).thenReturn(false);
            when(httpResponse.end(anyString())).thenReturn(Future.succeededFuture());

            p.sendResponse(ctx, Response.ok().build());

            // A was called (it produced the failure), B was never called
            assertEquals(List.of("transform:A"), i1.transformCalls);
            assertTrue(i2.transformCalls.isEmpty(), "B must not be invoked after A fails");
            // bare-metal 500 written
            verify(httpResponse).setStatusCode(500);
        }

        @Test
        @DisplayName("When middle interceptor fails, later interceptors are not invoked")
        void middleFailureSkipsLater() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            RecordingInterceptor i3 = new RecordingInterceptor("C");
            i2.transformFails = true;
            ResponsePipeline p = pipeline(List.of(i1, i2, i3));
            when(httpResponse.ended()).thenReturn(false);
            when(httpResponse.end(anyString())).thenReturn(Future.succeededFuture());

            p.sendResponse(ctx, Response.ok("v").build());

            assertEquals(List.of("transform:A"), i1.transformCalls);
            assertEquals(List.of("transform:B"), i2.transformCalls);
            assertTrue(i3.transformCalls.isEmpty(), "C must not be invoked after B fails");
            verify(httpResponse).setStatusCode(500);
        }

        @Test
        @DisplayName("Serializer is never called when transformResponse chain fails")
        void serializerNotCalledOnChainFailure() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            i1.transformFails = true;
            ResponsePipeline p = pipeline(List.of(i1));
            when(httpResponse.ended()).thenReturn(false);
            when(httpResponse.end(anyString())).thenReturn(Future.succeededFuture());

            p.sendResponse(ctx, Response.ok().build());

            verify(serializer, never()).serialize(any(), any());
        }
    }

    // --- FR-CORE-001.3: afterResponse (normal path) swallows exceptions and continues ---

    /**
     * Tests for the swallowing contract of the {@code afterResponse} normal-path sync loop
     * (FR-CORE-001.3).
     */
    @Nested
    @DisplayName(
            "FR-CORE-001.3 — afterResponse (normal path): every interceptor invoked in order; exception swallowed, iteration continues")
    class AfterResponseNormalSwallow {

        @Test
        @DisplayName("All interceptors invoked in list order on success path")
        void allInterceptorsInvokedInOrderOnSuccess() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            RecordingInterceptor i3 = new RecordingInterceptor("C");
            ResponsePipeline p = pipeline(List.of(i1, i2, i3));

            p.sendResponse(ctx, Response.ok().build());

            assertEquals(List.of("after:A"), i1.afterCalls);
            assertEquals(List.of("after:B"), i2.afterCalls);
            assertEquals(List.of("after:C"), i3.afterCalls);
        }

        @Test
        @DisplayName("Exception from first afterResponse hook is swallowed; second hook still runs")
        void exceptionFromFirstSwallowedSecondRuns() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            i1.afterThrows = true;
            ResponsePipeline p = pipeline(List.of(i1, i2));

            // must not propagate
            assertDoesNotThrow(() -> p.sendResponse(ctx, Response.ok().build()));

            assertEquals(List.of("after:A"), i1.afterCalls);
            assertEquals(List.of("after:B"), i2.afterCalls);
        }

        @Test
        @DisplayName("Exception from middle hook is swallowed; later hooks still run")
        void exceptionFromMiddleSwallowedLaterRuns() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            RecordingInterceptor i3 = new RecordingInterceptor("C");
            i2.afterThrows = true;
            ResponsePipeline p = pipeline(List.of(i1, i2, i3));

            assertDoesNotThrow(() -> p.sendResponse(ctx, Response.ok().build()));

            assertEquals(List.of("after:A"), i1.afterCalls);
            assertEquals(List.of("after:B"), i2.afterCalls);
            assertEquals(List.of("after:C"), i3.afterCalls);
        }

        @Test
        @DisplayName("afterResponse receives the final transformed response, not the seed")
        void afterResponseReceivesFinalTransformedResponse() {
            RecordingInterceptor transformer = new RecordingInterceptor("T");
            transformer.transformSuffix = "+T";
            List<String> afterEntities = new ArrayList<>();
            RequestInterceptor observer = new RequestInterceptor() {
                @Override
                public void afterResponse(RoutingContext rc, Response response) {
                    afterEntities.add(
                            response.getEntity() == null
                                    ? null
                                    : response.getEntity().toString());
                }
            };
            ResponsePipeline p = pipeline(List.of(transformer, observer));

            p.sendResponse(ctx, Response.ok("v").build());

            assertEquals(
                    List.of("v+T"),
                    afterEntities,
                    "afterResponse must see the response after the full transformResponse chain");
        }

        @Test
        @DisplayName("afterResponse on the normal path fires AFTER the wire write, exactly once")
        void afterResponseFiresAfterSuccessfulWire() {
            // Corrected single-fire contract: on the success path afterResponse fires AFTER a
            // successful applyToWire (serializer.serialize). Re-pinned deliberately to the new
            // order — the observer must see the serialize call already done by the time it runs,
            // and it must fire exactly once.
            List<String> events = new ArrayList<>();
            doAnswer(inv -> {
                        events.add("serialize");
                        return null;
                    })
                    .when(serializer)
                    .serialize(eq(ctx), any(Response.class));
            RequestInterceptor observer = new RequestInterceptor() {
                @Override
                public void afterResponse(RoutingContext rc, Response response) {
                    events.add("after");
                }
            };
            ResponsePipeline p = pipeline(List.of(observer));

            p.sendResponse(ctx, Response.ok("v").build());

            assertEquals(
                    List.of("serialize", "after"),
                    events,
                    "afterResponse must fire exactly once, AFTER the successful wire write");
        }
    }

    // --- FR-CORE-001.4: afterResponse (fallback-500 path) swallows exceptions and continues ---

    /**
     * Tests for the swallowing contract of the {@code afterResponse} fallback-500-path sync loop
     * (FR-CORE-001.4).
     *
     * <p>Note: both the loop and {@link dev.vertique.core.async.Combinators#forEachSwallowSync}
     * catch {@link Exception} (including checked exceptions) and route each per-hook failure to the
     * onFailure logger, so a throwing observer is swallowed and the remaining hooks still run. This
     * comment exists so future reviewers can find the swallowing decision quickly.
     */
    @Nested
    @DisplayName(
            "FR-CORE-001.4 — afterResponse (fallback-500 path): every hook invoked; exception swallowed, bare-metal 500 still written")
    class AfterResponseFallbackSwallow {

        @Test
        @DisplayName("All afterResponse hooks invoked in order on fallback-500 path")
        void allHooksInvokedInOrderOnFallback500() {
            RecordingInterceptor failTransform = new RecordingInterceptor("F");
            failTransform.transformFails = true;
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            ResponsePipeline p = pipeline(List.of(failTransform, i1, i2));
            when(httpResponse.ended()).thenReturn(false);
            when(httpResponse.end(anyString())).thenReturn(Future.succeededFuture());

            p.sendResponse(ctx, Response.ok().build());

            // The transform-failing interceptor ran transformResponse (to fail), then
            // on fallback, ALL interceptors run afterResponse
            assertEquals(List.of("after:F"), failTransform.afterCalls);
            assertEquals(List.of("after:A"), i1.afterCalls);
            assertEquals(List.of("after:B"), i2.afterCalls);
        }

        @Test
        @DisplayName("Exception from first afterResponse on fallback-500 path is swallowed; second still runs")
        void exceptionFromFirstSwallowedSecondRunsOnFallback() {
            RecordingInterceptor failTransform = new RecordingInterceptor("F");
            failTransform.transformFails = true;
            RecordingInterceptor throwAfter = new RecordingInterceptor("Throw");
            throwAfter.afterThrows = true;
            RecordingInterceptor secondAfter = new RecordingInterceptor("OK");
            ResponsePipeline p = pipeline(List.of(failTransform, throwAfter, secondAfter));
            when(httpResponse.ended()).thenReturn(false);
            when(httpResponse.end(anyString())).thenReturn(Future.succeededFuture());

            // must not propagate
            assertDoesNotThrow(() -> p.sendResponse(ctx, Response.ok().build()));

            assertEquals(List.of("after:Throw"), throwAfter.afterCalls);
            assertEquals(List.of("after:OK"), secondAfter.afterCalls);
        }

        @Test
        @DisplayName("Bare-metal 500 is still written even when all afterResponse hooks throw on fallback path")
        void bareMetalFallback500WrittenEvenWhenAllHooksThrow() {
            RecordingInterceptor failTransform = new RecordingInterceptor("F");
            failTransform.transformFails = true;
            RecordingInterceptor throwAfter1 = new RecordingInterceptor("T1");
            throwAfter1.afterThrows = true;
            RecordingInterceptor throwAfter2 = new RecordingInterceptor("T2");
            throwAfter2.afterThrows = true;
            ResponsePipeline p = pipeline(List.of(failTransform, throwAfter1, throwAfter2));
            when(httpResponse.ended()).thenReturn(false);
            when(httpResponse.end(anyString())).thenReturn(Future.succeededFuture());

            assertDoesNotThrow(() -> p.sendResponse(ctx, Response.ok().build()));

            verify(httpResponse).setStatusCode(500);
            verify(httpResponse).end(contains("about:blank"));
            // serializer must not be called on bare-metal path
            verify(serializer, never()).serialize(any(), any());
        }

        @Test
        @DisplayName("afterResponse on fallback-500 path receives a synthetic 500 response with no entity")
        void fallback500PathPassesSynthetic500ToAfterResponse() {
            RecordingInterceptor failTransform = new RecordingInterceptor("F");
            failTransform.transformFails = true;
            List<Response> observed = new ArrayList<>();
            RequestInterceptor observer = new RequestInterceptor() {
                @Override
                public void afterResponse(RoutingContext rc, Response response) {
                    observed.add(response);
                }
            };
            ResponsePipeline p = pipeline(List.of(failTransform, observer));
            when(httpResponse.ended()).thenReturn(false);
            when(httpResponse.end(anyString())).thenReturn(Future.succeededFuture());

            p.sendResponse(ctx, Response.ok("original").build());

            assertFalse(observed.isEmpty(), "afterResponse must be invoked on fallback-500 path");
            assertEquals(500, observed.get(0).getStatus(), "synthetic response must carry status 500");
            assertNull(observed.get(0).getEntity(), "synthetic response must have no entity");
        }
    }

    // --- Success-entity serialization failure routes to fallback-500 (sync-throw, CORE-001) ---

    /**
     * Tests that a SYNCHRONOUS serialization failure of a <em>success</em> entity — thrown by the
     * serializer from inside the {@code onSuccess} handler of {@link ResponsePipeline#sendResponse}
     * — is routed to {@link ResponsePipeline#sendFallback500} rather than escaping uncaught and
     * leaving the HTTP response unfinished (a hung client).
     *
     * <p>This is the CORE-001 sync-throw characterization: {@code .onFailure(...)} only catches the
     * transform-chain future's failure, not a throw inside {@code .onSuccess(...)}.
     */
    @Nested
    @DisplayName("Success-entity serialization failure routes to fallback-500 (sync-throw)")
    class SuccessEntitySerializationFailure {

        @Test
        @DisplayName("A serializer that throws on a success entity sends a bare-metal 500, not an uncaught throw")
        void successEntitySerializationThrows_sendsFallback500() {
            // given a success Response (no KEY_ERROR_RESPONSE) whose serialization throws EncodeException
            ResponsePipeline p = pipeline(List.of());
            when(httpResponse.ended()).thenReturn(false);
            when(httpResponse.end(anyString())).thenReturn(Future.succeededFuture());
            doThrow(new EncodeException("profile mapper failed to encode success entity"))
                    .when(serializer)
                    .serialize(eq(ctx), any(Response.class));

            // when the pipeline sends a success entity
            assertDoesNotThrow(
                    () -> p.sendResponse(ctx, Response.ok("v").build()),
                    "a synchronous serialization failure must not escape sendResponse");

            // then the fallback-500 path wrote a bare-metal 500 (not a hung response)
            verify(httpResponse).setStatusCode(500);
            verify(httpResponse).end(contains("about:blank"));
        }

        @Test
        @DisplayName("Success-entity serialization throw fires afterResponse exactly once, with a status-500 response")
        void successEntitySerializationThrows_firesAfterResponseExactlyOnceWith500() {
            // given a success Response whose serialization throws, WITH an afterResponse-recording
            // hook present. Defect #1 (double afterResponse) would fire the hook twice — once with
            // the success response (before applyToWire) and once with the synthetic 500 (in
            // sendFallback500). The corrected contract: afterResponse fires EXACTLY ONCE, reflecting
            // the terminal outcome actually written — the 500.
            List<Response> observed = new ArrayList<>();
            RequestInterceptor observer = new RequestInterceptor() {
                @Override
                public void afterResponse(RoutingContext rc, Response response) {
                    observed.add(response);
                }
            };
            ResponsePipeline p = pipeline(List.of(observer));
            when(httpResponse.ended()).thenReturn(false);
            when(httpResponse.headWritten()).thenReturn(false);
            when(httpResponse.end(anyString())).thenReturn(Future.succeededFuture());
            doThrow(new EncodeException("profile mapper failed to encode success entity"))
                    .when(serializer)
                    .serialize(eq(ctx), any(Response.class));

            // when the pipeline sends a success entity
            assertDoesNotThrow(() -> p.sendResponse(ctx, Response.ok("v").build()));

            // then afterResponse fired exactly once, and with the synthetic 500 — not the success
            assertEquals(1, observed.size(), "afterResponse must fire exactly once, not twice");
            assertEquals(
                    500,
                    observed.get(0).getStatus(),
                    "the single afterResponse must reflect the terminal outcome actually written (500)");
            verify(httpResponse).setStatusCode(500);
            verify(httpResponse).end(contains("about:blank"));
        }

        @Test
        @DisplayName("Error-body serialization throw after head committed does not double-write or escape")
        void errorBodySerializationThrowsAfterHeadWritten_doesNotDoubleWrite() {
            // given KEY_ERROR_RESPONSE=true, a resolved error mapper that throws on serialize, and a
            // response whose head is already committed (headWritten() true, ended() false). The
            // fail-open in serializeErrorWithFailOpen deliberately RETHROWS on a committed head — and
            // Defect #2 (Critical) then let sendFallback500's !ended()-only guard call
            // setStatusCode(500).end(...) on the committed response (IllegalStateException / split
            // response). The corrected guard (!ended() && !headWritten()) suppresses the second write.
            ctx.data().put(ResponsePipeline.KEY_ERROR_RESPONSE, Boolean.TRUE);
            when(ctx.get(ResponsePipeline.KEY_ERROR_RESPONSE)).thenReturn(Boolean.TRUE);
            when(httpResponse.ended()).thenReturn(false);
            when(httpResponse.headWritten()).thenReturn(true);
            doThrow(new EncodeException("profile mapper failed to encode error body"))
                    .when(serializer)
                    .serialize(eq(ctx), any(Response.class));

            List<Response> observed = new ArrayList<>();
            RequestInterceptor observer = new RequestInterceptor() {
                @Override
                public void afterResponse(RoutingContext rc, Response response) {
                    observed.add(response);
                }
            };
            ResponsePipeline p = pipeline(List.of(observer));

            // when the pipeline sends the error response
            assertDoesNotThrow(
                    () -> p.sendResponse(ctx, Response.status(409).entity("err").build()),
                    "a committed-head error-body throw must not escape sendResponse");

            // then the committed partial response is left as-is — NO second bare-metal write
            verify(httpResponse, never()).setStatusCode(500);
            verify(httpResponse, never()).end(anyString());
            // and afterResponse still fired exactly once (the request DID fail terminally)
            assertEquals(1, observed.size(), "afterResponse must fire exactly once on the failure path");
            assertEquals(500, observed.get(0).getStatus(), "the single afterResponse reflects the 500 failure");
        }
    }
}
