// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.response.ResponseProducerBinding;
import dev.vertique.rest.core.response.ResponseSerializer;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
        pipeline = new ResponsePipeline(Set.of(), List.of(), serializer);

        ctx = mock(RoutingContext.class);
        httpResponse = mock(HttpServerResponse.class);

        var httpRequest = mock(io.vertx.core.http.HttpServerRequest.class);
        when(httpRequest.method()).thenReturn(HttpMethod.GET);
        when(ctx.request()).thenReturn(httpRequest);
        when(ctx.response()).thenReturn(httpResponse);
        when(httpResponse.setStatusCode(anyInt())).thenReturn(httpResponse);
        when(httpResponse.putHeader(anyString(), anyString())).thenReturn(httpResponse);

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
}
