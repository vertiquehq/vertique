// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.jaxrs.runtime.ResourceExecutionPlan;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the CG-010 slice 2 plan-or-reflective dispatch in
 * {@link ResourceMethodInvoker#handle(RoutingContext)}'s private {@code invokeMethod} step.
 *
 * <p>Two paths must be distinguishable from outside the class:
 * <ul>
 *   <li>When {@link ResourceMethodMeta#executionPlan()} is non-null, the plan owns argument
 *       extraction ({@link ResourceExecutionPlan#extractArguments}) and direct invocation
 *       ({@link ResourceExecutionPlan#invoke}); reflective {@code Method.invoke} must NOT fire.</li>
 *   <li>When the plan is null, {@link ParameterExtractor#extractArguments} runs and
 *       {@code meta.method().invoke(...)} is the dispatch primitive.</li>
 * </ul>
 *
 * <p>The dispatch logic is private; these tests reach it via reflection rather than driving the
 * full {@code handle} pipeline (which also exercises interceptors, response pipeline, and
 * error pipeline — out of scope here).
 */
class ResourceMethodInvokerDispatchTest {

    /**
     * Test resource exposing a single public method whose argument types match the test fixture's
     * {@code Object[]} shape.
     */
    static class FixtureResource {
        public String greet(String who) {
            return "hello " + who;
        }

        public Future<String> greetAsync(String who) {
            return Future.succeededFuture("async " + who);
        }

        public String greetNoArgs() {
            return "hello world";
        }
    }

    @Nested
    @DisplayName("invokeMethod — plan-or-reflective dispatch (CG-010 slice 2)")
    class Dispatch {

        @Test
        @DisplayName("execution plan present → plan.extractArguments + plan.invoke; Method.invoke skipped")
        void planPresent_planTakesOver() throws Throwable {
            FixtureResource resource = new FixtureResource();
            Method method = FixtureResource.class.getMethod("greet", String.class);
            ResourceExecutionPlan plan = mock(ResourceExecutionPlan.class);
            Object[] planArgs = new Object[] {"alice"};
            when(plan.extractArguments(any(), any(), any())).thenReturn(planArgs);
            doReturn("hello alice").when(plan).invoke(resource, planArgs);

            ResourceMethodInvoker invoker = invokerFor(resource, method, plan);

            Future<Object> result = invokeMethod(invoker, ctx());
            assertTrue(result.succeeded(), "plan path must succeed");
            assertEquals("hello alice", result.result());
            verify(plan, times(1)).extractArguments(any(), any(), any());
            verify(plan, times(1)).invoke(resource, planArgs);
        }

        @Test
        @DisplayName("execution plan absent → ParameterExtractor.extractArguments + Method.invoke")
        void planAbsent_reflectivePath() throws Throwable {
            FixtureResource resource = new FixtureResource();
            // Use a 0-arg method so the empty-params meta + empty extracted-args array compose
            // cleanly through reflective dispatch without parameter coercion.
            Method method = FixtureResource.class.getMethod("greetNoArgs");

            ResourceMethodInvoker invoker = invokerFor(resource, method, null /* no plan */);

            Future<Object> result = invokeMethod(invoker, ctx());
            assertTrue(result.succeeded(), "reflective path must succeed");
            assertEquals("hello world", result.result());
        }

        @Test
        @DisplayName("plan throws InvocationTargetException → unwraps cause into failed future")
        void planThrowsITE_unwrapped() throws Throwable {
            FixtureResource resource = new FixtureResource();
            Method method = FixtureResource.class.getMethod("greet", String.class);
            ResourceExecutionPlan plan = mock(ResourceExecutionPlan.class);
            when(plan.extractArguments(any(), any(), any())).thenReturn(new Object[] {"x"});
            IllegalStateException cause = new IllegalStateException("boom");
            doThrow(new java.lang.reflect.InvocationTargetException(cause))
                    .when(plan)
                    .invoke(any(), any());

            ResourceMethodInvoker invoker = invokerFor(resource, method, plan);

            Future<Object> result = invokeMethod(invoker, ctx());
            assertTrue(result.failed());
            assertSame(cause, result.cause(), "InvocationTargetException must be unwrapped to its cause");
        }

        @Test
        @DisplayName("Future-returning resource method via plan → Future is returned directly (not wrapped)")
        void planReturnsFuture_passedThrough() throws Throwable {
            FixtureResource resource = new FixtureResource();
            Method method = FixtureResource.class.getMethod("greetAsync", String.class);
            ResourceExecutionPlan plan = mock(ResourceExecutionPlan.class);
            when(plan.extractArguments(any(), any(), any())).thenReturn(new Object[] {"bob"});
            doReturn(Future.succeededFuture("async bob")).when(plan).invoke(resource, new Object[] {"bob"});

            ResourceMethodInvoker invoker = invokerFor(resource, method, plan);

            Future<Object> result = invokeMethod(invoker, ctx());
            assertTrue(result.succeeded());
            assertEquals("async bob", result.result());
        }

        @Test
        @DisplayName("plan-path is selected even with empty parameter list")
        void planPath_zeroArgMethod_noReflectiveCall() throws Throwable {
            FixtureResource resource = new FixtureResource();
            Method method = FixtureResource.class.getMethod("greet", String.class);
            ResourceExecutionPlan plan = mock(ResourceExecutionPlan.class);
            when(plan.extractArguments(any(), any(), any())).thenReturn(new Object[] {"carol"});
            doReturn("hello carol").when(plan).invoke(resource, new Object[] {"carol"});
            // Sanity: also verify that the reflective ParameterExtractor isn't dispatched. We can't
            // mock the field directly (it's package-private), but verifying the plan returned the
            // expected output is sufficient — if the reflective path had run, the result would be
            // "hello null" because the validated request returns no parameters.
            ResourceMethodInvoker invoker = invokerFor(resource, method, plan);

            Future<Object> result = invokeMethod(invoker, ctx());
            assertEquals("hello carol", result.result(), "plan path produced the carol greeting");
            verify(plan, never()).invoke(resource, new Object[] {null});
        }
    }

    @Nested
    @DisplayName("invokeMethod — bound request reuse (Slice 9a fix 2)")
    class BoundRequestReuse {

        @Test
        @DisplayName("pre-stashed BoundRequest is reused, not clobbered by a fresh DefaultBoundRequest")
        void preStashedBoundRequest_isReused() throws Throwable {
            FixtureResource resource = new FixtureResource();
            Method method = FixtureResource.class.getMethod("greetNoArgs");
            ResourceMethodInvoker invoker = invokerFor(resource, method, null /* no plan */);

            RoutingContext ctx = ctx();
            java.util.Map<String, Object> data = new java.util.HashMap<>();
            when(ctx.get(dev.vertique.rest.jaxrs.request.BoundRequest.KEY_META_DATA_BOUND_REQUEST))
                    .thenAnswer(
                            inv -> data.get(dev.vertique.rest.jaxrs.request.BoundRequest.KEY_META_DATA_BOUND_REQUEST));
            org.mockito.Mockito.doAnswer(inv -> {
                        data.put(inv.getArgument(0), inv.getArgument(1));
                        return null;
                    })
                    .when(ctx)
                    .put(
                            org.mockito.ArgumentMatchers.eq(
                                    dev.vertique.rest.jaxrs.request.BoundRequest.KEY_META_DATA_BOUND_REQUEST),
                            any());

            dev.vertique.rest.jaxrs.request.BoundRequest stashed =
                    mock(dev.vertique.rest.jaxrs.request.BoundRequest.class);
            data.put(dev.vertique.rest.jaxrs.request.BoundRequest.KEY_META_DATA_BOUND_REQUEST, stashed);

            Future<Object> result = invokeMethod(invoker, ctx);
            assertTrue(result.succeeded());
            assertSame(
                    stashed,
                    data.get(dev.vertique.rest.jaxrs.request.BoundRequest.KEY_META_DATA_BOUND_REQUEST),
                    "the pre-stashed BoundRequest must remain the one in context");
            // The invoker must never overwrite an already-stashed bound request.
            verify(ctx, never())
                    .put(
                            org.mockito.ArgumentMatchers.eq(
                                    dev.vertique.rest.jaxrs.request.BoundRequest.KEY_META_DATA_BOUND_REQUEST),
                            any());
        }
    }

    // --- Helpers ---

    /**
     * Builds a {@link ResourceMethodInvoker} with the minimum collaborators needed to drive
     * {@code invokeMethod} via reflection. Real {@link ResponsePipeline} / {@link ErrorPipeline}
     * are not exercised here; the interceptor list is empty.
     */
    private static ResourceMethodInvoker invokerFor(
            FixtureResource resource, Method method, ResourceExecutionPlan plan) {
        ResourceMethodMeta meta = new ResourceMethodMeta(
                resource,
                method,
                "op",
                "GET",
                "/op",
                List.of(),
                method.getReturnType(),
                method.getReturnType() == Future.class,
                false,
                new dev.vertique.rest.core.security.SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                plan);
        ErrorPipeline errorPipeline = mock(ErrorPipeline.class);
        ResponsePipeline responsePipeline = mock(ResponsePipeline.class);
        return new ResourceMethodInvoker(
                meta,
                List.of(),
                errorPipeline,
                responsePipeline,
                new RestContextResolution(Set.of()) /* restContextResolution */,
                List.of() /* decoders */,
                null /* beanValidator */,
                null /* objectProcessor */);
    }

    /**
     * Calls the package-private {@code invokeMethod} via reflection. Returns the resulting
     * {@code Future<Object>} so tests can assert success/failure outcomes.
     */
    @SuppressWarnings("unchecked")
    private static Future<Object> invokeMethod(ResourceMethodInvoker invoker, RoutingContext ctx) throws Throwable {
        Method invokeMethod = ResourceMethodInvoker.class.getDeclaredMethod("invokeMethod", RoutingContext.class);
        invokeMethod.setAccessible(true);
        return (Future<Object>) invokeMethod.invoke(invoker, ctx);
    }

    /**
     * Minimal RoutingContext stub providing the raw-request stubs the {@code DefaultBoundRequest}
     * binding consults (FR-024): {@code request()}, {@code pathParams()}, {@code queryParams()},
     * {@code headers()}, {@code cookies()}, and {@code body()}. The invoker builds the
     * {@link dev.vertique.rest.jaxrs.request.BoundRequest} from these and stashes it via
     * {@code ctx.put(...)} for both the generated and reflective paths.
     */
    private static RoutingContext ctx() {
        RoutingContext ctx = mock(RoutingContext.class);
        when(ctx.data()).thenReturn(new java.util.HashMap<>());

        io.vertx.core.http.HttpServerRequest request = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(java.util.Map.of());
        when(ctx.queryParams()).thenReturn(io.vertx.core.MultiMap.caseInsensitiveMultiMap());
        when(request.headers()).thenReturn(io.vertx.core.MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(java.util.Set.of());
        when(ctx.body()).thenReturn(null);
        return ctx;
    }
}
