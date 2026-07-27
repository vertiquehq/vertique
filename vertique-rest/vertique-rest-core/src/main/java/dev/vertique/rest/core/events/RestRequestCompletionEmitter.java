// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.capture.RestRequestCaptureCoordinator;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContextSnapshot;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.AsyncResult;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * ROOT-scoped middleware that emits a {@link RestRequestCompletedEvent} exactly once per handled
 * request, covering all success and failure paths through the normal HTTP response lifecycle.
 *
 * <p><strong>Protocol-upgrade exclusion.</strong> Successful protocol upgrades (e.g. WebSocket 101)
 * complete out-of-band via {@code RequestContextLifecycle.completeNow()}, which writes the 101
 * response without firing the Vert.x response end handler. As a result
 * {@code ctx.addEndHandler(...)} never runs for a successful upgrade and no
 * {@link RestRequestCompletedEvent} is emitted. Successful upgrades are audited as
 * channel-lifecycle events ({@code CHANNEL_OPENED}) instead. A <em>failed</em> upgrade that ends
 * with an HTTP error response DOES produce a completion event because the error path goes through
 * the normal response end handler.
 *
 * <p>Placement ({@code ORDER = RequestContextLifecycle.ORDER + 5}) is chosen so that:
 * <ul>
 *   <li>This middleware runs after {@link RequestContextLifecycle} (which provides the per-request
 *       handle) but before {@code CorrelationIngressMiddleware} (ORDER + 10). The earlier placement
 *       ensures the end handler is registered even for requests that are short-circuited by the
 *       correlation middleware's REJECT policy.</li>
 *   <li>{@link RequestContextLifecycle} registers its end handler first (at {@code ORDER =
 *       Integer.MIN_VALUE}), and because Vert.x Web fires end handlers in reverse registration
 *       order, its end handler fires <em>last</em>. This means this middleware's end handler fires
 *       <em>before</em> {@link RequestContextLifecycle}'s own end handler — the lifecycle closes its
 *       scopes last — which is why holder-bound values ({@link SecurityContext},
 *       {@link CorrelationContext}) are still accessible at emit time.</li>
 * </ul>
 *
 * <p>Exactly-once guarantee: an idempotent flag ({@code KEY_EMITTED}) is stored on the routing
 * context so that even if the end handler fires more than once, only the first invocation emits an
 * event.
 *
 * <p>Listener isolation: each {@link RestRequestCompletedListener} is invoked in its own
 * {@code try/catch}. A throwing listener is logged at {@code WARN} and does not prevent other
 * listeners from receiving the event or affect the HTTP response.
 *
 * <p>Safety: {@code safeFailureMessage} is intentionally left {@code null}. Raw exception messages
 * may contain SQL errors, upstream service details, or PII and must never be placed in the event
 * directly (§10.3). A future curated source may populate this field.
 */
@Slf4j
@Singleton
public final class RestRequestCompletionEmitter implements Middleware {

    // --- Routing context keys ---

    /** Key under which the request start time ({@link Instant}) is stored on the routing context. */
    static final String KEY_START_TIME = "rest.events.startTime";

    /** Key under which the exactly-once emission flag ({@link Boolean}) is stored. */
    static final String KEY_EMITTED = "rest.events.emitted";

    /**
     * Key under which the captured operationId ({@link String}) is stored by
     * {@link OperationIdCaptureContributor}. Public so that external components such as
     * {@code ResourceMethodInvoker} can read the value without depending on internal keys.
     */
    public static final String KEY_OPERATION_ID = "rest.events.operationId";

    /**
     * Key under which the route template ({@link String}) is stored by
     * {@link OperationIdCaptureContributor}. Public so that external components such as
     * {@code ResourceMethodInvoker} can read the value without depending on internal keys.
     */
    public static final String KEY_ROUTE_TEMPLATE = "rest.events.routeTemplate";

    /** Post-handoff wire-failure marker; value: Throwable; first writer wins. */
    public static final String KEY_WIRE_FAILURE = "vertique.rest.core.events.wireFailure";

    /**
     * Execution order: runs after {@link RequestContextLifecycle} (ORDER = {@link Integer#MIN_VALUE})
     * and before {@code CorrelationIngressMiddleware} (ORDER + 10) so the end handler is registered
     * on all request paths including REJECT short-circuits.
     */
    static final int ORDER = RequestContextLifecycle.ORDER + 5;

    /** No-op {@link AutoCloseable} returned when a scope's open fails or the set is empty. */
    private static final AutoCloseable NO_OP_SCOPE = () -> {};

    // --- Dependencies ---

    private final Optional<SecurityRuntime> securityRuntime;
    private final ContextHolder contextHolder;
    private final Set<RestRequestCompletedListener> listeners;
    private final List<RestRequestCaptureCoordinator> coordinators;
    private final Set<RequestCompletionScope> completionScopes;

    /**
     * Creates the emitter with all required dependencies, including capture coordinators and a
     * set of completion scopes.
     *
     * <p>This is the primary constructor used by Dagger. Coordinators are sorted once at
     * construction time using {@link OrderedExtension#comparator()} so invocation order is
     * deterministic and requires no per-request sorting.
     *
     * @param securityRuntime  the optional security runtime; present when the security module is
     *                         active, empty otherwise
     * @param contextHolder    the request-scoped context holder for reading bound context values
     * @param listeners        the set of safe listeners to notify on each request completion
     * @param coordinators     the set of capture coordinators to invoke after the safe listener set;
     *                         invoked in {@link OrderedExtension} order; may be empty
     * @param completionScopes the set of {@link RequestCompletionScope} implementations opened around
     *                         the listener-dispatch loop; empty when no integrations are bound —
     *                         in that case behavior is identical to the pre-SPI baseline
     */
    @Inject
    public RestRequestCompletionEmitter(
            Optional<SecurityRuntime> securityRuntime,
            ContextHolder contextHolder,
            Set<RestRequestCompletedListener> listeners,
            Set<RestRequestCaptureCoordinator> coordinators,
            Set<RequestCompletionScope> completionScopes) {
        this.securityRuntime = securityRuntime;
        this.contextHolder = contextHolder;
        this.listeners = listeners;
        this.coordinators =
                coordinators.stream().sorted(OrderedExtension.comparator()).toList();
        this.completionScopes = completionScopes;
    }

    /**
     * Convenience constructor for use in tests and other non-Dagger construction sites where no
     * capture coordinators or completion scopes are needed. Delegates to the primary constructor
     * with an empty coordinator set and empty scopes.
     *
     * @param securityRuntime the optional security runtime; present when the security module is
     *                        active, empty otherwise
     * @param contextHolder   the request-scoped context holder for reading bound context values
     * @param listeners       the set of safe listeners to notify on each request completion
     */
    public RestRequestCompletionEmitter(
            Optional<SecurityRuntime> securityRuntime,
            ContextHolder contextHolder,
            Set<RestRequestCompletedListener> listeners) {
        this(securityRuntime, contextHolder, listeners, Set.of(), Set.of());
    }

    /**
     * Convenience constructor for use in tests and other non-Dagger construction sites where
     * capture coordinators are provided but no completion scopes are needed.
     *
     * @param securityRuntime the optional security runtime; present when the security module is
     *                        active, empty otherwise
     * @param contextHolder   the request-scoped context holder for reading bound context values
     * @param listeners       the set of safe listeners to notify on each request completion
     * @param coordinators    the set of capture coordinators to invoke after the safe listener set
     */
    public RestRequestCompletionEmitter(
            Optional<SecurityRuntime> securityRuntime,
            ContextHolder contextHolder,
            Set<RestRequestCompletedListener> listeners,
            Set<RestRequestCaptureCoordinator> coordinators) {
        this(securityRuntime, contextHolder, listeners, coordinators, Set.of());
    }

    /**
     * Returns the execution priority for this middleware.
     *
     * @return {@link RequestContextLifecycle#ORDER} + 5
     */
    @Override
    public int priority() {
        return ORDER;
    }

    /**
     * Returns {@link MiddlewareScope#ROOT} — fires for every request.
     *
     * @return {@link MiddlewareScope#ROOT}
     */
    @Override
    public MiddlewareScope scope() {
        return MiddlewareScope.ROOT;
    }

    /**
     * Records the request start time on the routing context, registers the end handler that will
     * emit the completion event, and delegates to the next handler.
     *
     * @param ctx the current routing context; must not be {@code null}
     */
    @Override
    public void handle(RoutingContext ctx) {
        ctx.put(KEY_START_TIME, Instant.now());
        ctx.addEndHandler(ar -> emit(ctx, ar));
        ctx.next();
    }

    // --- Emission ---

    /**
     * Emits the {@link RestRequestCompletedEvent} exactly once for the given routing context.
     * Protected against double-invocation by an idempotent flag stored on the context.
     *
     * <p>Package-private (not {@code private}) so {@code RestRequestCompletionEmitterTest} can
     * drive it directly with a synthetic {@link AsyncResult} for wire-failure scenarios that
     * cannot be produced deterministically over a real socket (e.g. an HTTP/2-only
     * {@code StreamResetException} on an HTTP/1.1 test server).
     *
     * @param ctx       the routing context for the completed request
     * @param endResult the outcome delivered to the response end handler; not yet consumed by
     *                  this stub — {@code wireFailureCode} is always {@code null} for now
     */
    void emit(RoutingContext ctx, AsyncResult<Void> endResult) {
        // --- Exactly-once guard ---
        if (Boolean.TRUE.equals(ctx.get(KEY_EMITTED))) {
            return;
        }
        ctx.put(KEY_EMITTED, Boolean.TRUE);

        // --- Timing ---
        Instant endTime = Instant.now();
        Instant startTime = ctx.get(KEY_START_TIME);
        if (startTime == null) {
            startTime = endTime;
        }

        // --- Context values (still live: RequestContextLifecycle fires last) ---
        SecurityContext sec = securityRuntime.map(SecurityRuntime::current).orElse(null);
        // Snapshot the security context so the event is isolated from any later rebind of the
        // live holder-bound context (e.g. when the same Vert.x duplicated context is reused for
        // a subsequent request after this one closes).
        SecurityContextSnapshot secSnapshot = sec != null ? sec.snapshot() : null;
        // Snapshot the correlation context immediately so the event is isolated from any later
        // mutation of the live MutableCorrelationContext (e.g. by the next request on the same
        // Vert.x duplicated context after this one closes).
        CorrelationContextSnapshot corr = contextHolder
                .current(CorrelationContext.class)
                .map(CorrelationContext::snapshot)
                .orElse(null);
        Optional<RequestOrigin> origin = sec != null ? sec.origin() : Optional.empty();

        // --- Operation metadata set by OperationIdCaptureContributor ---
        String operationId = ctx.get(KEY_OPERATION_ID);
        String routeTemplate = ctx.get(KEY_ROUTE_TEMPLATE);

        // --- HTTP facts ---
        int status = ctx.response().getStatusCode();
        // failureCode: class name only — safe, low-cardinality, suitable for metric labels.
        String failureCode = ctx.failure() != null ? ctx.failure().getClass().getSimpleName() : null;
        // safeFailureMessage: intentionally null. Raw exception messages are unsafe (§10.3).
        // A future curated source may populate this field via enrichment.
        String safeFailureMessage = null;

        RestRequestCompletedEvent event = new RestRequestCompletedEvent(
                startTime,
                endTime,
                ctx.request().method().name(),
                ctx.request().path(),
                routeTemplate,
                operationId,
                status,
                failureCode,
                safeFailureMessage,
                null,
                secSnapshot,
                corr,
                origin,
                Map.of());

        // --- Fan-out with listener isolation wrapped in composed completion scopes ---
        List<AutoCloseable> opened = openScopesQuietly(ctx);
        try {
            for (RestRequestCompletedListener listener : listeners) {
                try {
                    listener.onCompleted(event);
                } catch (Exception e) {
                    log.warn("RestRequestCompletedListener failed: {}", e.toString(), e);
                }
            }

            // --- Capture coordinator invocation (after safe listeners, in OrderedExtension order) ---
            for (RestRequestCaptureCoordinator coordinator : coordinators) {
                try {
                    coordinator.onRequestCompletion(event, ctx);
                } catch (Exception e) {
                    log.warn("RestRequestCaptureCoordinator failed: {}", e.toString(), e);
                }
            }
        } finally {
            closeScopesQuietly(opened);
        }
    }

    // --- Scope helpers ---

    /**
     * Opens all {@link RequestCompletionScope} implementations in iteration order. Each
     * scope's {@link RequestCompletionScope#open(RoutingContext)} is guarded: if it throws an
     * {@link Exception}, a {@code WARN} is logged for that scope (class name only) and the open
     * is skipped — the remaining scopes are still attempted. Returns a list of only the
     * successfully-opened {@link AutoCloseable}s, in open order, ready for reverse-order close.
     *
     * <p>Returns an empty list immediately when {@link #completionScopes} is empty.
     *
     * @param rc the routing context for the current request
     * @return a list of successfully-opened closeables in open order; never {@code null}
     */
    private List<AutoCloseable> openScopesQuietly(RoutingContext rc) {
        if (completionScopes.isEmpty()) {
            return List.of();
        }
        List<AutoCloseable> opened = new ArrayList<>(completionScopes.size());
        for (RequestCompletionScope scope : completionScopes) {
            try {
                AutoCloseable closeable = scope.open(rc);
                opened.add(closeable);
            } catch (Exception e) {
                log.warn(
                        "RequestCompletionScope.open() failed ({}); listeners will still run",
                        scope.getClass().getSimpleName());
            }
        }
        return opened;
    }

    /**
     * Closes successfully-opened scopes in <em>reverse</em> open order so that scopes
     * bracket correctly (last-opened closes first). Each close is guarded: an {@link Exception}
     * is caught, logged at {@code WARN} (class name only), and processing continues to the next
     * scope. {@code Error}s (e.g. OOM) are not caught and propagate as fatal — consistent with
     * standard event-loop practice.
     *
     * @param opened the list of closeables in the order they were opened; reverse-iterated here
     */
    private void closeScopesQuietly(List<AutoCloseable> opened) {
        // Close in reverse open order
        List<AutoCloseable> reversed = new ArrayList<>(opened);
        Collections.reverse(reversed);
        for (AutoCloseable closeable : reversed) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn(
                        "RequestCompletionScope.close() failed ({})",
                        e.getClass().getSimpleName());
            }
        }
    }
}
