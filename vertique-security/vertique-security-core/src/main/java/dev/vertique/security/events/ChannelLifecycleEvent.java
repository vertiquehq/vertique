// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.SecurityContext;
import java.time.Instant;

/**
 * Sealed interface for persistent-channel lifecycle events.
 *
 * <p>A channel represents a long-lived authenticated connection (e.g., WebSocket, SSE stream,
 * or gRPC streaming call). The three permitted subtypes model the full lifecycle:
 * <ol>
 *   <li>{@link ChannelOpenedEvent} — a new channel was established with an authenticated identity.</li>
 *   <li>{@link ChannelIdentityRefreshedEvent} — the identity on an existing channel was refreshed
 *       (e.g., after a token renewal or re-authentication).</li>
 *   <li>{@link ChannelClosedEvent} — a channel was closed, either by the client, by the server, or
 *       due to an error or expiry.</li>
 * </ol>
 *
 * <p>Observers receive all subtypes through
 * {@link SecurityEventObserver#onChannelLifecycle(ChannelLifecycleEvent)} and may use
 * {@code instanceof} pattern-matching or a sealed {@code switch} to dispatch on type.
 *
 * @see ChannelOpenedEvent
 * @see ChannelIdentityRefreshedEvent
 * @see ChannelClosedEvent
 */
public sealed interface ChannelLifecycleEvent
        permits ChannelOpenedEvent, ChannelIdentityRefreshedEvent, ChannelClosedEvent {

    /**
     * Wall-clock instant when the lifecycle transition occurred.
     *
     * @return the event instant; never null
     */
    Instant occurredAt();

    /**
     * Stable identifier of the channel that experienced the lifecycle transition.
     *
     * @return the channel id; never null, never blank
     */
    String channelId();

    /**
     * Security context active on the channel at the time of the event.
     *
     * <p>For {@link ChannelIdentityRefreshedEvent}, this is the <em>new</em> security context.
     * The prior context is available via {@link ChannelIdentityRefreshedEvent#priorSecurityContext()}.
     *
     * @return the current security context; never null
     */
    SecurityContext securityContext();

    /**
     * Correlation context for the request or operation that caused this lifecycle transition.
     *
     * @return the correlation context; never null
     */
    CorrelationContext correlation();
}
