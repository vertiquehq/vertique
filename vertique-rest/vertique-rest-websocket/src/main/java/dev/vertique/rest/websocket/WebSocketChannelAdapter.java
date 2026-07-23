// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.channel.ChannelIdentityManager;
import io.vertx.core.Future;
import io.vertx.core.http.ServerWebSocket;
import java.util.Objects;

/**
 * Bridge between the WebSocket lifecycle hooks in {@link WebSocketEndpointRegistrar} and the
 * {@link ChannelIdentityManager}.
 *
 * <p>On WebSocket open, {@link #onOpen} wraps the underlying connection in a
 * {@link WebSocketChannelBinding} and registers the channel with the manager. The binding takes
 * ownership of the initial {@link ContextHolder.Scope} returned at upgrade time; the manager owns
 * the channel lifecycle from that point forward.
 *
 * <p>On WebSocket close, {@link #onClose} is invoked by the registrar's close handler
 * <em>after</em> the user's {@code @OnClose} method has settled. It delegates to
 * {@link ChannelIdentityManager#deregister(String, String)}, which is the single cleanup owner:
 * it emits {@link dev.vertique.security.events.ChannelClosedEvent}, cancels any pending
 * expiry timer, removes the registry entry, and releases the binding's scope. This ordering
 * ensures {@code @OnClose} observes an authenticated {@link dev.vertique.security.SecurityContext}
 * rather than an anonymous one, for both peer-initiated and server-initiated closes.
 *
 * <p>{@link WebSocketMount.Factory} constructs a single adapter inline when both the
 * {@link ChannelIdentityManager} and {@link SecurityRuntime} are present (i.e. the security module
 * is configured). The manager is supplied through {@code WebSocketModule}'s
 * {@code @BindsOptionalOf ChannelIdentityManager}; when that optional binding is absent (no-auth
 * deployments) the factory builds no adapter and the registrar skips channel registration. The
 * same adapter instance handles every WebSocket connection mounted by that factory.
 *
 * @see WebSocketChannelBinding
 * @see ChannelIdentityManager
 */
public final class WebSocketChannelAdapter {

    /** Reason code sent when the peer closes the WebSocket connection. */
    static final String REASON_CHANNEL_CLOSED_BY_PEER = "CHANNEL_CLOSED_BY_PEER";

    private final ChannelIdentityManager manager;
    private final SecurityRuntime securityRuntime;

    /**
     * Creates a new adapter.
     *
     * <p>Constructed inline by {@link WebSocketMount.Factory} (not Dagger-managed) only when the
     * optional {@link ChannelIdentityManager} binding and the {@link SecurityRuntime} are both
     * present, so the adapter never exists in a no-auth deployment.
     *
     * @param manager         the channel identity manager that owns the channel lifecycle; must not
     *                        be {@code null}
     * @param securityRuntime the security runtime used to bind a refreshed {@link SecurityContext}
     *                        during identity refresh; must not be {@code null}
     */
    public WebSocketChannelAdapter(ChannelIdentityManager manager, SecurityRuntime securityRuntime) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.securityRuntime = Objects.requireNonNull(securityRuntime, "securityRuntime");
    }

    /**
     * Invoked from {@link WebSocketEndpointRegistrar} when a WebSocket connection is successfully
     * bootstrapped.
     *
     * <p>Creates a {@link WebSocketChannelBinding} that wraps the connection, then calls
     * {@link ChannelIdentityManager#register(String, SecurityContext, dev.vertique.security.channel.ChannelBinding)}
     * to register the channel. The binding takes ownership of {@code initialScope}; callers must
     * not close it after this method is called.
     *
     * <p><b>Fail-closed on registration failure.</b> If {@code register(...)} fails, the manager
     * never retains the binding, so its {@code deregister} cleanup path would never release the
     * scope. To avoid orphaning {@code initialScope}, this method releases the binding's scope (via
     * {@link WebSocketChannelBinding#releaseResources()}) before propagating the failure. The
     * returned future therefore fails only after the scope has been released, so the caller
     * ({@link WebSocketEndpointRegistrar}) can fail closed by closing the socket without separately
     * releasing the scope (F-W5).
     *
     * <p>The {@link dev.vertique.security.channel.ChannelIdentityManager#register} SPI documents a
     * non-null {@link Future} return, but a custom manager could violate that contract by throwing
     * synchronously or returning {@code null}. Both are defended here: the {@code register(...)} call
     * is wrapped so a synchronous throw or a {@code null} return is treated exactly like a failed
     * registration — the binding's scope is released and a failed future is returned — so a
     * misbehaving manager cannot orphan the scope or let an exception escape past the registrar's
     * failure path.
     *
     * @param channelId    a stable identifier for this channel (typically the session id); must not
     *                     be {@code null}
     * @param ctx          the initial {@link SecurityContext} captured at upgrade time; must not be
     *                     {@code null}
     * @param socket       the underlying Vert.x server WebSocket; must not be {@code null}
     * @param ownerContext the Vert.x context that owns this connection; rebind and manager-initiated
     *                     close operations are dispatched onto this context; must not be {@code null}
     * @param initialScope the scope currently binding the channel's context values; ownership is
     *                     transferred to the {@link WebSocketChannelBinding}; must not be
     *                     {@code null}
     * @return a future that completes when the channel has been registered with the manager; on
     *         registration failure, completes (failed) only after the binding's scope has been
     *         released
     */
    public Future<Void> onOpen(
            String channelId,
            SecurityContext ctx,
            ServerWebSocket socket,
            io.vertx.core.Context ownerContext,
            ContextHolder.Scope initialScope) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(socket, "socket");
        Objects.requireNonNull(ownerContext, "ownerContext");
        Objects.requireNonNull(initialScope, "initialScope");

        WebSocketChannelBinding binding =
                new WebSocketChannelBinding(ownerContext, socket, securityRuntime, initialScope);

        // Defend the manager boundary: a custom manager may throw synchronously or return null in
        // violation of the register() contract. Capture either as a failed future so the single
        // recover() below releases the binding's scope on every failure mode.
        Future<Void> registration;
        try {
            registration = manager.register(channelId, ctx, binding);
            if (registration == null) {
                registration = Future.failedFuture(new IllegalStateException(
                        "ChannelIdentityManager.register returned null for channel " + channelId));
            }
        } catch (Throwable t) {
            registration = Future.failedFuture(t);
        }

        return registration.recover(t -> {
            // Registration failed → the manager did not retain the binding, so release the scope here
            // to avoid orphaning it, then propagate the original failure once release completes.
            return binding.releaseResources().transform(ignored -> Future.failedFuture(t));
        });
    }

    /**
     * Invoked from {@link WebSocketEndpointRegistrar} after the user's {@code @OnClose} method has
     * settled (either peer-initiated or server-initiated close).
     *
     * <p>Delegates to {@link ChannelIdentityManager#deregister(String, String)} with fallback
     * reason {@value #REASON_CHANNEL_CLOSED_BY_PEER}. The manager is the single cleanup owner: it
     * emits a {@link dev.vertique.security.events.ChannelClosedEvent} (using the pending
     * reason from a prior server-initiated {@code closeChannel} call if present, otherwise the
     * fallback), cancels any pending expiry timer, removes the registry entry, and releases the
     * binding's scope.
     *
     * <p>If the channel was not registered (e.g. because {@link #onOpen} was never called due to a
     * security-absent configuration), the manager returns a succeeded future immediately.
     *
     * @param channelId the channel identifier passed to {@link #onOpen} at connection time; must
     *                  not be {@code null}
     * @return a future that completes when the channel has been fully deregistered and resources
     *         released by the manager
     */
    public Future<Void> onClose(String channelId) {
        Objects.requireNonNull(channelId, "channelId");
        return manager.deregister(channelId, REASON_CHANNEL_CLOSED_BY_PEER);
    }
}
