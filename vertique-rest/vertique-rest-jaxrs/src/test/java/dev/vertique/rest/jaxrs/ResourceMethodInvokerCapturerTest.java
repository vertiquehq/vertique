// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.capture.HttpOperationMeta;
import dev.vertique.rest.core.capture.RestServerRequestEvidenceCapturer;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.events.RestRequestCompletionEmitter;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying the {@link RestServerRequestEvidenceCapturer} seam in
 * {@link ResourceMethodInvoker}.
 *
 * <p>These tests cover:
 * <ul>
 *   <li>No capturers registered → request handled normally; no evidence keys on {@code ctx.data()}</li>
 *   <li>One capturer registered → {@code captureRequest} is invoked with the correct
 *       {@link HttpOperationMeta} (method, operationId, routeTemplate)</li>
 *   <li>A throwing capturer does not break the request — invocation succeeds and subsequent
 *       capturers still run</li>
 * </ul>
 *
 * <p>Because {@code invokeMethod} is private, these tests reach it via the package-private
 * reflection helper pattern established in {@link ResourceMethodInvokerDispatchTest}.
 */
class ResourceMethodInvokerCapturerTest {

    /** Contract whose default method backs a route on {@link FixtureResource} (issue #630). */
    interface DefaultGreeting {
        default String greetDefault() {
            return "hello default";
        }
    }

    /** Fixture resource used for reflective invocation. */
    static class FixtureResource implements DefaultGreeting {
        public String greetNoArgs() {
            return "hello world";
        }
    }

    // --- Helpers ---

    /**
     * Builds a {@link ResourceMethodInvoker} with the supplied capturers and the minimum
     * collaborators needed to drive the {@code invokeMethod} private method.
     *
     * @param resource  the resource instance to invoke
     * @param method    the method to invoke
     * @param capturers the set of capturers to inject
     * @return a fully constructed invoker
     */
    private static ResourceMethodInvoker invokerFor(
            FixtureResource resource, Method method, Set<RestServerRequestEvidenceCapturer> capturers) {
        ResourceMethodMeta meta = new ResourceMethodMeta(
                resource,
                method,
                "greetNoArgs",
                "GET",
                "/greet",
                List.of(),
                method.getReturnType(),
                false,
                false,
                new dev.vertique.rest.core.security.SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null /* no execution plan */);
        ErrorPipeline errorPipeline = mock(ErrorPipeline.class);
        ResponsePipeline responsePipeline = mock(ResponsePipeline.class);
        return new ResourceMethodInvoker(
                meta,
                List.of(),
                errorPipeline,
                responsePipeline,
                new RestContextResolution(Set.of()),
                List.of(),
                null /* beanValidator */,
                null /* objectProcessor */,
                new ArrayList<>(capturers)
                        .stream()
                                .sorted(dev.vertique.core.extension.OrderedExtension.comparator())
                                .toList(),
                null /* resolvedBodyMapper */);
    }

    /**
     * Invokes the private {@code invokeMethod} via reflection and returns its {@code Future<Object>}.
     *
     * @param invoker the invoker under test
     * @param ctx     the routing context stub
     * @return the result future
     */
    @SuppressWarnings("unchecked")
    private static Future<Object> invokeMethod(ResourceMethodInvoker invoker, RoutingContext ctx) throws Throwable {
        Method invokeMethod = ResourceMethodInvoker.class.getDeclaredMethod("invokeMethod", RoutingContext.class);
        invokeMethod.setAccessible(true);
        return (Future<Object>) invokeMethod.invoke(invoker, ctx);
    }

    /**
     * Builds a minimal {@link RoutingContext} stub with an empty {@code ctx.data()} map and the
     * given routeTemplate stored under {@link RestRequestCompletionEmitter#KEY_ROUTE_TEMPLATE}.
     *
     * @param routeTemplate the route template to place in ctx.data(), or {@code null}
     * @return the configured stub
     */
    private static RoutingContext ctx(String routeTemplate) {
        RoutingContext ctx = mock(RoutingContext.class);
        Map<String, Object> data = new HashMap<>();
        if (routeTemplate != null) {
            data.put(RestRequestCompletionEmitter.KEY_ROUTE_TEMPLATE, routeTemplate);
        }
        when(ctx.data()).thenReturn(data);
        when(ctx.get(RestRequestCompletionEmitter.KEY_ROUTE_TEMPLATE)).thenAnswer(inv -> routeTemplate);

        // Raw-request stubs the reflective path's DefaultBoundRequest binding consults (FR-024).
        io.vertx.core.http.HttpServerRequest request = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(Map.of());
        when(ctx.queryParams()).thenReturn(io.vertx.core.MultiMap.caseInsensitiveMultiMap());
        when(request.headers()).thenReturn(io.vertx.core.MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(Set.of());
        when(ctx.body()).thenReturn(null);
        return ctx;
    }

    // --- Tests ---

    @Nested
    @DisplayName("No-op when no capturers registered")
    class NoCapturer {

        @Test
        @DisplayName("empty capturer set → request succeeds; no evidence keys in ctx.data()")
        void noCapturerSetIsNoOp() throws Throwable {
            FixtureResource resource = new FixtureResource();
            Method method = FixtureResource.class.getMethod("greetNoArgs");

            RoutingContext ctx = ctx("/greet");
            ResourceMethodInvoker invoker = invokerFor(resource, method, Set.of());

            Future<Object> result = invokeMethod(invoker, ctx);
            assertTrue(result.succeeded(), "request must succeed");
            assertEquals("hello world", result.result());

            // No evidence keys must be placed on ctx.data() — the capturer set was empty, and no
            // in-repo capturer stashes evidence under RoutingContext#data() regardless of whether one
            // is registered (GH-118, ADR-0157). The literal string is the former evidence key-constant
            // class's REQUEST_EVIDENCE value, inlined now that the deprecated constants class has been
            // removed.
            assertFalse(
                    ctx.data().containsKey("vertique.audit.capture.http.requestEvidence"),
                    "no vertique.audit.capture.http.* evidence key must be set when no capturers are registered");
        }
    }

    @Nested
    @DisplayName("Capturer invoked with correct meta")
    class CapturerInvoked {

        @Test
        @DisplayName("an interface-default route carries the resource class, not the declaring interface")
        void capturerReceivesResourceClassForDefaultRoute() throws Throwable {
            FixtureResource resource = new FixtureResource();
            Method method = DefaultGreeting.class.getMethod("greetDefault");

            List<HttpOperationMeta> captured = new ArrayList<>();
            RestServerRequestEvidenceCapturer capturer = (ctx, meta) -> captured.add(meta);

            Future<Object> result = invokeMethod(invokerFor(resource, method, Set.of(capturer)), ctx("/greet"));
            assertTrue(result.succeeded(), "request must succeed");

            assertEquals(1, captured.size(), "capturer must be called exactly once");
            HttpOperationMeta meta = captured.get(0);
            assertEquals(DefaultGreeting.class, meta.method().getDeclaringClass());
            assertEquals(FixtureResource.class, meta.resourceClass(), "resourceClass must be the resource's class");
        }

        @Test
        @DisplayName("captureRequest receives the method, operationId, and route template")
        void capturerReceivesCorrectMeta() throws Throwable {
            FixtureResource resource = new FixtureResource();
            Method method = FixtureResource.class.getMethod("greetNoArgs");

            List<HttpOperationMeta> captured = new ArrayList<>();
            RestServerRequestEvidenceCapturer capturer = (ctx, meta) -> captured.add(meta);

            RoutingContext ctx = ctx("/greet");
            ResourceMethodInvoker invoker = invokerFor(resource, method, Set.of(capturer));

            Future<Object> result = invokeMethod(invoker, ctx);
            assertTrue(result.succeeded(), "request must succeed");

            assertEquals(1, captured.size(), "capturer must be called exactly once");
            HttpOperationMeta meta = captured.get(0);
            assertNotNull(meta);
            assertEquals(method, meta.method(), "method must match the resource method");
            assertEquals("greetNoArgs", meta.operationId(), "operationId must match the meta operationId");
            assertEquals("/greet", meta.routeTemplate(), "routeTemplate must be the route's template");
        }

        @Test
        @DisplayName("captureRequest is called once even when both plan and reflective paths run")
        void capturerCalledOnce() throws Throwable {
            FixtureResource resource = new FixtureResource();
            Method method = FixtureResource.class.getMethod("greetNoArgs");

            List<HttpOperationMeta> captured = new ArrayList<>();
            RestServerRequestEvidenceCapturer capturer = (ctx, meta) -> captured.add(meta);

            RoutingContext ctx = ctx(null /* no template in ctx */);
            ResourceMethodInvoker invoker = invokerFor(resource, method, Set.of(capturer));

            invokeMethod(invoker, ctx);
            assertEquals(1, captured.size(), "captureRequest must be invoked exactly once per invokeMethod call");
        }

        @Test
        @DisplayName("the route template comes from the route, not from ctx.data(), which any handler can rewrite")
        void routeTemplateComesFromTheRoute() throws Throwable {
            FixtureResource resource = new FixtureResource();
            Method method = FixtureResource.class.getMethod("greetNoArgs");

            List<HttpOperationMeta> captured = new ArrayList<>();
            RestServerRequestEvidenceCapturer capturer = (ctx, meta) -> captured.add(meta);

            RoutingContext ctx = ctx("/rewritten-by-a-handler");
            ResourceMethodInvoker invoker = invokerFor(resource, method, Set.of(capturer));

            invokeMethod(invoker, ctx);
            assertEquals(1, captured.size());
            assertEquals(
                    "/greet",
                    captured.get(0).routeTemplate(),
                    "capturers must see the route template the registrar validated, not a context value");
        }
    }

    @Nested
    @DisplayName("Throwing capturer does not break request handling")
    class ThrowingCapturer {

        @Test
        @DisplayName("a capturer that throws does not prevent the method from succeeding")
        void throwingCapturerDoesNotBreakRequest() throws Throwable {
            FixtureResource resource = new FixtureResource();
            Method method = FixtureResource.class.getMethod("greetNoArgs");

            RestServerRequestEvidenceCapturer throwing = (ctx, meta) -> {
                throw new RuntimeException("capturer-boom");
            };

            RoutingContext ctx = ctx("/greet");
            ResourceMethodInvoker invoker = invokerFor(resource, method, Set.of(throwing));

            Future<Object> result = invokeMethod(invoker, ctx);
            assertTrue(result.succeeded(), "throwing capturer must not break request handling");
            assertEquals("hello world", result.result());
        }

        @Test
        @DisplayName("a throwing capturer does not prevent subsequent capturers from running")
        void throwingCapturerDoesNotBlockSubsequentCapturers() throws Throwable {
            FixtureResource resource = new FixtureResource();
            Method method = FixtureResource.class.getMethod("greetNoArgs");

            List<HttpOperationMeta> captured = new ArrayList<>();

            // Throwing capturer at lower priority (runs first)
            RestServerRequestEvidenceCapturer throwing = new RestServerRequestEvidenceCapturer() {
                @Override
                public int priority() {
                    return 0;
                }

                @Override
                public void captureRequest(RoutingContext ctx, HttpOperationMeta meta) {
                    throw new RuntimeException("capturer-boom");
                }
            };

            // Capturing capturer at higher priority (runs after)
            RestServerRequestEvidenceCapturer capturing = new RestServerRequestEvidenceCapturer() {
                @Override
                public int priority() {
                    return 10;
                }

                @Override
                public void captureRequest(RoutingContext ctx, HttpOperationMeta meta) {
                    captured.add(meta);
                }
            };

            RoutingContext ctx = ctx("/greet");
            // Build invoker with both capturers pre-sorted by priority
            List<RestServerRequestEvidenceCapturer> sorted = List.of(throwing, capturing);
            ResourceMethodMeta meta = new ResourceMethodMeta(
                    resource,
                    method,
                    "greetNoArgs",
                    "GET",
                    "/greet",
                    List.of(),
                    method.getReturnType(),
                    false,
                    false,
                    new dev.vertique.rest.core.security.SecurityPolicy.None(),
                    new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    null);
            ErrorPipeline errorPipeline = mock(ErrorPipeline.class);
            ResponsePipeline responsePipeline = mock(ResponsePipeline.class);
            ResourceMethodInvoker invoker = new ResourceMethodInvoker(
                    meta,
                    List.of(),
                    errorPipeline,
                    responsePipeline,
                    new RestContextResolution(Set.of()),
                    List.of(),
                    null,
                    null,
                    sorted,
                    null);

            Future<Object> result = invokeMethod(invoker, ctx);
            assertTrue(result.succeeded(), "request must succeed despite throwing capturer");
            assertEquals(1, captured.size(), "subsequent capturer must still be called");
        }
    }
}
