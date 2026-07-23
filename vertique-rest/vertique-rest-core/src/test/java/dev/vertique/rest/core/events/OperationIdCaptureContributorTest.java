// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.RouteRegistration;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link OperationIdCaptureContributor}.
 *
 * <p>Verifies (post route-registration migration, FR-023):
 * <ul>
 *   <li>Priority is 350 — post-context range, after authorization and context-bridging bands.</li>
 *   <li>{@link OperationIdCaptureContributor#contribute} adds exactly one handler via
 *       {@link RouteRegistration#addHandler}.</li>
 *   <li>The route template is read from {@link RestOperationDescriptor#routeTemplate()} on the
 *       context's neutral operation descriptor — <em>not</em> from the OpenAPI
 *       {@code Operation#getAbsoluteOpenAPIPath()}.</li>
 *   <li>The added handler stores {@code operationId} under
 *       {@link RestRequestCompletionEmitter#KEY_OPERATION_ID} and the route template under
 *       {@link RestRequestCompletionEmitter#KEY_ROUTE_TEMPLATE} on the routing context.</li>
 *   <li>The added handler calls {@code rc.next()} to pass control to the next handler.</li>
 * </ul>
 */
class OperationIdCaptureContributorTest {

    private final OperationIdCaptureContributor contributor = new OperationIdCaptureContributor();

    // --- Priority ---

    @Test
    @DisplayName("priority() returns 350 — post-context range, after authorization band (100-299)")
    void priorityIs350() {
        assertEquals(350, contributor.priority());
    }

    // --- contribute() ---

    @Nested
    @DisplayName("contribute() — handler registration")
    class ContributeHandlerRegistration {

        @Test
        @DisplayName("contribute() calls route.addHandler() exactly once")
        void contributeCallsAddHandlerOnce() {
            RouteRegistration route = mockRoute();
            OperationRegistrationContext ctx = mockContext("orders.approve", "/orders/{id}/approve", route);

            contributor.contribute(ctx);

            verify(route).addHandler(any());
        }

        @Test
        @DisplayName("added handler stores operationId on routing context under KEY_OPERATION_ID")
        @SuppressWarnings("unchecked")
        void handlerStoresOperationId() {
            String operationId = "orders.approve";
            RouteRegistration route = mockRoute();
            OperationRegistrationContext ctx = mockContext(operationId, "/orders/{id}/approve", route);

            contributor.contribute(ctx);

            // Capture the handler added to the route
            ArgumentCaptor<Handler<RoutingContext>> handlerCaptor = forClass(Handler.class);
            verify(route).addHandler(handlerCaptor.capture());

            // Invoke the handler on a stub routing context
            Map<String, Object> store = new HashMap<>();
            RoutingContext rc = mockRoutingContext(store);
            handlerCaptor.getValue().handle(rc);

            assertEquals(
                    operationId,
                    store.get(RestRequestCompletionEmitter.KEY_OPERATION_ID),
                    "operationId must be stored under KEY_OPERATION_ID");
        }

        @Test
        @DisplayName("added handler stores route template (from descriptor.routeTemplate()) under KEY_ROUTE_TEMPLATE")
        @SuppressWarnings("unchecked")
        void handlerStoresRouteTemplateFromDescriptor() {
            String operationId = "getUser";
            String routeTemplate = "/users/{id}";
            RouteRegistration route = mockRoute();
            OperationRegistrationContext ctx = mockContext(operationId, routeTemplate, route);

            contributor.contribute(ctx);

            ArgumentCaptor<Handler<RoutingContext>> handlerCaptor = forClass(Handler.class);
            verify(route).addHandler(handlerCaptor.capture());

            Map<String, Object> store = new HashMap<>();
            RoutingContext rc = mockRoutingContext(store);
            handlerCaptor.getValue().handle(rc);

            assertEquals(
                    operationId,
                    store.get(RestRequestCompletionEmitter.KEY_OPERATION_ID),
                    "operationId must be stored under KEY_OPERATION_ID");
            assertEquals(
                    routeTemplate,
                    store.get(RestRequestCompletionEmitter.KEY_ROUTE_TEMPLATE),
                    "route template must come from descriptor.routeTemplate() and be stored under KEY_ROUTE_TEMPLATE");
        }

        @Test
        @DisplayName("added handler calls rc.next() to pass control to the next handler in the chain")
        @SuppressWarnings("unchecked")
        void handlerCallsNext() {
            RouteRegistration route = mockRoute();
            OperationRegistrationContext ctx = mockContext("health.check", "/health", route);

            contributor.contribute(ctx);

            ArgumentCaptor<Handler<RoutingContext>> handlerCaptor = forClass(Handler.class);
            verify(route).addHandler(handlerCaptor.capture());

            Map<String, Object> store = new HashMap<>();
            RoutingContext rc = mockRoutingContext(store);
            handlerCaptor.getValue().handle(rc);

            verify(rc).next();
        }
    }

    // --- Pre-dispatch / no NPE ---

    @Test
    @DisplayName("constructor creates instance without throwing — no injected state required")
    void constructorDoesNotThrow() {
        // OperationIdCaptureContributor has a no-arg @Inject constructor; just verify it exists
        OperationIdCaptureContributor c = new OperationIdCaptureContributor();
        assertEquals(350, c.priority(), "priority must still be 350 on a fresh instance");
    }

    // --- Helpers ---

    /**
     * Creates a mock {@link RouteRegistration} whose {@code addHandler} returns itself for fluent
     * chaining.
     *
     * @return a configured mock route registration
     */
    private static RouteRegistration mockRoute() {
        RouteRegistration route = mock(RouteRegistration.class);
        when(route.addHandler(any())).thenReturn(route);
        return route;
    }

    /**
     * Creates a mock {@link OperationRegistrationContext} exposing the given operationId, a neutral
     * {@link RestOperationDescriptor} reporting the given route template, and the given route.
     *
     * @param operationId   the operationId to expose on the context
     * @param routeTemplate the route template the descriptor reports via {@code routeTemplate()}
     * @param route         the route registration to expose
     * @return a configured mock context
     */
    private static OperationRegistrationContext mockContext(
            String operationId, String routeTemplate, RouteRegistration route) {
        RestOperationDescriptor descriptor = mock(RestOperationDescriptor.class);
        when(descriptor.routeTemplate()).thenReturn(routeTemplate);

        OperationRegistrationContext ctx = mock(OperationRegistrationContext.class);
        when(ctx.operationId()).thenReturn(operationId);
        when(ctx.operation()).thenReturn(descriptor);
        when(ctx.route()).thenReturn(route);
        return ctx;
    }

    /**
     * Creates a mock {@link RoutingContext} that records calls to {@code put()} in the provided
     * {@code store} map and stubs {@code next()} as a no-op for verification.
     *
     * @param store the map to populate when {@code rc.put(key, value)} is called
     * @return a configured mock routing context
     */
    private static RoutingContext mockRoutingContext(Map<String, Object> store) {
        RoutingContext rc = mock(RoutingContext.class);
        when(rc.put(any(), any())).thenAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return rc;
        });
        return rc;
    }
}
