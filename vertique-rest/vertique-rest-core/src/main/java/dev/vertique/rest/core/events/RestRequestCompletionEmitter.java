// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContextSnapshot;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.AsyncResult;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
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
 * ROOT-scoped middleware that emits exactly one completion event for each HTTP request that
 * completes through the normal HTTP response lifecycle, covering all success and failure paths. The
 * transport that claimed the request (see {@link RequestCompletionRecorder}) decides what is
 * emitted:
 * <ul>
 *   <li>a request a JAX-RS operation route claimed produces a {@link RestRequestCompletedEvent}
 *       carrying that route's operation, dispatched to every {@link RestRequestCompletedListener};</li>
 *   <li>a request no transport claimed produces an {@link HttpRequestCompletedEvent}, dispatched to
 *       every {@link HttpRequestCompletedListener};</li>
 *   <li>a request another transport claimed produces neither event, because that transport reports
 *       its completion itself. The emitter logs one {@code DEBUG} line for it, carrying the request
 *       method and status code only, so an unexpected claim is diagnosable.</li>
 * </ul>
 * The transport facts are built once per request and shared by either event type. The
 * {@link RequestCompletionScope}s bracket the dispatch of either event; none opens for a request
 * another transport claimed, because nothing is dispatched for it.
 *
 * <p><strong>Protocol-upgrade exclusion.</strong> Successful protocol upgrades (e.g. WebSocket 101)
 * complete out-of-band via {@code RequestContextLifecycle.completeNow()}, which writes the 101
 * response without firing the Vert.x response end handler. As a result
 * {@code ctx.addEndHandler(...)} never runs for a successful upgrade and no completion event is
 * emitted. The WebSocket transport also claims a successfully upgraded request, as a guard against a
 * late completion driven by the connection's close. Successful upgrades are audited as
 * channel-lifecycle events ({@code CHANNEL_OPENED}) instead. A <em>failed</em> upgrade that ends
 * with an HTTP error response DOES produce a completion event, an {@link HttpRequestCompletedEvent},
 * because the error path goes through the normal response end handler and no transport claimed the
 * request.
 *
 * <p><strong>Placement.</strong> This middleware runs in the {@link ExtensionPhase#SYSTEM_FIRST}
 * phase at {@code ORDER = RequestContextLifecycle.ORDER + 5}, right after
 * {@link RequestContextLifecycle} and ahead of every application-phase ROOT middleware, so that:
 * <ul>
 *   <li>An application ROOT middleware that ends the response without calling {@code next()}, at
 *       any priority, runs only after this middleware has registered its end handler, so the
 *       request still yields exactly one completion event. The same holds for the framework's
 *       application-phase {@code CorrelationIngressMiddleware}
 *       ({@code RequestContextLifecycle.ORDER + 10}): the end handler is registered even for
 *       requests that are short-circuited by its REJECT policy.</li>
 *   <li>When {@link RequestContextLifecycle} is mounted, it runs first and registers its end handler
 *       first (at {@code ORDER = Integer.MIN_VALUE}, in the same phase), and because Vert.x Web fires
 *       end handlers in reverse registration order, its end handler fires <em>last</em>. This
 *       middleware's end handler therefore fires <em>before</em> the lifecycle closes its scopes,
 *       which is why holder-bound values ({@link SecurityContext}, {@link CorrelationContext}) are
 *       still accessible at emit time. Inside the shared phase the priority keeps that order; a
 *       priority tie would not, because the class-name tie-break sorts this class first.</li>
 *   <li>This middleware calls no {@link RequestContextLifecycle} API and does not require it: its
 *       exactly-once emission holds with or without the lifecycle.</li>
 * </ul>
 * The phase is a trusted ordering hint, not a security boundary: a {@code SYSTEM_FIRST} middleware
 * that another framework or platform module orders ahead of this one, and that ends the response,
 * is outside this guarantee.
 *
 * <p>Exactly-once guarantee: on a request's first pass, this middleware creates the request's
 * framework-owned completion state (start time, a compare-and-set emitted flag, and the claim) and
 * registers one end handler whose closure holds it. No public routing-context data key exposes that
 * state. A reroute, or this middleware mounted twice, re-enters on the same request: the state is
 * reused, no second end handler is registered, the first pass's start time is kept, and the claim
 * is cleared so the current pass's routes decide it. Emission reads only the closure-held state and
 * wins its compare-and-set or returns, so even if the end handler fires more than once, only the
 * first invocation emits an event. It runs inline on whichever thread ends the response.
 *
 * <p>Claim: an identity handler that a route registrar installs first on every operation route,
 * ahead of authentication, claims the request with that route's operation
 * ({@link RequestCompletionRecorder#operationRouteHandler}); the last operation route matched in the
 * current pass decides. A transport that reports the request's completion itself claims it through
 * {@link RequestCompletionRecorder#claimForOtherTransport}. Emission reads the claim once, from the
 * state's single (claim, operation) value, so the event type and its operation come from one
 * consistent read. A {@link RestRequestCompletedEvent}'s {@code operation()} is the descriptor
 * instance the identity handler recorded.
 *
 * <p>Listener dispatch: every {@link RestRequestCompletedListener} and
 * {@link HttpRequestCompletedListener} is called through its two-argument
 * {@code onCompleted(event, RoutingContext)} overload, with the request's live root routing
 * context: the context this middleware received on the request's first pass, which its end
 * handler holds. The emitter never calls the one-argument {@code onCompleted(event)} directly; a
 * listener reaches it through the overload's default, which delegates to it. Listeners are
 * unordered.
 *
 * <p>Listener isolation: each listener is invoked in its own {@code try/catch}. A throwing one is
 * logged at {@code WARN} and does not prevent the others from receiving the event or affect the
 * HTTP response. {@link Error}s are not caught and propagate.
 *
 * <p>Safety: {@code safeFailureMessage} is intentionally left {@code null}. Raw exception messages
 * may contain SQL errors, upstream service details, or PII and must never be placed in the event
 * directly (§10.3). A future curated source may populate this field. The request path is carried
 * raw on either event; this middleware never writes it to its log.
 */
@Slf4j
@Singleton
public final class RestRequestCompletionEmitter implements Middleware {

    // --- Routing context keys ---

    /** Post-handoff wire-failure marker; value: Throwable; first writer wins. */
    public static final String KEY_WIRE_FAILURE = "vertique.rest.core.events.wireFailure";

    /**
     * Execution priority inside the {@link ExtensionPhase#SYSTEM_FIRST} phase. It sorts this
     * middleware right after {@link RequestContextLifecycle} (ORDER = {@link Integer#MIN_VALUE}, same
     * phase), so the lifecycle registers its end handler first and that end handler fires after this
     * one's. The priority, not the tie-break, keeps that order: the class-name {@code orderKey}
     * tie-break would sort this middleware first. The {@code SYSTEM_FIRST} phase, not this priority,
     * runs this middleware ahead of every application-phase ROOT middleware, including
     * {@code CorrelationIngressMiddleware} ({@code RequestContextLifecycle.ORDER + 10}, default
     * phase), so the end handler is registered on all request paths, including REJECT
     * short-circuits and an application middleware that ends the response without calling
     * {@code next()}. The phase is a trusted ordering hint, not a security boundary.
     */
    static final int ORDER = RequestContextLifecycle.ORDER + 5;

    // --- Dependencies ---

    private final Optional<SecurityRuntime> securityRuntime;
    private final ContextHolder contextHolder;
    private final Set<RestRequestCompletedListener> listeners;
    private final Set<HttpRequestCompletedListener> httpListeners;
    private final Set<RequestCompletionScope> completionScopes;

    /**
     * Creates the emitter with all its dependencies: both listener sets and the completion scopes.
     * This is the constructor Dagger uses.
     *
     * @param securityRuntime  the optional security runtime; present when the security module is
     *                         active, empty otherwise
     * @param contextHolder    the request-scoped context holder for reading bound context values
     * @param restListeners    the set of {@link RestRequestCompletedListener}s notified for each
     *                         request a JAX-RS operation route claimed; may be empty
     * @param httpListeners    the set of {@link HttpRequestCompletedListener}s notified for each
     *                         request no transport claimed; may be empty
     * @param completionScopes the set of {@link RequestCompletionScope} implementations opened around
     *                         the dispatch of either event type; empty when no integrations are
     *                         bound — in that case behavior is identical to the pre-SPI baseline
     */
    @Inject
    public RestRequestCompletionEmitter(
            Optional<SecurityRuntime> securityRuntime,
            ContextHolder contextHolder,
            Set<RestRequestCompletedListener> restListeners,
            Set<HttpRequestCompletedListener> httpListeners,
            Set<RequestCompletionScope> completionScopes) {
        this.securityRuntime = securityRuntime;
        this.contextHolder = contextHolder;
        this.listeners = restListeners;
        this.httpListeners = httpListeners;
        this.completionScopes = completionScopes;
    }

    /**
     * Convenience constructor for use in tests and other non-Dagger construction sites that need
     * only {@link RestRequestCompletedListener}s. Delegates to the {@code @Inject} constructor with
     * no {@link HttpRequestCompletedListener}s and no completion scopes.
     *
     * @param securityRuntime the optional security runtime; present when the security module is
     *                        active, empty otherwise
     * @param contextHolder   the request-scoped context holder for reading bound context values
     * @param restListeners   the set of {@link RestRequestCompletedListener}s notified for each
     *                        request a JAX-RS operation route claimed
     */
    public RestRequestCompletionEmitter(
            Optional<SecurityRuntime> securityRuntime,
            ContextHolder contextHolder,
            Set<RestRequestCompletedListener> restListeners) {
        this(securityRuntime, contextHolder, restListeners, Set.of(), Set.of());
    }

    /**
     * Returns {@link ExtensionPhase#SYSTEM_FIRST} — the emitter must register its end handler before
     * any application middleware can end the response, so an application ROOT middleware that ends
     * the response without calling {@code next()}, at any priority, still yields exactly one
     * completion event. {@code SYSTEM_FIRST} enforces this independent of priority; inside the phase,
     * {@link #ORDER} sorts the emitter right after {@link RequestContextLifecycle}, whose end handler
     * must fire after this one's. The phase is a trusted ordering hint, not a security boundary: a
     * {@code SYSTEM_FIRST} middleware that another framework or platform module orders ahead of the
     * emitter, and that ends the response, is out of scope.
     *
     * @return {@link ExtensionPhase#SYSTEM_FIRST}
     */
    @Override
    public ExtensionPhase phase() {
        return ExtensionPhase.SYSTEM_FIRST;
    }

    /**
     * Returns the execution priority for this middleware inside the
     * {@link ExtensionPhase#SYSTEM_FIRST} phase.
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
     * Begins the request's completion state, or reuses it on re-entry, and delegates to the next
     * handler.
     *
     * <p>On the request's first pass it creates the framework-owned completion state, starting now
     * and bound to {@code ctx.request()}, and registers the one end handler that emits the
     * completion event from that state. A reroute, or this middleware mounted twice, re-enters on the
     * same request: it finds the state bound to {@code ctx.request()}, reuses it, and registers no
     * second end handler; it keeps the start time and clears the claim, so the current pass decides
     * the claim. A holder that is missing, of another type, or bound to another request is not
     * re-entry: a fresh state replaces it.
     *
     * @param ctx the current routing context; must not be {@code null}
     */
    @Override
    public void handle(RoutingContext ctx) {
        RequestCompletionState reentered = RequestCompletionRecorder.boundState(ctx);
        if (reentered != null) {
            reentered.reroute();
        } else {
            RequestCompletionState state = RequestCompletionRecorder.begin(ctx);
            ctx.addEndHandler(endResult -> emit(ctx, state, endResult));
        }
        ctx.next();
    }

    // --- Emission ---

    /**
     * Emits the request's completion event at most once, for the request whose completion state is
     * {@code state}: the call that wins the state's compare-and-set emitted flag emits, and every
     * other call returns. The start time and the claim come from {@code state} alone, never from
     * routing-context data, and the claim is read once.
     *
     * <p>An {@code OTHER} claim returns first, before any transport fact is built. For any other
     * claim the transport facts are built once, and the claim selects the dispatch:
     * <ul>
     *   <li>{@code REST(op)}: a {@link RestRequestCompletedEvent} carrying {@code op}, to every
     *       {@link RestRequestCompletedListener}, inside the {@link RequestCompletionScope}
     *       bracket;</li>
     *   <li>{@code NONE}: an {@link HttpRequestCompletedEvent}, to every
     *       {@link HttpRequestCompletedListener}, inside the scope bracket;</li>
     *   <li>{@code OTHER}: nothing, no security or correlation snapshot is taken, and no scope
     *       opens; one {@code DEBUG} line, logged only when {@code DEBUG} is enabled, carries the
     *       method and status code, never the path.</li>
     * </ul>
     * Every listener is called through its two-argument {@code onCompleted(event, RoutingContext)}
     * overload, with {@code ctx}.
     *
     * <p>Package-private (not {@code private}) so {@code RestRequestCompletionEmitterTest} can
     * drive it directly with a synthetic {@link AsyncResult} for wire-failure scenarios that
     * cannot be produced deterministically over a real socket (e.g. an HTTP/2-only
     * {@code StreamResetException} on an HTTP/1.1 test server).
     *
     * @param ctx       the request's root routing context, as the end-handler closure holds it;
     *                  every listener receives it
     * @param state     the request's completion state, as the end-handler closure holds it
     * @param endResult the outcome delivered to the response end handler; consulted for
     *                  {@code wireFailureCode} when the {@link #KEY_WIRE_FAILURE} marker is absent
     */
    void emit(RoutingContext ctx, RequestCompletionState state, AsyncResult<Void> endResult) {
        // --- Exactly-once guard ---
        if (!state.markEmitted()) {
            return;
        }

        // --- Claim: one read of the state's single volatile (claim, operation) value ---
        RequestCompletionState.Claim claim = state.claim();

        // --- Another transport reports this request's completion itself: build no facts ---
        if (claim.kind() == RequestCompletionState.ClaimKind.OTHER) {
            logSkippedForOtherTransport(
                    ctx.request().method().name(), ctx.response().getStatusCode());
            return;
        }

        // --- Timing ---
        Instant endTime = Instant.now();
        Instant startTime = state.startTime();

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

        // --- HTTP facts ---
        String method = ctx.request().method().name();
        String path = ctx.request().path();
        int status = ctx.response().getStatusCode();
        // failureCode: class name only — safe, low-cardinality, suitable for metric labels.
        String failureCode = ctx.failure() != null ? ctx.failure().getClass().getSimpleName() : null;
        // safeFailureMessage: intentionally null. Raw exception messages are unsafe (§10.3).
        // A future curated source may populate this field via enrichment.
        String safeFailureMessage = null;
        // wireFailureCode: post-handoff wire-failure classification. The KEY_WIRE_FAILURE marker
        // (streaming failures, set by the response pipeline; first-writer-wins) takes precedence
        // over a failed end-handler result (client aborts).
        Throwable marker = ctx.get(KEY_WIRE_FAILURE);
        String wireFailureCode = wireFailureCode(marker, endResult);

        // --- Dispatch by claim: exactly one event type, or nothing ---
        switch (claim.kind()) {
            case REST ->
                dispatchRest(
                        ctx,
                        new RestRequestCompletedEvent(
                                startTime,
                                endTime,
                                method,
                                path,
                                claim.operation(),
                                status,
                                failureCode,
                                safeFailureMessage,
                                wireFailureCode,
                                secSnapshot,
                                corr,
                                origin,
                                Map.of()));
            case NONE ->
                dispatchHttp(
                        ctx,
                        new HttpRequestCompletedEvent(
                                startTime,
                                endTime,
                                method,
                                path,
                                status,
                                failureCode,
                                safeFailureMessage,
                                wireFailureCode,
                                secSnapshot,
                                corr,
                                origin,
                                Map.of()));
            case OTHER -> {
                // Returned above, before any fact was built; not reachable here.
            }
        }
    }

    /**
     * Dispatches the event of a request a JAX-RS operation route claimed: opens the completion
     * scopes, calls {@code onCompleted(event, ctx)} on every {@link RestRequestCompletedListener} in
     * the set's own order, each isolated, and closes the scopes.
     *
     * @param ctx   the request's root routing context, passed to every listener
     * @param event the request's REST completion event
     */
    private void dispatchRest(RoutingContext ctx, RestRequestCompletedEvent event) {
        List<AutoCloseable> opened = openScopesQuietly(ctx);
        try {
            for (RestRequestCompletedListener listener : listeners) {
                try {
                    listener.onCompleted(event, ctx);
                } catch (Exception e) {
                    log.warn("RestRequestCompletedListener failed: {}", e.toString(), e);
                }
            }
        } finally {
            closeScopesQuietly(opened);
        }
    }

    /**
     * Dispatches the event of a request no transport claimed: opens the completion scopes, calls
     * {@code onCompleted(event, ctx)} on every {@link HttpRequestCompletedListener} in the set's own
     * order, each isolated, and closes the scopes.
     *
     * @param ctx   the request's root routing context, passed to every listener
     * @param event the request's HTTP completion event
     */
    private void dispatchHttp(RoutingContext ctx, HttpRequestCompletedEvent event) {
        List<AutoCloseable> opened = openScopesQuietly(ctx);
        try {
            for (HttpRequestCompletedListener listener : httpListeners) {
                try {
                    listener.onCompleted(event, ctx);
                } catch (Exception e) {
                    log.warn("HttpRequestCompletedListener failed: {}", e.toString(), e);
                }
            }
        } finally {
            closeScopesQuietly(opened);
        }
    }

    /**
     * Logs the one {@code DEBUG} line for a request another transport claimed, which gets no
     * completion event here. It carries the method and status code only, never the path, a header
     * or any other raw request value, and allocates nothing when {@code DEBUG} is disabled.
     *
     * @param method the request method
     * @param status the response status code
     */
    private static void logSkippedForOtherTransport(String method, int status) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "Completion event skipped: request claimed by another transport (method={}, status={})",
                    method,
                    status);
        }
    }

    /**
     * Derives the wire-failure classification for a completed request from the two input
     * channels of the {@code ResponseSerializer} completion contract: the {@link #KEY_WIRE_FAILURE}
     * marker (streaming failures, set by the response pipeline) and the end-handler
     * {@link AsyncResult} (client aborts). The marker takes precedence when both carry a failure;
     * when neither does, returns {@code null} (clean wire completion).
     *
     * @param marker    the {@link #KEY_WIRE_FAILURE} routing-context marker value, or {@code null}
     *                  when absent
     * @param endResult the outcome delivered to the response end handler
     * @return the normalized wire-failure classification, or {@code null} on clean completion
     */
    static String wireFailureCode(@Nullable Throwable marker, AsyncResult<Void> endResult) {
        if (marker != null) {
            return normalizeWireFailureCause(marker);
        }
        if (endResult != null && endResult.failed()) {
            return normalizeWireFailureCause(endResult.cause());
        }
        return null;
    }

    /**
     * Normalizes a wire-failure cause to a low-cardinality classification string safe for use as
     * a metric label: the cause's class simple name, except the Vert.x 5.1.2 connection-close
     * signal — {@link io.vertx.core.impl.NoStackTraceThrowable} with the exact message
     * {@code "Connection closed"} — which normalizes to {@code "ConnectionClosed"}. Matched by
     * class name AND message (not message alone), so an unrelated exception carrying the same
     * text is not misclassified. An HTTP/2 {@code StreamResetException} is intentionally NOT
     * normalized and keeps its own simple class name.
     *
     * <p><strong>Version-coupled:</strong> the exact class name and message are Vert.x 5.1.2
     * internals ({@code io.vertx.core.impl.NoStackTraceThrowable} is not part of the public API);
     * revisit this predicate on a Vert.x upgrade.
     *
     * @param cause the wire-failure cause; never {@code null}
     * @return the normalized classification string; never {@code null}
     */
    private static String normalizeWireFailureCause(Throwable cause) {
        if (cause.getClass().getName().equals("io.vertx.core.impl.NoStackTraceThrowable")
                && "Connection closed".equals(cause.getMessage())) {
            return "ConnectionClosed";
        }
        return cause.getClass().getSimpleName();
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
