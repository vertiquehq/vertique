// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.security.CarriageRequirement;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Root configuration record for the identity-snapshot durable-carriage subsystem, deserialized
 * from the {@code identity.snapshot} section of the application config via
 * {@link dev.vertique.core.config.ConfigParser} (PRD-ID-002 §14.3 "Durable carriage", §14.6 A9
 * "expected-but-absent carriage detection").
 *
 * @param captureEnabled     the global capture kill-switch consulted by {@link IdentitySnapshotCapture};
 *                           defaults to {@code true} when omitted
 * @param onDegradation      the policy applied when a carried snapshot is present but unverifiable;
 *                           defaults to {@link IdentitySnapshotDegradationPolicy#FAIL} when omitted
 * @param hmacKeys           the HMAC keyset backing {@link IdentitySnapshotCodec}; required — snapshot
 *                           signing/verification cannot proceed without at least an active key
 * @param maxCarrierLifetimeMs the carrier-lifetime freshness budget in milliseconds (measured from a
 *                           snapshot's {@code issuedAt}); {@code null} = unbounded/absent until an
 *                           operator configures it (mandatory-finite once any target is
 *                           {@link CarriageRequirement#REQUIRED} — see the compact constructor)
 * @param maxSnapshotLifetimeMs the snapshot-lifetime freshness budget in milliseconds (measured from a
 *                           snapshot's immutable {@code capturedAt}, so a chained re-encode cannot renew
 *                           authority); {@code null} = unbounded/absent until an operator configures it
 *                           (same mandatory-finite rule as {@code maxCarrierLifetimeMs})
 * @param clockSkewMs        the tolerated clock skew in milliseconds applied by the freshness policy;
 *                           defaults to {@code 30000} (30s) when omitted
 * @param carriageRequirements per-durable-target-kind {@link CarriageRequirement} (keyed on the
 *                           {@code DeferredExecutionOrigin#kind()} / durable-target kind string, e.g.
 *                           {@code "delayed-job"}, {@code "cron"}, {@code "outbox-relay"}); an unlisted
 *                           target-kind defaults to {@link CarriageRequirement#OPTIONAL} (see
 *                           {@link #carriageRequirementFor(String)}); never {@code null}, defaults to an
 *                           empty map when omitted
 */
public record IdentitySnapshotConfig(
        boolean captureEnabled,
        IdentitySnapshotDegradationPolicy onDegradation,
        SnapshotHmacConfig hmacKeys,
        @Nullable Long maxCarrierLifetimeMs,
        @Nullable Long maxSnapshotLifetimeMs,
        long clockSkewMs,
        Map<String, CarriageRequirement> carriageRequirements) {

    /** Default tolerated clock skew (milliseconds) when {@code clockSkewMs} is omitted. */
    private static final long DEFAULT_CLOCK_SKEW_MS = 30_000L;

    /**
     * Compact constructor validating {@code hmacKeys} is present and, when any configured target-kind
     * is {@link CarriageRequirement#REQUIRED}, that both freshness budgets are finite — a
     * never-expiring {@code REQUIRED} snapshot would otherwise be a standing bearer credential.
     *
     * @throws ConfigurationException if {@code hmacKeys} is {@code null}, or if any
     *                                {@code carriageRequirements} entry is {@code REQUIRED} while
     *                                {@code maxCarrierLifetimeMs} or {@code maxSnapshotLifetimeMs} is
     *                                {@code null}
     */
    public IdentitySnapshotConfig {
        Objects.requireNonNull(onDegradation, "onDegradation");
        if (hmacKeys == null) {
            throw new ConfigurationException("identity.snapshot.hmacKeys is required");
        }
        Objects.requireNonNull(carriageRequirements, "carriageRequirements");
        carriageRequirements = Map.copyOf(carriageRequirements);
        boolean anyRequired = carriageRequirements.values().stream()
                .anyMatch(requirement -> requirement == CarriageRequirement.REQUIRED);
        if (anyRequired && (maxCarrierLifetimeMs == null || maxSnapshotLifetimeMs == null)) {
            throw new ConfigurationException("identity.snapshot.carriageRequirements declares a REQUIRED target, but "
                    + "maxCarrierLifetimeMs and maxSnapshotLifetimeMs must both be configured — a "
                    + "never-expiring REQUIRED snapshot would be a standing bearer credential");
        }
    }

    /**
     * Jackson-friendly factory that fills in defaults for omitted JSON properties.
     *
     * @param captureEnabled       the capture kill-switch; defaults to {@code true} when {@code null}
     * @param onDegradation        the degradation policy; defaults to {@code FAIL} when {@code null}
     * @param hmacKeys             the HMAC keyset config; required
     * @param maxCarrierLifetimeMs the carrier-lifetime budget (ms); {@code null} = absent
     * @param maxSnapshotLifetimeMs the snapshot-lifetime budget (ms); {@code null} = absent
     * @param clockSkewMs          the tolerated clock skew (ms); defaults to {@code 30000} when
     *                             {@code null}
     * @param carriageRequirements per-target-kind carriage requirement map; defaults to an empty map
     *                             when {@code null}
     * @return the deserialized identity-snapshot config
     */
    @JsonCreator
    static IdentitySnapshotConfig fromJson(
            @JsonProperty("captureEnabled") @Nullable Boolean captureEnabled,
            @JsonProperty("onDegradation") @Nullable IdentitySnapshotDegradationPolicy onDegradation,
            @JsonProperty("hmacKeys") @Nullable SnapshotHmacConfig hmacKeys,
            @JsonProperty("maxCarrierLifetimeMs") @Nullable Long maxCarrierLifetimeMs,
            @JsonProperty("maxSnapshotLifetimeMs") @Nullable Long maxSnapshotLifetimeMs,
            @JsonProperty("clockSkewMs") @Nullable Long clockSkewMs,
            @JsonProperty("carriageRequirements") @Nullable Map<String, CarriageRequirement> carriageRequirements) {
        return new IdentitySnapshotConfig(
                captureEnabled == null || captureEnabled,
                onDegradation != null ? onDegradation : IdentitySnapshotDegradationPolicy.FAIL,
                hmacKeys,
                maxCarrierLifetimeMs,
                maxSnapshotLifetimeMs,
                clockSkewMs != null ? clockSkewMs : DEFAULT_CLOCK_SKEW_MS,
                carriageRequirements != null ? carriageRequirements : Map.of());
    }

    /**
     * Resolves the configured {@link CarriageRequirement} for a durable-target kind.
     *
     * @param targetKind the durable-target kind string (e.g. {@code "delayed-job"}); must not be
     *                   {@code null}
     * @return the configured requirement for {@code targetKind}, or {@link CarriageRequirement#OPTIONAL}
     *     when the kind is not listed in {@link #carriageRequirements()}
     */
    public CarriageRequirement carriageRequirementFor(String targetKind) {
        Objects.requireNonNull(targetKind, "targetKind");
        return carriageRequirements.getOrDefault(targetKind, CarriageRequirement.OPTIONAL);
    }

    /**
     * Returns the carrier-lifetime freshness budget as a {@link Duration}.
     *
     * @return the configured budget, or empty when unconfigured
     */
    public Optional<Duration> maxCarrierLifetime() {
        return Optional.ofNullable(maxCarrierLifetimeMs).map(Duration::ofMillis);
    }

    /**
     * Returns the snapshot-lifetime freshness budget as a {@link Duration}.
     *
     * @return the configured budget, or empty when unconfigured
     */
    public Optional<Duration> maxSnapshotLifetime() {
        return Optional.ofNullable(maxSnapshotLifetimeMs).map(Duration::ofMillis);
    }

    /**
     * Returns the tolerated clock skew as a {@link Duration}.
     *
     * @return the clock skew; never {@code null}
     */
    public Duration clockSkew() {
        return Duration.ofMillis(clockSkewMs);
    }
}
