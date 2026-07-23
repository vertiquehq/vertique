// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.channel;

import dev.vertique.security.SecurityContext;
import io.vertx.core.Future;

/**
 * Transport-owned binding handle for a single registered channel.
 *
 * <p>The {@link ChannelIdentityManager} holds one {@code ChannelBinding} per channel ID and
 * invokes it to rebind identity or force-close the channel. Implementations are responsible for
 * transport-affine concerns: owning any transport-scoped binding lifetime, dispatching the rebind
 * on whatever execution context the transport requires, and closing the underlying connection.
 *
 * <p>Transport implementations typically hold a per-connection "bound scope" handle that must be
 * released when the channel closes or rebinds, otherwise subsequent callbacks see stale identity.
 * The transport is the only layer that knows the scope lifecycle, so the binding is owned there;
 * the manager only signals "rebind now" / "close now" through this callback. Core does not depend
 * on any transport runtime.
 *
 * <p>Concrete implementations live in their owning transport modules — for example,
 * {@code vertique-rest-websocket} provides a WebSocket-specific binding that dispatches rebinds
 * on the Vert.x event-loop thread that owns the connection.
 */
public interface ChannelBinding {

    /**
     * Replace the bound {@link SecurityContext} for this channel.
     *
     * <p>Implementations MUST release any prior bound-scope resources before establishing the new
     * binding to avoid leaking stale identity when the prior scope is closed. The returned future
     * completes when the rebind has taken effect for subsequent transport callbacks.
     *
     * @param newCtx the replacement {@link SecurityContext} to bind to this channel; never
     *               {@code null}
     * @return a {@link Future} that completes when the rebind has taken effect; never {@code null}
     */
    Future<Void> rebind(SecurityContext newCtx);

    /**
     * Initiate the transport close (e.g. send the WebSocket close frame).
     *
     * <p>{@code reasonCode} is a stable identifier that will eventually be propagated into
     * {@link dev.vertique.security.events.ChannelClosedEvent} — for example,
     * {@code "IDENTITY_EXPIRED"}, {@code "IDENTITY_REVOKED"},
     * {@code "CHANNEL_CLOSED_BY_PEER"}, or {@code "CHANNEL_CLOSED_BY_SERVER"}.
     *
     * <p>MUST NOT release the identity scope — scope release is the manager's responsibility via
     * {@link #releaseResources()} after the transport's close callback has run the application
     * close handler. The close callback path (e.g. {@code ws.closeHandler}) will eventually call
     * back into the manager's {@code deregister} method, which calls {@link #releaseResources()}.
     *
     * @param reasonCode a stable, non-null reason identifier describing why the channel is being
     *                   closed; propagated to channel lifecycle events
     * @return a {@link Future} that completes when the transport close has been initiated; never
     *         {@code null}
     */
    Future<Void> close(String reasonCode);

    /**
     * Release any transport-scoped binding resources (e.g. the bound {@link SecurityRuntime} scope).
     *
     * <p>Idempotent: calling more than once is a no-op — subsequent calls return a succeeded future
     * immediately without performing any additional cleanup.
     *
     * <p>This method is invoked by the manager in its {@code deregister} path, which runs
     * <em>after</em> the transport's close callback has completed the application close handler.
     * This ordering ensures the user's {@code @OnClose} method observes an authenticated
     * {@link dev.vertique.security.SecurityContext} rather than an anonymous one.
     *
     * @return a {@link Future} that completes when the resources have been released; never
     *         {@code null}
     */
    Future<Void> releaseResources();
}
