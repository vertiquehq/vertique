// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.channel;

import dev.vertique.security.SecurityContext;
import io.vertx.core.Future;
import java.util.Optional;

/**
 * Per-runtime channel registry SPI for long-lived connection identity management (PRD §7.12).
 *
 * <p>Tracks long-lived channels (WebSocket, future SSE, future bidi-stream transports) and supports
 * identity refresh and force-close. Transport adapters provide a {@link ChannelBinding} when
 * registering so the manager can dispatch rebind and close operations back to the transport in a
 * context-appropriate way.
 *
 * <p>The default implementation lives in {@code vertique-rest-security}. Transport-specific
 * bindings live in their owning modules ({@code vertique-rest-websocket} for WebSocket).
 *
 * <p>Applications calling {@link #refreshIdentity(String, SecurityContext)} from outside the
 * channel's owning execution context receive a {@link Future} that completes only after the rebind
 * has taken effect — callers MUST {@code compose} on it before the next operation that depends on
 * the new identity.
 */
public interface ChannelIdentityManager {

    /**
     * Register a new channel with its initial {@link SecurityContext} and transport binding.
     *
     * <p>The {@code binding} is retained by the manager and used for subsequent {@link #refreshIdentity}
     * and {@link #closeChannel} calls. Registering the same {@code channelId} twice is a caller
     * error; implementations MAY replace the prior registration or fail.
     *
     * @param channelId a stable, non-null identifier for this channel; unique within the runtime
     * @param ctx       the initial {@link SecurityContext} captured at channel open; never
     *                  {@code null}
     * @param binding   the transport-owned callback used to dispatch rebind and close; never
     *                  {@code null}
     * @return a {@link Future} that completes when the registration has taken effect; never
     *         {@code null}
     */
    Future<Void> register(String channelId, SecurityContext ctx, ChannelBinding binding);

    /**
     * Atomically replace the bound {@link SecurityContext} for the given channel.
     *
     * <p>The manager delegates to the registered {@link ChannelBinding#rebind(SecurityContext)} so
     * the transport can perform the rebind on the appropriate execution context. The returned
     * {@link Future} completes only after the rebind has taken effect; callers MUST compose on it
     * before depending on the new identity.
     *
     * @param channelId the channel whose identity is being refreshed; must have been registered via
     *                  {@link #register}
     * @param newCtx    the replacement {@link SecurityContext}; never {@code null}
     * @return a {@link Future} that completes when the new identity is in effect; fails if the
     *         channel is not registered; never {@code null}
     */
    Future<Void> refreshIdentity(String channelId, SecurityContext newCtx);

    /**
     * Server-initiated force-close of the given channel with a typed reason.
     *
     * <p>Records the {@code reasonCode} as the pending close reason for this channel and initiates
     * the transport close via {@link ChannelBinding#close(String)}. The
     * {@link dev.vertique.security.events.ChannelClosedEvent} emission, expiry timer
     * cancellation, registry removal, and scope release happen later in
     * {@link #deregister(String, String)} after the transport's close callback has run the
     * application close handler ({@code @OnClose}).
     *
     * <p>This ordering ensures that the user's {@code @OnClose} method observes an authenticated
     * {@link SecurityContext} rather than an anonymous one (the scope is still bound when
     * {@code @OnClose} runs).
     *
     * <p>Idempotent: calling on an unregistered or already-closed channel returns a succeeded
     * future immediately.
     *
     * @param channelId  the channel to close; must have been registered via {@link #register}
     * @param reasonCode a stable, non-null reason identifier propagated into the
     *                   {@link dev.vertique.security.events.ChannelClosedEvent} — for example,
     *                   {@code "IDENTITY_EXPIRED"}, {@code "IDENTITY_REVOKED"}, or
     *                   {@code "CHANNEL_CLOSED_BY_SERVER"}
     * @return a {@link Future} that completes when the transport close has been initiated; never
     *         {@code null}
     */
    Future<Void> closeChannel(String channelId, String reasonCode);

    /**
     * Invoked by the transport AFTER the application close handler ({@code @OnClose}) has run.
     *
     * <p>This is the single cleanup owner. It:
     * <ol>
     *   <li>Removes the channel from the registry.</li>
     *   <li>Cancels the expiry timer (if any).</li>
     *   <li>Determines the effective close reason: the reason recorded by a prior server-initiated
     *       {@link #closeChannel(String, String)} call if present, otherwise
     *       {@code fallbackReasonCode}.</li>
     *   <li>Emits a {@link dev.vertique.security.events.ChannelClosedEvent} with the effective
     *       reason.</li>
     *   <li>Calls {@link ChannelBinding#releaseResources()} to release the transport-scoped
     *       identity scope.</li>
     * </ol>
     *
     * <p>Idempotent: if the channel is not found (already deregistered), returns a succeeded future
     * immediately without emitting any event.
     *
     * @param channelId        the channel identifier; must not be {@code null}
     * @param fallbackReasonCode the reason code to use when no prior server-initiated close has
     *                           recorded a pending reason (e.g.
     *                           {@code "CHANNEL_CLOSED_BY_PEER"}); must not be {@code null}
     * @return a {@link Future} that completes when all cleanup has finished; never {@code null}
     */
    Future<Void> deregister(String channelId, String fallbackReasonCode);

    /**
     * Returns the currently bound {@link SecurityContext} for the given channel, or
     * {@link Optional#empty()} if the channel is not registered or has been closed.
     *
     * @param channelId the channel to look up; never {@code null}
     * @return the currently bound context, or {@link Optional#empty()} if not found; never
     *         {@code null}
     */
    Optional<SecurityContext> current(String channelId);
}
