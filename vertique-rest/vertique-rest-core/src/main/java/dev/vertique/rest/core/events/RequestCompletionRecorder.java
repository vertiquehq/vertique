// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import dev.vertique.rest.core.routing.RestOperationDescriptor;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.PlatformHandler;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * INTERNAL framework seam — HTTP-runtime collaborator consumed by sibling framework modules; not
 * an application contract and outside the maturity promise. An application uses the extension
 * points and configuration this module documents and never names this type.
 *
 * <p>Stateless, non-instantiable recorder of the framework-owned completion state that
 * {@link RestRequestCompletionEmitter} creates for each request and emits from. The state records
 * which transport claims the request and, for a REST operation, which operation:
 * <ul>
 *   <li>{@link #operationRouteHandler(RestOperationDescriptor)} returns the handler that a route
 *       registrar installs first on every operation route, ahead of authentication, so a request
 *       rejected after its route matched still produces a {@link RestRequestCompletedEvent} whose
 *       {@code operation()} is that route's descriptor;</li>
 *   <li>{@link #claimForOtherTransport(RoutingContext)} lets a transport that reports the request's
 *       completion itself claim the request; such a request carries no route identity and produces
 *       no completion event from the emitter;</li>
 *   <li>{@link #recordWireFailure(RoutingContext, Throwable)} records a post-handoff wire failure on
 *       the request's state (first writer wins) so the completion event can classify it, without a
 *       public {@code RoutingContext.data()} key.</li>
 * </ul>
 * A request that neither of them claims is unclaimed, and produces an
 * {@link HttpRequestCompletedEvent}.
 *
 * <p><strong>The holder.</strong> The state is reachable from every routing context of the request,
 * the root context and every sub-router's alike, as the value of a holder in the request's shared
 * routing-context data, under a key private to this package. Every read goes through
 * {@link RoutingContext#get(String)}, so a context without data, such as a mocked one, has no
 * holder. A holder counts only when its value is a completion state bound to the context's own
 * request, compared by identity with {@link RoutingContext#request()}. With no holder, a value of
 * another type, or a state bound to another request, the handler, the claim, and the wire-failure
 * recorder change nothing, and no path ever changes the state of another request. Removing or
 * replacing the holder can therefore at most prevent this request's claim or wire-failure record.
 *
 * <p>Nothing here blocks, performs I/O, reads request data, or fails a request.
 */
public final class RequestCompletionRecorder {

    /** Routing-context data key under which the per-request completion state is held. */
    static final String HOLDER_KEY = "vertique.rest.core.events.completionState";

    private RequestCompletionRecorder() {}

    /**
     * Returns the handler that records {@code operation} as the route identity of each request its
     * route matches. It is a {@link PlatformHandler}, so Vert.x Web runs it ahead of every
     * authentication and user handler on the route; a route registrar installs it as the first
     * handler of every operation route.
     *
     * <p>When the request has a holder, the handler claims the request for REST with
     * {@code operation}. It replaces a REST claim made earlier in the same routing pass, so the last
     * operation route matched decides, and it leaves another transport's claim unchanged. Without a
     * holder it records nothing. Either way it then calls {@link RoutingContext#next()} exactly once.
     * It never fails the request, reads no request data, and allocates nothing per request: the
     * claim value is built once, here.
     *
     * @param operation the operation descriptor of the route the handler is installed on; a request
     *                  the route claims produces a {@link RestRequestCompletedEvent} whose
     *                  {@code operation()} is this instance
     * @return the operation route's identity handler
     * @throws NullPointerException if {@code operation} is {@code null}
     */
    public static PlatformHandler operationRouteHandler(RestOperationDescriptor operation) {
        RequestCompletionState.Claim restClaim = RequestCompletionState.Claim.rest(operation);
        return ctx -> {
            RequestCompletionState state = boundState(ctx);
            if (state != null) {
                state.claimOperation(restClaim);
            }
            ctx.next();
        };
    }

    /**
     * Claims the current request for a transport other than REST, one that reports the request's
     * completion itself. Call it where the transport commits to that completion.
     *
     * <p>{@link RestRequestCompletionEmitter} emits no completion event for a request claimed this
     * way: neither a {@link RestRequestCompletedEvent} nor an {@link HttpRequestCompletedEvent}, and
     * no {@link RequestCompletionScope} opens for it. The claiming transport reports the completion
     * through its own events.
     *
     * <p>The claim applies only to an unclaimed request: once an operation route or another transport
     * has claimed the request, the call changes nothing. Without a holder bound to the request it is
     * a no-op. A request claimed this way carries no route identity, and a reroute clears the claim.
     * Non-blocking; never throws.
     *
     * @param ctx the routing context of the request
     */
    public static void claimForOtherTransport(RoutingContext ctx) {
        RequestCompletionState state = boundState(ctx);
        if (state != null) {
            state.claimForOtherTransport();
        }
    }

    /**
     * Records a post-handoff wire failure on the current request's framework-owned completion state,
     * first writer wins. Call it where the response pipeline (or an equivalent framework writer)
     * observes that the wire write failed after handoff.
     *
     * <p>{@link RestRequestCompletionEmitter} reads the recorded cause when building
     * {@code wireFailureCode}, ahead of a failed response end-handler result. Without a holder bound
     * to the request this is a no-op — the same best-effort carve-out as a late {@code end()} failure
     * that settles after the completion event was already emitted. Non-blocking; never throws.
     *
     * @param ctx   the routing context of the request
     * @param cause the failure the wire-completion future settled with; must not be {@code null}
     * @throws NullPointerException if {@code cause} is {@code null}
     */
    public static void recordWireFailure(RoutingContext ctx, Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        RequestCompletionState state = boundState(ctx);
        if (state != null) {
            state.recordWireFailure(cause);
        }
    }

    /**
     * Returns the post-handoff wire-failure cause recorded for {@code ctx}, or {@code null}. For
     * framework tests that drive a response pipeline without mounting the emitter; not an
     * application contract.
     *
     * @param ctx the routing context of the request
     * @return the first recorded cause, or {@code null}
     */
    @Nullable
    public static Throwable recordedWireFailure(RoutingContext ctx) {
        RequestCompletionState state = boundState(ctx);
        return state != null ? state.wireFailure() : null;
    }

    /**
     * Installs a completion-state holder on {@code ctx} when the emitter is not mounted. Reserved for
     * framework tests that isolate a response pipeline; production traffic gets its holder from
     * {@link RestRequestCompletionEmitter}.
     *
     * @param ctx the routing context of the request
     */
    public static void installHolder(RoutingContext ctx) {
        begin(ctx);
    }

    /**
     * Creates the completion state of the request routed through {@code ctx}, starting now and bound
     * to {@code ctx.request()}, and stores it as the request's holder, replacing any value there.
     * Reserved for {@link RestRequestCompletionEmitter}, which captures the returned state in its
     * end-handler closure.
     *
     * @param ctx the routing context of the request
     * @return the new completion state
     */
    static RequestCompletionState begin(RoutingContext ctx) {
        RequestCompletionState state = new RequestCompletionState(Instant.now(), ctx.request());
        ctx.put(HOLDER_KEY, state);
        return state;
    }

    /**
     * Returns the completion state held for the request routed through {@code ctx}, or {@code null}
     * when there is none: no holder, a holder value of another type, or a state bound to a request
     * other than {@code ctx.request()}. The holder is read only through
     * {@link RoutingContext#get(String)}.
     *
     * @param ctx the routing context of the request
     * @return the request's own completion state, or {@code null}
     */
    @Nullable
    static RequestCompletionState boundState(RoutingContext ctx) {
        Object holder = ctx.get(HOLDER_KEY);
        RequestCompletionState state = holder instanceof RequestCompletionState held ? held : null;
        return state != null && state.isBoundTo(ctx.request()) ? state : null;
    }
}
