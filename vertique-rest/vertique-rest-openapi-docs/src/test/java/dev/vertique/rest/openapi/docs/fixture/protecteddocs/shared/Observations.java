// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared;

import dev.vertique.rest.core.events.HttpRequestCompletedEvent;
import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.CredentialRejectedEvent;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything one component's fixtures observe, in one component-scoped object.
 *
 * <ul>
 *   <li><b>Traces.</b> Each probe and the application contributor append their name to the trace of
 *       the request whose {@value #REQUEST_HEADER} header carries the trace key, in the order they
 *       ran. A request that no contributor reached has an empty trace.
 *   <li><b>Registered operations.</b> At registration each probe records the {@link
 *       RestOperationDescriptor} it was handed, per operation id, deduplicated by identity: when every
 *       probe received the same instance, the list holds exactly one descriptor. These are never
 *       reset.
 *   <li><b>Events.</b> The security events ({@link CredentialRejectedEvent} and {@link
 *       AuthorizationDecisionEvent}, in arrival order) and the completion events, read and cleared
 *       together by {@link #drain()}.
 *   <li><b>Barriers.</b> A test registers a barrier for a trace key before it sends the request; the
 *       barrier middleware completes it once the request's lifecycle closed, after every end handler
 *       (completion listeners included) ran, so a {@link #drain()} after the barrier holds exactly
 *       that request's events when requests are sent one at a time.
 *   <li><b>Counters</b> of the later mount's routes, its catch-all route (and how many of its
 *       invocations saw an authenticated user), its failure handler, and the error interceptor.
 * </ul>
 */
public final class Observations {

    /** The request header carrying the trace key of a request. */
    public static final String REQUEST_HEADER = "X-Probe-Request";

    private final Map<String, List<String>> traces = new ConcurrentHashMap<>();
    private final Map<String, List<RestOperationDescriptor>> operations = new ConcurrentHashMap<>();
    private final List<Object> securityEvents = new CopyOnWriteArrayList<>();
    private final List<RestRequestCompletedEvent> restEvents = new CopyOnWriteArrayList<>();
    private final List<HttpRequestCompletedEvent> httpEvents = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<Void>> barriers = new ConcurrentHashMap<>();

    private final AtomicInteger laterMountRouteHits = new AtomicInteger();
    private final AtomicInteger laterMountManagementRouteHits = new AtomicInteger();
    private final AtomicInteger laterMountCatchAllHits = new AtomicInteger();
    private final AtomicInteger laterMountCatchAllUserHits = new AtomicInteger();
    private final AtomicInteger laterMountFailureHandlerHits = new AtomicInteger();
    private final AtomicInteger errorInterceptorHits = new AtomicInteger();

    /** Creates an empty observation hub. */
    public Observations() {}

    /**
     * The events one request produced.
     *
     * @param security   the security events in arrival order: {@link CredentialRejectedEvent} and
     *                   {@link AuthorizationDecisionEvent} instances
     * @param rest       the REST completion events
     * @param http       the HTTP completion events
     */
    public record Drained(
            List<Object> security, List<RestRequestCompletedEvent> rest, List<HttpRequestCompletedEvent> http) {}

    // --- Traces ---

    /**
     * Appends a step to the trace of the request, when it carries a trace key.
     *
     * @param ctx  the request
     * @param step the step's name
     */
    void appendTrace(RoutingContext ctx, String step) {
        String key = ctx.request().getHeader(REQUEST_HEADER);
        if (key != null) {
            traces.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(step);
        }
    }

    /**
     * Returns the trace of a request.
     *
     * @param key the request's trace key
     * @return a copy of the steps in the order they ran; empty when no step ran
     */
    public List<String> trace(String key) {
        List<String> trace = traces.get(key);
        if (trace == null) {
            return List.of();
        }
        synchronized (trace) {
            return List.copyOf(trace);
        }
    }

    // --- Registered operations ---

    /**
     * Records the descriptor a probe was handed at registration, unless that very instance is already
     * recorded for its operation id.
     *
     * @param operationId the operation id the registration context names
     * @param descriptor  the descriptor the registration context carries
     */
    void recordOperation(String operationId, RestOperationDescriptor descriptor) {
        List<RestOperationDescriptor> recorded =
                operations.computeIfAbsent(operationId, k -> new CopyOnWriteArrayList<>());
        synchronized (recorded) {
            if (recorded.stream().noneMatch(existing -> existing == descriptor)) {
                recorded.add(descriptor);
            }
        }
    }

    /**
     * Returns the descriptors the probes were handed for an operation id, distinct by identity.
     *
     * @param operationId the operation id
     * @return a copy of the recorded descriptors; empty when none was recorded
     */
    public List<RestOperationDescriptor> operations(String operationId) {
        List<RestOperationDescriptor> recorded = operations.get(operationId);
        return recorded == null ? List.of() : List.copyOf(recorded);
    }

    // --- Events ---

    /**
     * Records a security event.
     *
     * @param event a {@link CredentialRejectedEvent} or an {@link AuthorizationDecisionEvent}
     */
    void recordSecurityEvent(Object event) {
        securityEvents.add(event);
    }

    /**
     * Records a REST completion event.
     *
     * @param event the event
     */
    void recordRestEvent(RestRequestCompletedEvent event) {
        restEvents.add(event);
    }

    /**
     * Records an HTTP completion event.
     *
     * @param event the event
     */
    void recordHttpEvent(HttpRequestCompletedEvent event) {
        httpEvents.add(event);
    }

    /**
     * Returns every event recorded since the last drain and clears them.
     *
     * @return the drained events
     */
    public synchronized Drained drain() {
        List<Object> security = List.copyOf(securityEvents);
        List<RestRequestCompletedEvent> rest = List.copyOf(restEvents);
        List<HttpRequestCompletedEvent> http = List.copyOf(httpEvents);
        securityEvents.removeAll(security);
        restEvents.removeAll(rest);
        httpEvents.removeAll(http);
        return new Drained(security, rest, http);
    }

    // --- Barriers ---

    /**
     * Registers the barrier of a request about to be sent.
     *
     * @param key the request's trace key
     * @return the barrier, completed once the request's lifecycle closed
     */
    public CompletableFuture<Void> expect(String key) {
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        barriers.put(key, barrier);
        return barrier;
    }

    /**
     * Returns the barrier registered for a trace key.
     *
     * @param key the trace key
     * @return the barrier, or {@code null} when none is registered
     */
    CompletableFuture<Void> barrier(String key) {
        return barriers.get(key);
    }

    // --- Counters ---

    void laterMountRouteHit() {
        laterMountRouteHits.incrementAndGet();
    }

    void laterMountManagementRouteHit() {
        laterMountManagementRouteHits.incrementAndGet();
    }

    void laterMountCatchAllHit(boolean userSet) {
        laterMountCatchAllHits.incrementAndGet();
        if (userSet) {
            laterMountCatchAllUserHits.incrementAndGet();
        }
    }

    void laterMountFailureHandlerHit() {
        laterMountFailureHandlerHits.incrementAndGet();
    }

    void errorInterceptorHit() {
        errorInterceptorHits.incrementAndGet();
    }

    /**
     * Returns how many times any route of the later mount ran.
     *
     * @return the count
     */
    public int laterMountRouteHits() {
        return laterMountRouteHits.get();
    }

    /**
     * Returns how many times the later mount's {@code GET /management/openapi.json} route ran.
     *
     * @return the count
     */
    public int laterMountManagementRouteHits() {
        return laterMountManagementRouteHits.get();
    }

    /**
     * Returns how many times the later mount's catch-all route ran.
     *
     * @return the count
     */
    public int laterMountCatchAllHits() {
        return laterMountCatchAllHits.get();
    }

    /**
     * Returns how many invocations of the later mount's catch-all route saw {@code ctx.user()} set.
     *
     * @return the count
     */
    public int laterMountCatchAllUserHits() {
        return laterMountCatchAllUserHits.get();
    }

    /**
     * Returns how many times the later mount's failure handler ran.
     *
     * @return the count
     */
    public int laterMountFailureHandlerHits() {
        return laterMountFailureHandlerHits.get();
    }

    /**
     * Returns how many times the counting error interceptor ran.
     *
     * @return the count
     */
    public int errorInterceptorHits() {
        return errorInterceptorHits.get();
    }

    /**
     * Clears the traces, events, barriers, and counters. The registered operations are kept: they are
     * recorded once, when the routes are built.
     */
    public synchronized void reset() {
        traces.clear();
        securityEvents.clear();
        restEvents.clear();
        httpEvents.clear();
        barriers.clear();
        laterMountRouteHits.set(0);
        laterMountManagementRouteHits.set(0);
        laterMountCatchAllHits.set(0);
        laterMountCatchAllUserHits.set(0);
        laterMountFailureHandlerHits.set(0);
        errorInterceptorHits.set(0);
    }
}
