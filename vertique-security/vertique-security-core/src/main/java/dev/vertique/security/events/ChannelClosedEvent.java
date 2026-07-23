// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.SecurityContext;
import java.time.Instant;
import java.util.Objects;

/**
 * Event fired when a persistent channel is closed.
 *
 * <p>Channels may be closed by the client (normal disconnect), by the server (policy enforcement,
 * graceful shutdown), or due to expiry of the authenticated credential. The machine-readable
 * {@code reasonCode} distinguishes the closure cause (e.g., {@code "CLIENT_DISCONNECT"},
 * {@code "TOKEN_EXPIRED"}, {@code "SERVER_SHUTDOWN"}).
 *
 * @param occurredAt      wall-clock instant when the channel was closed; never null
 * @param channelId       stable identifier of the closed channel; never null, never blank
 * @param securityContext the security context that was active at close time; never null
 * @param correlation     correlation context for the operation that closed the channel; never null
 * @param reasonCode      machine-readable closure reason (e.g., {@code "CLIENT_DISCONNECT"},
 *                        {@code "TOKEN_EXPIRED"}); must not be blank
 */
public record ChannelClosedEvent(
        Instant occurredAt,
        String channelId,
        SecurityContext securityContext,
        CorrelationContext correlation,
        String reasonCode)
        implements ChannelLifecycleEvent {

    /**
     * Compact constructor — validates all fields, rejects a blank {@code channelId}, and
     * rejects a blank {@code reasonCode}.
     */
    public ChannelClosedEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(channelId, "channelId");
        if (channelId.isBlank()) {
            throw new IllegalArgumentException("channelId must not be blank");
        }
        Objects.requireNonNull(securityContext, "securityContext");
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
    }
}
