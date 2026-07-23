// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import dev.vertique.core.context.ContextValue;
import dev.vertique.security.origin.RequestOrigin;
import java.util.Objects;
import java.util.Optional;

/**
 * Handoff type binding a present-but-unverifiable durable identity snapshot to the async
 * degradation gate.
 *
 * <p>The receive-side {@code InboundContextInitializer} for durable identity carriage runs
 * synchronously and cannot itself emit the
 * {@code dev.vertique.security.events.IdentitySnapshotDegradationEvent} (whose emission is awaited
 * before the policy decision — an ordering guarantee, not acknowledged audit delivery) — emission is async
 * ({@code SecurityEventEmitter.emit(...)} returns {@code Future<Void>}). Instead the initializer
 * <em>detects and binds</em> this marker onto the {@code ContextHolder} when a carried snapshot
 * fails HMAC verification (or otherwise cannot be reconstructed); a separate async degradation
 * gate on the service dispatch interceptor chain reads the marker, emits the event, and applies
 * the configured degradation policy ({@code FAIL} or {@code CONTINUE_WITHOUT_IDENTITY}).
 *
 * <p>Public so that {@code vertique-services} (where the gate lives, to avoid inverting the
 * shipped services→security-runtime dependency direction) can read this marker off the context
 * holder without depending on security-runtime internals.
 *
 * @param reasonCode machine-readable degradation reason; non-null, non-blank. One of
 *                   {@code BAD_HMAC}, {@code UNKNOWN_KEY}, {@code DECODE_FAILED},
 *                   {@code KEY_UNAVAILABLE}, {@code SCHEMA_INCOMPATIBLE}
 * @param origin     best-effort {@link RequestOrigin} recovered from the present-but-unverifiable
 *                   snapshot metadata, when available; non-null {@link Optional} — use
 *                   {@link Optional#empty()} when no origin could be recovered
 */
public record SnapshotDegradationMarker(String reasonCode, Optional<RequestOrigin> origin) implements ContextValue {

    /**
     * Compact constructor — validates {@code reasonCode} is non-null and non-blank, and
     * {@code origin} is a non-null {@link Optional}.
     */
    public SnapshotDegradationMarker {
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        Objects.requireNonNull(origin, "origin");
    }
}
