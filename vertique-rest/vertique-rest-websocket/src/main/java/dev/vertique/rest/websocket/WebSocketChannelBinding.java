// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.channel.ChannelBinding;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.ServerWebSocket;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link ChannelBinding} implementation for a single open WebSocket connection.
 *
 * <p>Owns the combined snapshot {@link ContextHolder.Scope} captured at upgrade time (returned by
 * {@link dev.vertique.context.ContextValues#bindSnapshot}), which keeps the full per-channel
 * request context — {@link SecurityContext}, {@link dev.vertique.core.correlation.CorrelationContext},
 * MDC, etc. — bound on the channel's Vert.x event-loop context. From construction the binding is
 * the sole owner of that scope's lifecycle.
 *
 * <p><b>Rebind semantics.</b> {@link #rebind(SecurityContext)} hops to the channel's owning
 * Vert.x context and layers a SecurityContext-only override on top of the snapshot scope: it
 * closes the prior override (if any) — which restores the snapshot's SecurityContext — then calls
 * {@link SecurityRuntime#bindCurrent(SecurityContext)} for the new identity. The snapshot scope is
 * NOT closed, so CorrelationContext and MDC survive the refresh. The returned {@link Future}
 * completes after the override is in place.
 *
 * <p><b>Close semantics.</b> {@link #close(String)} sends a WebSocket close frame with code
 * {@code 1000} and the supplied {@code reasonCode} but does NOT release any scope. Scope release
 * is the manager's responsibility: after the transport's close callback has run the application
 * close handler ({@code @OnClose}), the manager calls {@link #releaseResources()}.
 *
 * <p><b>Release semantics.</b> {@link #releaseResources()} closes the refresh override (if any)
 * then the snapshot scope, in LIFO order. It is idempotent — it uses {@link AtomicReference#getAndSet}
 * on each scope to take ownership exactly once, so concurrent calls (e.g. manager deregister racing
 * with a rebind cleanup) are safe.
 *
 * <p>All operations are dispatched on the owning Vert.x context so they execute on the correct
 * event loop without external synchronization. The scope references are {@link AtomicReference}s so
 * concurrent close / rebind / expiry-timer races are handled without data races.
 *
 * @see ChannelBinding
 * @see WebSocketChannelAdapter
 */
@Slf4j
final class WebSocketChannelBinding implements ChannelBinding {

    /**
     * RFC 6455 normal-closure code.
     */
    private static final short WS_CLOSE_NORMAL = 1000;

    private final io.vertx.core.Context ownerContext;
    private final ServerWebSocket socket;
    private final SecurityRuntime securityRuntime;

    /**
     * The combined snapshot scope bound at upgrade time — holds the full request context
     * (SecurityContext, CorrelationContext, MDC, ...). It stays open for the channel's whole
     * lifetime and is released only by {@link #releaseResources()}. Identity refresh must NOT
     * close it, or correlation/MDC would be dropped from the channel context.
     */
    private final AtomicReference<ContextHolder.Scope> snapshotScope;

    /**
     * The latest SecurityContext-only override scope layered on top of the snapshot by
     * {@link #rebind(SecurityContext)}. Null until the first refresh. Each refresh closes the prior
     * override (restoring the snapshot's SecurityContext) before binding the new one, leaving the
     * snapshot's CorrelationContext/MDC untouched.
     */
    private final AtomicReference<ContextHolder.Scope> refreshScope = new AtomicReference<>();

    /**
     * Creates a new binding for the given WebSocket connection.
     *
     * @param ownerContext the Vert.x context that owns this WebSocket connection; rebind and close
     *                     operations are dispatched onto this context; must not be {@code null}
     * @param socket       the underlying Vert.x server WebSocket; must not be {@code null}
     * @param securityRuntime the security runtime used to bind a new {@link SecurityContext} during
     *                        rebind; must not be {@code null}
     * @param snapshotScope the combined snapshot scope binding the full per-channel request context
     *                     (typically the scope returned by {@code ContextValues.bindSnapshot}); must
     *                     not be {@code null}; ownership is transferred to this binding
     */
    WebSocketChannelBinding(
            io.vertx.core.Context ownerContext,
            ServerWebSocket socket,
            SecurityRuntime securityRuntime,
            ContextHolder.Scope snapshotScope) {
        this.ownerContext = Objects.requireNonNull(ownerContext, "ownerContext");
        this.socket = Objects.requireNonNull(socket, "socket");
        this.securityRuntime = Objects.requireNonNull(securityRuntime, "securityRuntime");
        this.snapshotScope = new AtomicReference<>(Objects.requireNonNull(snapshotScope, "snapshotScope"));
    }

    // --- ChannelBinding ---

    /**
     * {@inheritDoc}
     *
     * <p>Dispatches the rebind onto the channel's owning Vert.x context. Layers a
     * SecurityContext-only override on top of the combined snapshot scope: the prior override (if
     * any) is closed first — restoring the snapshot's SecurityContext — then the new context is
     * bound. The snapshot scope itself stays open, so {@link dev.vertique.core.correlation.CorrelationContext}
     * and MDC bound at upgrade time survive the refresh. The returned future completes after the
     * override is in place.
     *
     * <p>If the binding's scopes have already been released by {@link #releaseResources()} (the
     * channel was closed/deregistered), the returned future fails with {@link IllegalStateException}
     * and no override is layered — a stale in-flight refresh cannot resurrect a dead channel's
     * identity or leak a scope.
     *
     * @param newCtx the replacement {@link SecurityContext}; must not be {@code null}
     * @return a future that completes when the rebind has taken effect on the channel's event loop,
     *         or fails with {@link IllegalStateException} if the binding was already released
     */
    @Override
    public Future<Void> rebind(SecurityContext newCtx) {
        Objects.requireNonNull(newCtx, "newCtx");
        Promise<Void> promise = Promise.promise();
        // Hop to the owner context so the rebind takes effect on the WebSocket's event loop.
        // Running here also serializes this body against releaseResources() (which hops to the same
        // context), so the snapshot-released check below cannot interleave with scope teardown.
        ownerContext.runOnContext(v -> {
            try {
                if (snapshotScope.get() == null) {
                    // The binding's scopes have already been released (channel closed/deregistered).
                    // Refuse to layer a new override — doing so would leak a scope and resurrect a
                    // dead channel's identity. The caller's Future fails so it learns the refresh
                    // did not apply.
                    promise.fail(new IllegalStateException("Channel binding already released; cannot rebind"));
                    return;
                }
                ContextHolder.Scope priorOverride = refreshScope.getAndSet(null);
                if (priorOverride != null) {
                    priorOverride.close();
                }
                refreshScope.set(securityRuntime.bindCurrent(newCtx));
                promise.complete();
            } catch (Throwable t) {
                promise.fail(t);
            }
        });
        return promise.future();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Dispatches the close onto the channel's owning Vert.x context. Sends a WebSocket close
     * frame with code {@code 1000} and the supplied {@code reasonCode} as the close reason text.
     * If the socket is already closed, no second close frame is sent.
     *
     * <p>The scope is NOT released here — scope release is the manager's responsibility via
     * {@link #releaseResources()}, which is called after the transport's close callback has run
     * the application close handler ({@code @OnClose}).
     *
     * @param reasonCode a stable identifier describing the close reason (e.g.
     *                   {@code "IDENTITY_EXPIRED"}, {@code "CHANNEL_CLOSED_BY_PEER"}); must not be
     *                   {@code null}
     * @return a future that completes when the transport close frame has been sent (or the socket
     *         was already closed); never {@code null}
     */
    @Override
    public Future<Void> close(String reasonCode) {
        Objects.requireNonNull(reasonCode, "reasonCode");
        Promise<Void> promise = Promise.promise();
        // Hop to the owner context so the close frame is sent on the WebSocket's event loop.
        ownerContext.runOnContext(v -> {
            try {
                if (!socket.isClosed()) {
                    socket.close(WS_CLOSE_NORMAL, reasonCode).onComplete(ar -> promise.complete());
                } else {
                    promise.complete();
                }
            } catch (Throwable t) {
                promise.fail(t);
            }
        });
        return promise.future();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Dispatches the scope release onto the channel's owning Vert.x context. Closes the latest
     * SecurityContext override (if any) before the combined snapshot scope, in LIFO order. Uses
     * {@link AtomicReference#getAndSet} on each scope to take ownership exactly once, making this
     * method idempotent — subsequent calls return a succeeded future immediately.
     *
     * @return a future that completes when the scopes have been released on the owning event loop;
     *         never {@code null}
     */
    @Override
    public Future<Void> releaseResources() {
        Promise<Void> promise = Promise.promise();
        // Hop to the owner context so scope cleanup happens on the WebSocket's event loop.
        ownerContext.runOnContext(v -> {
            try {
                // LIFO: close the refresh override (if any) before the snapshot it was layered on.
                ContextHolder.Scope override = refreshScope.getAndSet(null);
                if (override != null) {
                    override.close();
                }
                ContextHolder.Scope snapshot = snapshotScope.getAndSet(null);
                if (snapshot != null) {
                    snapshot.close();
                }
                promise.complete();
            } catch (Throwable t) {
                promise.fail(t);
            }
        });
        return promise.future();
    }
}
