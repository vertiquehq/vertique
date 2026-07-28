// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.rest.core.events.RestRequestCompletionEmitter;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.response.ResponseProducerBinding;
import dev.vertique.rest.core.response.ResponseSerializer;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.json.EncodeException;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link ResponsePipeline}.
 *
 * <p>Verifies type-based response dispatch, including the built-in Void and
 * {@link Response} producers, the JSON fallback path, custom producer registration via
 * {@link ResponseProducerBinding}, superclass hierarchy walking, Accept header negotiation
 * (200 with negotiated content type and 406 Not Acceptable), {@code sendResponse},
 * and the new {@link RequestInterceptor#afterResponse} observer for both success and error paths.
 *
 * <p>With the restructured pipeline, the pipeline sets status and headers on the HTTP response
 * directly, then either ends the response (for no-entity responses) or calls the serializer
 * (for responses with an entity). Tests verify the correct path is taken in each case.
 *
 * <p>The {@code wire-completion observation} group additionally verifies what the pipeline does
 * with the serializer's wire-completion future <em>after</em> the response has been handed off:
 * the {@link RestRequestCompletionEmitter#KEY_WIRE_FAILURE} marker, the class-only WARN, guarded
 * terminal cleanup, redispatch onto the captured request context, and the error fail-open and
 * fallback-500 completion paths.
 */
class ResponsePipelineTest {

    private ResponsePipeline pipeline;
    private RoutingContext ctx;
    private HttpServerResponse httpResponse;
    private ResponseSerializer serializer;
    private MultiMap responseHeaders;

    @BeforeEach
    void setUp() {
        serializer = mock(ResponseSerializer.class);
        // The serializer SPI returns a wire-completion future; a clean pass-through keeps every
        // pre-existing scenario on the success path once the pipeline observes that future.
        when(serializer.serialize(any(), any())).thenReturn(Future.succeededFuture());
        pipeline = new ResponsePipeline(Set.of(), List.of(), serializer);

        ctx = mock(RoutingContext.class);
        httpResponse = mock(HttpServerResponse.class);

        var httpRequest = mock(io.vertx.core.http.HttpServerRequest.class);
        when(httpRequest.method()).thenReturn(HttpMethod.GET);
        when(ctx.request()).thenReturn(httpRequest);
        when(ctx.response()).thenReturn(httpResponse);
        when(httpResponse.setStatusCode(anyInt())).thenReturn(httpResponse);
        when(httpResponse.putHeader(anyString(), anyString())).thenReturn(httpResponse);
        // Wire terminations return futures the pipeline observes; individual tests override these.
        when(httpResponse.end()).thenReturn(Future.succeededFuture());
        when(httpResponse.end(anyString())).thenReturn(Future.succeededFuture());
        when(httpResponse.reset()).thenReturn(Future.succeededFuture());

        responseHeaders = MultiMap.caseInsensitiveMultiMap();
        when(httpResponse.headers()).thenReturn(responseHeaders);

        // RequestPreconditions.from() caches in ctx.data()
        when(ctx.data()).thenReturn(new HashMap<>());
        // No conditional headers by default
        when(httpRequest.getHeader("If-None-Match")).thenReturn(null);
        when(httpRequest.getHeader("If-Match")).thenReturn(null);
        when(httpRequest.getHeader("If-Modified-Since")).thenReturn(null);
        when(httpRequest.getHeader("If-Unmodified-Since")).thenReturn(null);
    }

    // --- 204 No Content ---

    @Test
    @DisplayName("Should write 204 status and end with no body when result is null")
    void shouldReturn204ForNullResult() {
        pipeline.handle(ctx, null);

        verify(httpResponse).setStatusCode(204);
        verify(httpResponse).end();
        verify(serializer, never()).serialize(any(), any());
    }

    // --- JAX-RS Response dispatch ---

    @Test
    @DisplayName("Should delegate to serializer for JAX-RS Response with an entity")
    void shouldDispatchResponseType() {
        Map<String, String> entity = Map.of("key", "value");
        Response jaxRsResponse = Response.ok().entity(entity).build();

        pipeline.handle(ctx, jaxRsResponse);

        verify(serializer).serialize(eq(ctx), argThat(r -> r.getStatus() == 200 && r.getEntity() == entity));
    }

    // --- JSON fallback ---

    @Test
    @DisplayName("Should wrap unregistered types in 200 OK Response and delegate to serializer")
    void shouldFallbackToJsonForUnregisteredType() {
        Map<String, String> plain = Map.of("hello", "world");

        pipeline.handle(ctx, plain);

        verify(serializer).serialize(eq(ctx), argThat(r -> r.getStatus() == 200 && r.getEntity() == plain));
    }

    // --- Custom producer registration via ResponseProducerBinding ---

    @Test
    @DisplayName("Should invoke a custom producer registered for String.class via binding")
    void shouldUseCustomProducer() {
        String result = "custom-output";
        Response produced = Response.ok(result).build();
        ResponseProducerBinding<String> binding = new ResponseProducerBinding<>(String.class, (c, r) -> produced);

        ResponsePipeline customPipeline = new ResponsePipeline(Set.of(binding), List.of(), serializer);
        customPipeline.handle(ctx, result);

        verify(serializer).serialize(eq(ctx), eq(produced));
    }

    // --- Superclass hierarchy walking ---

    @Test
    @DisplayName("Should find producer registered for Number.class when passing an Integer result")
    void shouldFindProducerByHierarchy() {
        Integer intResult = 42;
        Response produced = Response.ok(intResult).build();
        ResponseProducerBinding<Number> binding = new ResponseProducerBinding<>(Number.class, (c, r) -> produced);

        ResponsePipeline customPipeline = new ResponsePipeline(Set.of(binding), List.of(), serializer);
        customPipeline.handle(ctx, intResult);

        verify(serializer).serialize(eq(ctx), eq(produced));
    }

    // --- Interface-based producer lookup ---

    @Test
    @DisplayName("Should find producer registered for an interface type")
    void shouldFindProducerByInterface() {
        List<String> listResult = new ArrayList<>(List.of("a", "b"));
        Response produced = Response.ok(listResult).build();
        ResponseProducerBinding<List> binding = new ResponseProducerBinding<>(List.class, (c, r) -> produced);

        ResponsePipeline customPipeline = new ResponsePipeline(Set.of(binding), List.of(), serializer);
        customPipeline.handle(ctx, listResult);

        verify(serializer).serialize(eq(ctx), eq(produced));
    }

    @Test
    @DisplayName("Should prefer concrete class producer over interface producer")
    void shouldPreferClassOverInterface() {
        ArrayList<String> listResult = new ArrayList<>(List.of("a", "b"));
        Response classProduced = Response.ok("class").build();
        Response ifaceProduced = Response.ok("iface").build();
        ResponseProducerBinding<ArrayList> classBinding =
                new ResponseProducerBinding<>(ArrayList.class, (c, r) -> classProduced);
        ResponseProducerBinding<List> ifaceBinding = new ResponseProducerBinding<>(List.class, (c, r) -> ifaceProduced);

        ResponsePipeline customPipeline =
                new ResponsePipeline(Set.of(classBinding, ifaceBinding), List.of(), serializer);
        customPipeline.handle(ctx, listResult);

        verify(serializer).serialize(eq(ctx), eq(classProduced));
    }

    @Test
    @DisplayName("Should prefer direct interface over super-interface")
    void shouldPreferDirectInterfaceOverSuperInterface() {
        ArrayList<String> listResult = new ArrayList<>(List.of("a", "b"));
        Response listProduced = Response.ok("list").build();
        Response collectionProduced = Response.ok("collection").build();
        ResponseProducerBinding<List> listBinding = new ResponseProducerBinding<>(List.class, (c, r) -> listProduced);
        ResponseProducerBinding<Collection> collBinding =
                new ResponseProducerBinding<>(Collection.class, (c, r) -> collectionProduced);

        ResponsePipeline customPipeline = new ResponsePipeline(Set.of(listBinding, collBinding), List.of(), serializer);
        customPipeline.handle(ctx, listResult);

        verify(serializer).serialize(eq(ctx), eq(listProduced));
    }

    // --- Accept header negotiation ---

    @Test
    @DisplayName("Should return 406 when Accept header does not match any @Produces media type")
    void shouldReturn406WhenAcceptDoesNotMatch() {
        when(ctx.get(ResourceMethodInvoker.CTX_KEY_PRODUCES)).thenReturn(List.of("application/xml"));
        when(ctx.request().getHeader("Accept")).thenReturn("application/json");

        pipeline.handle(ctx, Map.of("key", "value"));

        verify(serializer).serialize(eq(ctx), argThat(r -> r.getStatus() == 406));
    }

    @Test
    @DisplayName("Should negotiate Accept header and return 200 with matching content type")
    void shouldNegotiateAcceptHeaderSuccessfully() {
        when(ctx.get(ResourceMethodInvoker.CTX_KEY_PRODUCES))
                .thenReturn(List.of("application/json", "application/xml"));
        when(ctx.request().getHeader("Accept")).thenReturn("application/xml");

        pipeline.handle(ctx, Map.of("key", "value"));

        verify(serializer)
                .serialize(
                        eq(ctx),
                        argThat(r ->
                                r.getStatus() == 200 && "application/xml".equals(r.getHeaderString("Content-Type"))));
    }

    @Test
    @DisplayName("Should add Vary: Accept header when Accept negotiation runs")
    void shouldAddVaryAcceptWhenNegotiationRuns() {
        when(ctx.get(ResourceMethodInvoker.CTX_KEY_PRODUCES)).thenReturn(List.of("application/json"));
        when(ctx.request().getHeader("Accept")).thenReturn("application/json");

        pipeline.handle(ctx, Map.of("key", "value"));

        // responseHeaders is a real MultiMap (not a mock); verify via its actual contents
        assertEquals("Accept", responseHeaders.get("Vary"));
    }

    // --- Conditional precondition evaluation gated to 2xx (RFC 9110 §13.2.2) ---

    @Test
    @DisplayName("Should leave a non-2xx response (e.g. a 302 redirect with no ETag) untouched even when "
            + "If-Match is present")
    void evaluatePreconditionsSkippedForNon2xxResponses() {
        Response redirect = Response.status(302)
                .location(URI.create("https://example.test/target"))
                .build();
        Response produced = Response.fromResponse(redirect).build();
        ResponseProducerBinding<Response> binding = new ResponseProducerBinding<>(Response.class, (c, r) -> produced);
        ResponsePipeline customPipeline = new ResponsePipeline(Set.of(binding), List.of(), serializer);

        // A client sends If-Match against a redirect that carries no ETag — unconditionally
        // evaluating this would fail the precondition and rewrite the 302 into a spurious 412.
        when(ctx.request().getHeader("If-Match")).thenReturn("\"some-etag\"");

        customPipeline.handle(ctx, produced);

        verify(httpResponse).setStatusCode(302);
        verify(httpResponse, never()).setStatusCode(412);
    }

    @Test
    @DisplayName("Should still apply conditional evaluation to a 2xx response (If-None-Match match -> 304)")
    void evaluatePreconditionsStillAppliesTo2xx() {
        EntityTag etag = new EntityTag("matching-etag");
        Response produced = Response.ok("body").tag(etag).build();
        ResponseProducerBinding<Response> binding = new ResponseProducerBinding<>(Response.class, (c, r) -> produced);
        ResponsePipeline customPipeline = new ResponsePipeline(Set.of(binding), List.of(), serializer);

        when(ctx.request().getHeader("If-None-Match")).thenReturn("\"matching-etag\"");

        customPipeline.handle(ctx, produced);

        verify(httpResponse).setStatusCode(304);
        verify(serializer, never()).serialize(any(), any());
    }

    @Test
    @DisplayName("Should leave a validator-less 2xx response (e.g. a URL-issuance 200 with no ETag) untouched "
            + "even when If-Match is present")
    void evaluatePreconditionsSkippedForValidatorLess2xxResponses() {
        // A 200 that carries neither an ETag nor Last-Modified has not opted into conditional
        // handling — evaluating a client's If-Match against the absent validator would spuriously
        // yield 412 for a validator-less bodyless success response.
        Response produced = Response.ok("https://example.test/signed-url").build();
        ResponseProducerBinding<Response> binding = new ResponseProducerBinding<>(Response.class, (c, r) -> produced);
        ResponsePipeline customPipeline = new ResponsePipeline(Set.of(binding), List.of(), serializer);

        when(ctx.request().getHeader("If-Match")).thenReturn("\"some-etag\"");

        customPipeline.handle(ctx, produced);

        verify(httpResponse).setStatusCode(200);
        verify(httpResponse, never()).setStatusCode(412);
    }

    // --- sendResponse (unified path for both success and error) ---

    @Test
    @DisplayName("Should set status, copy headers, and delegate body to serializer via sendResponse")
    void shouldSendResponseViaSerializer() {
        Response errorResponse = Response.status(422).entity("error body").build();

        pipeline.sendResponse(ctx, errorResponse);

        verify(httpResponse).setStatusCode(422);
        verify(serializer).serialize(eq(ctx), eq(errorResponse));
    }

    // --- afterResponse observer ---

    @Nested
    @DisplayName("afterResponse observer")
    class AfterResponseObserver {

        @Test
        @DisplayName("Should fire afterResponse for a success response")
        void shouldFireAfterResponseForSuccess() {
            AtomicReference<Response> observed = new AtomicReference<>();
            RequestInterceptor interceptor = new RequestInterceptor() {
                @Override
                public void afterResponse(RoutingContext rc, Response response) {
                    observed.set(response);
                }
            };
            ResponsePipeline pipelineWithHook = new ResponsePipeline(Set.of(), List.of(interceptor), serializer);

            Map<String, String> result = Map.of("key", "value");
            pipelineWithHook.handle(ctx, result);

            assertNotNull(observed.get());
            assertEquals(200, observed.get().getStatus());
        }

        @Test
        @DisplayName("Should fire afterResponse for an error response sent via sendResponse")
        void shouldFireAfterResponseForError() {
            AtomicReference<Response> observed = new AtomicReference<>();
            RequestInterceptor interceptor = new RequestInterceptor() {
                @Override
                public void afterResponse(RoutingContext rc, Response response) {
                    observed.set(response);
                }
            };
            ResponsePipeline pipelineWithHook = new ResponsePipeline(Set.of(), List.of(interceptor), serializer);
            Response errorResponse = Response.status(500).entity("error").build();

            pipelineWithHook.sendResponse(ctx, errorResponse);

            assertNotNull(observed.get());
            assertEquals(500, observed.get().getStatus());
        }

        @Test
        @DisplayName("Should fire afterResponse for a 406 Not Acceptable response")
        void shouldFireAfterResponseFor406() {
            AtomicReference<Response> observed = new AtomicReference<>();
            RequestInterceptor interceptor = new RequestInterceptor() {
                @Override
                public void afterResponse(RoutingContext rc, Response response) {
                    observed.set(response);
                }
            };
            ResponsePipeline pipelineWithHook = new ResponsePipeline(Set.of(), List.of(interceptor), serializer);
            when(ctx.get(ResourceMethodInvoker.CTX_KEY_PRODUCES)).thenReturn(List.of("application/xml"));
            when(ctx.request().getHeader("Accept")).thenReturn("application/json");

            pipelineWithHook.handle(ctx, Map.of("key", "value"));

            assertNotNull(observed.get());
            assertEquals(406, observed.get().getStatus());
        }

        @Test
        @DisplayName("Should swallow exceptions thrown by afterResponse observers")
        void shouldSwallowAfterResponseExceptions() {
            RequestInterceptor throwingInterceptor = new RequestInterceptor() {
                @Override
                public void afterResponse(RoutingContext rc, Response response) {
                    throw new RuntimeException("observer failed");
                }
            };
            ResponsePipeline pipelineWithHook =
                    new ResponsePipeline(Set.of(), List.of(throwingInterceptor), serializer);

            assertDoesNotThrow(() -> pipelineWithHook.handle(ctx, null));
            verify(httpResponse).setStatusCode(204);
        }

        @Test
        @DisplayName("Should see final response after transformResponse chain in afterResponse")
        void shouldSeeTransformedResponseInAfterResponse() {
            AtomicReference<Response> observed = new AtomicReference<>();
            RequestInterceptor transforming = new RequestInterceptor() {
                @Override
                public Future<Response> transformResponse(RoutingContext rc, Response response) {
                    return Future.succeededFuture(Response.fromResponse(response)
                            .header("X-Custom", "added")
                            .build());
                }
            };
            RequestInterceptor observer = new RequestInterceptor() {
                @Override
                public void afterResponse(RoutingContext rc, Response response) {
                    observed.set(response);
                }
            };
            ResponsePipeline pipelineWithHooks =
                    new ResponsePipeline(Set.of(), List.of(transforming, observer), serializer);

            pipelineWithHooks.handle(ctx, null);

            assertNotNull(observed.get());
            // The observer should see the response already transformed by the transforming interceptor
            assertNotNull(observed.get().getHeaderString("X-Custom"));
            assertEquals("added", observed.get().getHeaderString("X-Custom"));
        }
    }

    // --- Pipeline failure fallback ---

    @Test
    @DisplayName("Should send bare-metal 500 when transformResponse chain fails")
    void shouldSendFallback500WhenPipelineFails() {
        RequestInterceptor failingInterceptor = new RequestInterceptor() {
            @Override
            public Future<Response> transformResponse(RoutingContext rc, Response response) {
                return Future.failedFuture(new RuntimeException("transform failed"));
            }
        };
        ResponsePipeline pipelineWithHook = new ResponsePipeline(Set.of(), List.of(failingInterceptor), serializer);
        when(httpResponse.ended()).thenReturn(false);
        when(httpResponse.end(anyString())).thenReturn(Future.succeededFuture());

        pipelineWithHook.handle(ctx, null);

        verify(httpResponse).setStatusCode(500);
        verify(httpResponse).end(contains("about:blank"));
    }

    // --- sendFallback500 afterResponse observer ---

    /**
     * Verifies that when the {@code transformResponse} chain fails, every registered
     * {@code afterResponse} hook is still invoked exactly once with a synthetic 500 Response
     * before the bare-metal 500 is written.  This ensures observe-only consumers (metrics,
     * tracing, audit) never miss a fallback-500 outcome.
     *
     * <p>This test is the primary RED/GREEN driver for the span-status regression fix.
     */
    @Nested
    @DisplayName("afterResponse fired on sendFallback500 path")
    class FallbackAfterResponseObserver {

        @Test
        @DisplayName("afterResponse hook invoked with status 500 when transformResponse chain fails")
        void shouldFireAfterResponseWithSynthetic500OnPipelineFailure() {
            AtomicReference<Response> observed = new AtomicReference<>();
            RequestInterceptor failingTransform = new RequestInterceptor() {
                @Override
                public Future<Response> transformResponse(RoutingContext rc, Response response) {
                    return Future.failedFuture(new RuntimeException("transform failed"));
                }
            };
            RequestInterceptor observer = new RequestInterceptor() {
                @Override
                public void afterResponse(RoutingContext rc, Response response) {
                    observed.set(response);
                }
            };
            ResponsePipeline p = new ResponsePipeline(Set.of(), List.of(failingTransform, observer), serializer);
            when(httpResponse.ended()).thenReturn(false);
            when(httpResponse.end(anyString())).thenReturn(Future.succeededFuture());

            p.handle(ctx, null);

            assertNotNull(observed.get(), "afterResponse hook must be invoked on the fallback-500 path");
            assertEquals(500, observed.get().getStatus(), "synthetic response must carry status 500");
        }

        @Test
        @DisplayName("all afterResponse hooks are invoked even when one of them throws")
        void shouldInvokeAllAfterResponseHooksEvenWhenOnThrows() {
            List<Integer> invocationOrder = new ArrayList<>();
            RequestInterceptor failingTransform = new RequestInterceptor() {
                @Override
                public Future<Response> transformResponse(RoutingContext rc, Response response) {
                    return Future.failedFuture(new RuntimeException("transform failed"));
                }
            };
            RequestInterceptor throwingObserver = new RequestInterceptor() {
                @Override
                public void afterResponse(RoutingContext rc, Response response) {
                    invocationOrder.add(1);
                    throw new RuntimeException("observer 1 failed");
                }
            };
            RequestInterceptor secondObserver = new RequestInterceptor() {
                @Override
                public void afterResponse(RoutingContext rc, Response response) {
                    invocationOrder.add(2);
                }
            };
            ResponsePipeline p = new ResponsePipeline(
                    Set.of(), List.of(failingTransform, throwingObserver, secondObserver), serializer);
            when(httpResponse.ended()).thenReturn(false);
            when(httpResponse.end(anyString())).thenReturn(Future.succeededFuture());

            // must not throw even though an observer threw
            assertDoesNotThrow(() -> p.handle(ctx, null));

            assertEquals(List.of(1, 2), invocationOrder, "both observers must be called in order");
            // the bare-metal 500 must still be written
            verify(httpResponse).setStatusCode(500);
            verify(httpResponse).end(contains("about:blank"));
        }

        @Test
        @DisplayName("bare-metal 500 is still written after afterResponse hooks run on fallback path")
        void shouldStillWriteBareMetalFallback500AfterHooks() {
            RequestInterceptor failingTransform = new RequestInterceptor() {
                @Override
                public Future<Response> transformResponse(RoutingContext rc, Response response) {
                    return Future.failedFuture(new RuntimeException("transform failed"));
                }
            };
            ResponsePipeline p = new ResponsePipeline(Set.of(), List.of(failingTransform), serializer);
            when(httpResponse.ended()).thenReturn(false);
            when(httpResponse.end(anyString())).thenReturn(Future.succeededFuture());

            p.handle(ctx, null);

            verify(httpResponse).setStatusCode(500);
            verify(httpResponse).end(contains("about:blank"));
            // serializer must NOT be called on the bare-metal path
            verify(serializer, never()).serialize(any(), any());
        }

        @Test
        @DisplayName("normal success path still fires afterResponse exactly once (no double-fire)")
        void normalPathFiresAfterResponseExactlyOnce() {
            List<Response> observed = new ArrayList<>();
            RequestInterceptor observer = new RequestInterceptor() {
                @Override
                public void afterResponse(RoutingContext rc, Response response) {
                    observed.add(response);
                }
            };
            ResponsePipeline p = new ResponsePipeline(Set.of(), List.of(observer), serializer);

            p.handle(ctx, null); // null → 204 No Content

            assertEquals(1, observed.size(), "afterResponse must fire exactly once on the success path");
            assertEquals(204, observed.get(0).getStatus());
        }

        @Test
        @DisplayName("normal error-mapped path still fires afterResponse exactly once (no double-fire)")
        void errorMappedPathFiresAfterResponseExactlyOnce() {
            List<Response> observed = new ArrayList<>();
            RequestInterceptor observer = new RequestInterceptor() {
                @Override
                public void afterResponse(RoutingContext rc, Response response) {
                    observed.add(response);
                }
            };
            ResponsePipeline p = new ResponsePipeline(Set.of(), List.of(observer), serializer);
            Response errorResponse = Response.status(503).entity("unavailable").build();

            p.sendResponse(ctx, errorResponse);

            assertEquals(1, observed.size(), "afterResponse must fire exactly once on the error-mapped path");
            assertEquals(503, observed.get(0).getStatus());
        }
    }

    // --- Wire-completion observation (post-handoff) ---

    /**
     * Tests for the pipeline's observation of the serializer's wire-completion future. A failure
     * reported through that future happened <em>after</em> the response was handed off to the wire:
     * the pipeline records it under {@link RestRequestCompletionEmitter#KEY_WIRE_FAILURE}, logs the
     * cause class only, and performs guarded terminal cleanup — it never writes a bare 500, never
     * re-fires {@code afterResponse}, and never re-enters {@code sendFallback500}.
     */
    @Nested
    @DisplayName("wire-completion observation")
    class WireCompletionObservation {

        private Logger pipelineLogger;
        private Level previousLevel;
        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void capturePipelineLogs() {
            pipelineLogger = (Logger) LoggerFactory.getLogger(ResponsePipeline.class);
            previousLevel = pipelineLogger.getLevel();
            pipelineLogger.setLevel(Level.WARN);
            appender = new ListAppender<>();
            appender.start();
            pipelineLogger.addAppender(appender);
        }

        @AfterEach
        void releasePipelineLogs() {
            pipelineLogger.detachAppender(appender);
            appender.stop();
            pipelineLogger.setLevel(previousLevel);
        }

        /**
         * Returns the formatted WARN messages the pipeline emitted for a post-handoff wire failure.
         *
         * @return the wire-failure WARN messages, in emission order
         */
        private List<String> wireFailureWarnings() {
            return appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("Wire write failed"))
                    .toList();
        }

        /**
         * Stubs the serializer to hand back a pending completion future and marks the HTTP response
         * as not yet ended, matching the state the pipeline observes immediately after wire handoff.
         *
         * @return the promise backing the serializer's completion future, for the test to settle
         */
        private Promise<Void> pendingWireCompletion() {
            Promise<Void> wire = Promise.promise();
            when(serializer.serialize(any(), any())).thenReturn(wire.future());
            when(httpResponse.ended()).thenReturn(false);
            return wire;
        }

        @Test
        @DisplayName("A wire failure after handoff sets the marker and logs the cause class, never its message")
        void wireFailureAfterHandoffSetsMarkerAndLogsClassOnly() {
            // given a response handed off to a serializer whose completion future is still pending
            Promise<Void> wire = pendingWireCompletion();
            IllegalStateException cause = new IllegalStateException("secret-token-42 leaked into the message");

            pipeline.sendResponse(ctx, Response.ok("body").build());

            // when the wire write fails after the handoff
            wire.fail(cause);

            // then the failure is recorded on the routing context and logged class-only
            assertSame(
                    cause,
                    ctx.data().get(RestRequestCompletionEmitter.KEY_WIRE_FAILURE),
                    "the post-handoff wire failure must be recorded under KEY_WIRE_FAILURE");
            List<String> warnings = wireFailureWarnings();
            assertEquals(1, warnings.size(), "exactly one WARN must report the wire failure");
            assertTrue(
                    warnings.get(0).contains("IllegalStateException"),
                    "the WARN must name the cause class: " + warnings.get(0));
            assertFalse(
                    warnings.get(0).contains("secret-token-42"),
                    "the WARN must never carry the raw cause message: " + warnings.get(0));
        }

        @Test
        @DisplayName("An already-failed completion future ends the response without hooks or a bare 500")
        void immediateFailedFutureEndsResponseWithoutHooks() {
            // given a serializer that fails its completion future before returning
            RuntimeException cause = new RuntimeException("pipe failed on resume");
            when(serializer.serialize(any(), any())).thenReturn(Future.failedFuture(cause));
            when(httpResponse.ended()).thenReturn(false);
            List<Response> observed = new ArrayList<>();
            RequestInterceptor observer = new RequestInterceptor() {
                @Override
                public void afterResponse(RoutingContext rc, Response response) {
                    observed.add(response);
                }
            };
            ResponsePipeline p = new ResponsePipeline(Set.of(), List.of(observer), serializer);

            // when the response is sent
            p.sendResponse(ctx, Response.ok("body").build());

            // then the pipeline records the failure and owns terminal cleanup only
            assertSame(
                    cause,
                    ctx.data().get(RestRequestCompletionEmitter.KEY_WIRE_FAILURE),
                    "an immediately-failed completion future must still set the marker");
            verify(httpResponse).end();
            assertEquals(1, observed.size(), "afterResponse must fire exactly once, at wire handoff");
            assertEquals(200, observed.get(0).getStatus(), "the single afterResponse reflects the handed-off outcome");
            verify(httpResponse, never()).setStatusCode(500);
            verify(httpResponse, never()).end(anyString());
        }

        @Test
        @DisplayName("A completion future settled off-context redispatches cleanup onto the request context")
        void workerThreadCompletionRedispatchesToRequestContext() throws Exception {
            Vertx vertx = Vertx.vertx();
            try {
                // given a response handed off on a request context, with a context-free completion future
                Promise<Void> wire = pendingWireCompletion();
                AtomicReference<Context> cleanupContext = new AtomicReference<>();
                CountDownLatch cleanupDone = new CountDownLatch(1);
                when(httpResponse.end()).thenAnswer(invocation -> {
                    cleanupContext.set(Vertx.currentContext());
                    cleanupDone.countDown();
                    return Future.succeededFuture();
                });
                Context requestContext = vertx.getOrCreateContext();
                CountDownLatch handedOff = new CountDownLatch(1);
                requestContext.runOnContext(v -> {
                    pipeline.sendResponse(ctx, Response.ok("body").build());
                    handedOff.countDown();
                });
                assertTrue(handedOff.await(5, TimeUnit.SECONDS), "the response must reach wire handoff");

                // when the completion future fails on a foreign (non-Vert.x) thread
                RuntimeException cause = new RuntimeException("client reset observed off-context");
                Thread completer = new Thread(() -> wire.fail(cause), "wire-completion");
                completer.start();
                completer.join();

                // then marker and terminal cleanup both run back on the captured request context
                assertTrue(cleanupDone.await(5, TimeUnit.SECONDS), "terminal cleanup must run after a wire failure");
                assertSame(
                        requestContext,
                        cleanupContext.get(),
                        "terminal cleanup must be redispatched onto the captured request context");
                assertSame(
                        cause,
                        ctx.data().get(RestRequestCompletionEmitter.KEY_WIRE_FAILURE),
                        "the marker must be set on the request context, not the completing thread");
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }

        @Test
        @DisplayName("A truncated fixed-length response is reset, never ended into a framing violation")
        void underLengthFixedResponseResetInsteadOfEnded() {
            // given a committed, non-chunked response that declared more bytes than it has written
            Promise<Void> wire = pendingWireCompletion();
            when(httpResponse.headWritten()).thenReturn(true);
            when(httpResponse.isChunked()).thenReturn(false);
            when(httpResponse.bytesWritten()).thenReturn(10L);
            responseHeaders.set(HttpHeaders.CONTENT_LENGTH, "100");

            pipeline.sendResponse(ctx, Response.ok("body").build());

            // when the wire write fails after the handoff
            wire.fail(new RuntimeException("stream aborted mid-body"));

            // then the stream is reset — ending would frame 10 bytes as a complete 100-byte body
            verify(httpResponse).reset();
            verify(httpResponse, never()).end();
        }

        @Test
        @DisplayName("A fixed-length response that fails before its head is written is reset, not ended")
        void preHeadFixedLengthFailureResets() {
            // given an uncommitted, non-chunked response that declared 100 bytes and wrote none:
            // Vert.x preserves the explicit Content-Length, so a clean end() would ship a zero-byte
            // response advertised as a complete 100-byte body
            Promise<Void> wire = pendingWireCompletion();
            when(httpResponse.headWritten()).thenReturn(false);
            when(httpResponse.isChunked()).thenReturn(false);
            when(httpResponse.bytesWritten()).thenReturn(0L);
            responseHeaders.set(HttpHeaders.CONTENT_LENGTH, "100");

            pipeline.sendResponse(ctx, Response.ok("body").build());

            // when the wire write fails before the head reached the client
            wire.fail(new RuntimeException("connection died before the head flushed"));

            // then the stream is reset — an uncommitted head does not make the framing honest
            verify(httpResponse).reset();
            verify(httpResponse, never()).end();
        }

        @Test
        @DisplayName("A fixed-length response that over-wrote its declared length is reset")
        void overLengthFixedResponseResets() {
            // given a committed, non-chunked response that wrote more bytes than it declared —
            // surplus bytes desync a keep-alive connection exactly as a short body does
            Promise<Void> wire = pendingWireCompletion();
            when(httpResponse.headWritten()).thenReturn(true);
            when(httpResponse.isChunked()).thenReturn(false);
            when(httpResponse.bytesWritten()).thenReturn(20L);
            responseHeaders.set(HttpHeaders.CONTENT_LENGTH, "10");

            pipeline.sendResponse(ctx, Response.ok("body").build());

            // when the wire write fails after the handoff
            wire.fail(new RuntimeException("stream aborted mid-body"));

            // then the stream is reset — 20 bytes cannot be framed as the declared 10
            verify(httpResponse).reset();
            verify(httpResponse, never()).end();
        }

        @Test
        @DisplayName("An unparseable declared Content-Length fails closed and resets the stream")
        void malformedDeclaredLengthFailsClosed() {
            // given a declared length the guard cannot reason about at all
            Promise<Void> wire = pendingWireCompletion();
            when(httpResponse.headWritten()).thenReturn(true);
            when(httpResponse.isChunked()).thenReturn(false);
            when(httpResponse.bytesWritten()).thenReturn(10L);
            responseHeaders.set(HttpHeaders.CONTENT_LENGTH, "banana");

            pipeline.sendResponse(ctx, Response.ok("body").build());

            // when the wire write fails after the handoff
            wire.fail(new RuntimeException("stream aborted mid-body"));

            // then the unverifiable framing is treated as unsatisfiable, not waved through
            verify(httpResponse).reset();
            verify(httpResponse, never()).end();
        }

        @Test
        @DisplayName("A fixed-length response whose bytes exactly match the declared length is ended cleanly")
        void exactLengthCompletedResponseEndsCleanly() {
            // given a committed, non-chunked response whose written bytes satisfy the declared
            // length exactly — the boundary the reset guard must not cross
            Promise<Void> wire = pendingWireCompletion();
            when(httpResponse.headWritten()).thenReturn(true);
            when(httpResponse.isChunked()).thenReturn(false);
            when(httpResponse.bytesWritten()).thenReturn(10L);
            responseHeaders.set(HttpHeaders.CONTENT_LENGTH, "10");

            pipeline.sendResponse(ctx, Response.ok("body").build());

            // when the wire write fails after the handoff
            wire.fail(new RuntimeException("failure reported after the last byte was written"));

            // then the honestly-framed response is ended, never reset
            verify(httpResponse).end();
            verify(httpResponse, never()).reset();
        }

        @Test
        @DisplayName("A reset that fails falls back to closing the HTTP/1 connection")
        void resetFailureFallsBackToConnectionCloseOnHttp1() {
            // given a truncated fixed-length HTTP/1.1 response whose reset cannot be delivered
            Promise<Void> wire = pendingWireCompletion();
            when(httpResponse.headWritten()).thenReturn(true);
            when(httpResponse.isChunked()).thenReturn(false);
            when(httpResponse.bytesWritten()).thenReturn(10L);
            responseHeaders.set(HttpHeaders.CONTENT_LENGTH, "100");
            when(httpResponse.reset()).thenReturn(Future.failedFuture(new RuntimeException("reset not delivered")));
            HttpConnection connection = mock(HttpConnection.class);
            when(ctx.request().version()).thenReturn(HttpVersion.HTTP_1_1);
            when(ctx.request().connection()).thenReturn(connection);

            pipeline.sendResponse(ctx, Response.ok("body").build());

            // when the wire write fails after the handoff
            wire.fail(new RuntimeException("stream aborted mid-body"));

            // then the connection carrying the un-framable response is closed as the last resort
            verify(httpResponse).reset();
            verify(connection).close();
        }

        @Test
        @DisplayName("A reset that fails on HTTP/2 never closes the connection carrying sibling streams")
        void resetFailureOnHttp2LeavesConnectionOpen() {
            // given the same failed reset on an HTTP/2 stream
            Promise<Void> wire = pendingWireCompletion();
            when(httpResponse.headWritten()).thenReturn(true);
            when(httpResponse.isChunked()).thenReturn(false);
            when(httpResponse.bytesWritten()).thenReturn(10L);
            responseHeaders.set(HttpHeaders.CONTENT_LENGTH, "100");
            when(httpResponse.reset()).thenReturn(Future.failedFuture(new RuntimeException("reset not delivered")));
            HttpConnection connection = mock(HttpConnection.class);
            when(ctx.request().version()).thenReturn(HttpVersion.HTTP_2);
            when(ctx.request().connection()).thenReturn(connection);

            pipeline.sendResponse(ctx, Response.ok("body").build());

            // when the wire write fails after the handoff
            wire.fail(new RuntimeException("stream aborted mid-body"));

            // then the multiplexed connection is left alone — closing it would kill sibling streams
            verify(httpResponse).reset();
            verify(connection, never()).close();
        }

        @Test
        @DisplayName("A truncated chunked response is still ended cleanly and never reset")
        void chunkedTruncatedResponseStillEndedCleanly() {
            // given a committed chunked response — its terminal zero-length chunk frames the
            // truncation honestly, so no reset is warranted
            Promise<Void> wire = pendingWireCompletion();
            when(httpResponse.headWritten()).thenReturn(true);
            when(httpResponse.isChunked()).thenReturn(true);

            pipeline.sendResponse(ctx, Response.ok("body").build());

            // when the wire write fails after the handoff
            wire.fail(new RuntimeException("stream aborted mid-body"));

            // then the response is ended, not reset
            verify(httpResponse).end();
            verify(httpResponse, never()).reset();
        }

        @Test
        @DisplayName("A terminal end() whose future fails resets the response as a backstop")
        void endFailureBackstopResets() {
            // given a response whose guarded terminal end() cannot complete (the connection is gone)
            Promise<Void> wire = pendingWireCompletion();
            when(httpResponse.end()).thenReturn(Future.failedFuture(new RuntimeException("connection gone")));

            pipeline.sendResponse(ctx, Response.ok("body").build());

            // when the wire write fails after the handoff
            wire.fail(new RuntimeException("stream aborted mid-body"));

            // then the response that could not be ended is not left open
            verify(httpResponse).end();
            verify(httpResponse).reset();
        }

        @Test
        @DisplayName("A terminal end() that throws (already-written race) is guarded and does not escape")
        void throwingTerminalEndGuarded() {
            // given a response whose terminal end() loses an already-written race and throws
            Promise<Void> wire = pendingWireCompletion();
            when(httpResponse.end()).thenThrow(new IllegalStateException("Response has already been written"));
            RuntimeException cause = new RuntimeException("stream aborted mid-body");
            pipeline.sendResponse(ctx, Response.ok("body").build());

            // when the wire write fails after the handoff
            assertDoesNotThrow(() -> wire.fail(cause), "a guarded terminal end() must never escape the observer");

            // then the failure is still recorded and the end attempt was made
            assertSame(
                    cause,
                    ctx.data().get(RestRequestCompletionEmitter.KEY_WIRE_FAILURE),
                    "a throwing terminal end() must not prevent the marker from being recorded");
            verify(httpResponse).end();
        }

        @Test
        @DisplayName("The error fail-open retry's completion future is the one the pipeline observes")
        void errorPathFailOpenRetryReturnsRetryFuture() {
            // given an error response whose profile mapper throws once, then serializes on retry
            when(ctx.get(ResponsePipeline.KEY_ERROR_RESPONSE)).thenReturn(Boolean.TRUE);
            when(httpResponse.ended()).thenReturn(false);
            when(httpResponse.headWritten()).thenReturn(false);
            Promise<Void> retryWire = Promise.promise();
            when(serializer.serialize(any(), any()))
                    .thenThrow(new EncodeException("profile mapper failed to encode the error body"))
                    .thenReturn(retryWire.future());
            RuntimeException cause = new RuntimeException("client vanished during the retry write");

            pipeline.sendResponse(ctx, Response.status(409).entity("err").build());

            // when the retry's wire write fails after the handoff
            retryWire.fail(cause);

            // then the pipeline observed the retry's future, not the throw
            verify(serializer, times(2)).serialize(eq(ctx), any(Response.class));
            assertSame(
                    cause,
                    ctx.data().get(RestRequestCompletionEmitter.KEY_WIRE_FAILURE),
                    "the fail-open retry's completion future must be the observed one");
            verify(httpResponse, never()).setStatusCode(500);
        }

        @Test
        @DisplayName("The bare-metal 500 fallback observes its own end() future")
        void sendFallback500EndFutureObserved() {
            // given a transform-chain failure whose bare-metal 500 write fails on the wire
            RuntimeException cause = new RuntimeException("connection closed before the fallback flushed");
            when(httpResponse.ended()).thenReturn(false);
            when(httpResponse.headWritten()).thenReturn(false);
            when(httpResponse.end(anyString())).thenReturn(Future.failedFuture(cause));
            RequestInterceptor failingTransform = new RequestInterceptor() {
                @Override
                public Future<Response> transformResponse(RoutingContext rc, Response response) {
                    return Future.failedFuture(new RuntimeException("transform failed"));
                }
            };
            ResponsePipeline p = new ResponsePipeline(Set.of(), List.of(failingTransform), serializer);

            // when the fallback 500 is written
            p.sendResponse(ctx, Response.ok("body").build());

            // then its failed end() future is observed: logged always, marker best-effort
            assertEquals(1, wireFailureWarnings().size(), "the failed fallback-500 write must be logged");
            assertSame(
                    cause,
                    ctx.data().get(RestRequestCompletionEmitter.KEY_WIRE_FAILURE),
                    "the fallback-500 end() failure must be recorded under KEY_WIRE_FAILURE");
        }
    }
}
