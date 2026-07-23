// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.SecurityContext;
import java.time.Instant;
import java.util.Objects;

/**
 * Event fired when a persistent channel is opened with an authenticated identity.
 *
 * <p>This is the first event in a channel's lifecycle. It carries the initial
 * {@link SecurityContext} established at channel open time. Observers may use this event
 * to initialize per-channel audit state or register the channel in a session registry.
 *
 * @param occurredAt     wall-clock instant when the channel was opened; never null
 * @param channelId      stable identifier of the newly opened channel; never null, never blank
 * @param securityContext the security context active at channel open time; never null
 * @param correlation    correlation context for the operation that opened the channel; never null
 */
public record ChannelOpenedEvent(
        Instant occurredAt, String channelId, SecurityContext securityContext, CorrelationContext correlation)
        implements ChannelLifecycleEvent {

    /**
     * Compact constructor — validates all fields and rejects a blank {@code channelId}.
     */
    public ChannelOpenedEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(channelId, "channelId");
        if (channelId.isBlank()) {
            throw new IllegalArgumentException("channelId must not be blank");
        }
        Objects.requireNonNull(securityContext, "securityContext");
        Objects.requireNonNull(correlation, "correlation");
    }
}
