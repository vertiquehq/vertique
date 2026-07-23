// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.origin.RequestOrigin;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Event fired when a durably-carried {@link dev.vertique.security.IdentitySnapshot} is present but
 * cannot be reconstructed (HMAC verification failure, unknown signing key, decode failure, key
 * unavailability, or an incompatible schema version).
 *
 * <p>Emitted by the async degradation gate on the service dispatch interceptor chain after the
 * receive-side initializer has synchronously detected the unverifiable snapshot and bound a
 * {@link dev.vertique.security.SnapshotDegradationMarker} onto the context holder. Observers may
 * use this event for audit logging, alerting, or anomaly detection — it signals that a deferred
 * execution (delayed job, inbox/outbox handler, workflow resume) proceeded without a verified
 * subject-of-record, or was aborted, depending on the configured degradation policy.
 *
 * <p>All fields are non-null after construction; the {@code origin} optional carries a best-effort
 * {@link RequestOrigin} recovered from the present-but-unverifiable snapshot metadata, when
 * available — use {@link Optional#empty()} when no origin could be recovered.
 *
 * @param occurredAt wall-clock instant when the degradation was detected; never null
 * @param correlation correlation context for the deferred execution that triggered this event;
 *                    never null
 * @param origin      best-effort recovered network-envelope facts; non-null {@link Optional} —
 *                    use {@link Optional#empty()} when no origin could be recovered
 * @param reasonCode  machine-readable degradation reason; never null, never blank. One of
 *                    {@code BAD_HMAC}, {@code UNKNOWN_KEY}, {@code DECODE_FAILED},
 *                    {@code KEY_UNAVAILABLE}, {@code SCHEMA_INCOMPATIBLE}
 */
public record IdentitySnapshotDegradationEvent(
        Instant occurredAt, CorrelationContext correlation, Optional<RequestOrigin> origin, String reasonCode) {

    /**
     * Compact constructor — validates that all fields are non-null, and that {@code reasonCode}
     * is non-blank.
     */
    public IdentitySnapshotDegradationEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
    }
}
