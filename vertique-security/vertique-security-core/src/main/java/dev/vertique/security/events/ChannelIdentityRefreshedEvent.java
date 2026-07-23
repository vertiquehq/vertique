// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.SecurityContext;
import java.time.Instant;
import java.util.Objects;

/**
 * Event fired when the identity on an existing persistent channel is refreshed.
 *
 * <p>Identity refresh occurs when a channel's credential is renewed without closing the channel —
 * for example, after a token refresh on a WebSocket connection or a re-authentication on an
 * SSE stream. Both the new and prior {@link SecurityContext} are carried so observers can
 * detect identity changes (e.g., privilege elevation or downgrade) or update cached state.
 *
 * @param occurredAt          wall-clock instant when the identity was refreshed; never null
 * @param channelId           stable identifier of the channel whose identity was refreshed;
 *                            never null, never blank
 * @param securityContext     the new security context active after the refresh; never null
 * @param correlation         correlation context for the operation that triggered the refresh;
 *                            never null
 * @param priorSecurityContext the security context that was active before the refresh; never null
 */
public record ChannelIdentityRefreshedEvent(
        Instant occurredAt,
        String channelId,
        SecurityContext securityContext,
        CorrelationContext correlation,
        SecurityContext priorSecurityContext)
        implements ChannelLifecycleEvent {

    /**
     * Compact constructor — validates all fields and rejects a blank {@code channelId}.
     */
    public ChannelIdentityRefreshedEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(channelId, "channelId");
        if (channelId.isBlank()) {
            throw new IllegalArgumentException("channelId must not be blank");
        }
        Objects.requireNonNull(securityContext, "securityContext");
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(priorSecurityContext, "priorSecurityContext");
    }
}
