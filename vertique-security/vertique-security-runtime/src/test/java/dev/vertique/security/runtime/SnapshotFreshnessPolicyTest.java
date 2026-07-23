// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.context.DurableTarget;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SnapshotCarrierBinding;
import dev.vertique.security.SnapshotDegradationReason;
import dev.vertique.security.SnapshotIntegrity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SnapshotFreshnessPolicy} — the fail-closed freshness gate applied at snapshot
 * decode (PRD-ID-002 §14.6 amendment A9, the F5 replay defense). Verifies the three-term effective-expiry
 * minimum (signed {@code expiresAt}, carrier budget, capture-anchored snapshot budget), future-dating and
 * malformed-temporal-ordering rejection, and the clock-skew tolerance — all against an injected fixed
 * {@link Clock} so freshness is deterministic.
 */
class SnapshotFreshnessPolicyTest {

    private static final PrincipalRef ACTOR = new PrincipalRef(PrincipalType.SERVICE, "svc-1", java.util.Map.of());
    private static final SnapshotCarrierBinding CARRIER =
            new SnapshotCarrierBinding("carrier-1", new DurableTarget("outbox", "orders", Optional.empty()));
    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    private static Clock fixedAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static IdentitySnapshot snapshot(Instant capturedAt, Instant issuedAt, Instant expiresAt) {
        IdentitySnapshotContent content = new IdentitySnapshotContent(
                ACTOR,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "jwt",
                capturedAt,
                Optional.empty(),
                List.of(),
                "rest:authenticated",
                capturedAt);
        return new IdentitySnapshot(
                2, content, CARRIER, issuedAt, expiresAt, new SnapshotIntegrity("HmacSHA256", "key-1", "tag"));
    }

    @Test
    @DisplayName("effectiveExpiry is the minimum of all three terms — each in turn the binding minimum")
    void effectiveExpiryIsThreeTermMin() {
        // capturedAt = NOW - 10m, issuedAt = NOW - 5m in all three; only the binding term differs.
        Instant capturedAt = NOW.minus(Duration.ofMinutes(10));
        Instant issuedAt = NOW.minus(Duration.ofMinutes(5));

        // Term 1 (signed expiresAt) is the binding minimum: expiresAt = NOW - 1s (already past, no skew).
        SnapshotFreshnessPolicy noBudgets =
                new SnapshotFreshnessPolicy(Optional.empty(), Optional.empty(), Duration.ZERO, fixedAt(NOW));
        assertThrows(
                IdentitySnapshotCodecException.class,
                () -> noBudgets.check(snapshot(capturedAt, issuedAt, NOW.minusSeconds(1))),
                "a signed expiresAt already in the past must bind the effective expiry and fail closed");

        // Term 2 (issuedAt + maxCarrierLifetime) is the binding minimum: carrier budget expires at
        // issuedAt + 1m = NOW - 4m (past), while signed expiresAt is far in the future.
        SnapshotFreshnessPolicy carrierBudget = new SnapshotFreshnessPolicy(
                Optional.of(Duration.ofMinutes(1)), Optional.empty(), Duration.ZERO, fixedAt(NOW));
        assertThrows(
                IdentitySnapshotCodecException.class,
                () -> carrierBudget.check(snapshot(capturedAt, issuedAt, NOW.plus(Duration.ofHours(1)))),
                "the carrier-budget term must bind the effective expiry when it is the minimum");

        // Term 3 (capturedAt + maxSnapshotLifetime) is the binding minimum: snapshot budget expires at
        // capturedAt + 2m = NOW - 8m (past), while signed expiresAt and carrier budget are far ahead.
        SnapshotFreshnessPolicy snapshotBudget = new SnapshotFreshnessPolicy(
                Optional.of(Duration.ofHours(1)), Optional.of(Duration.ofMinutes(2)), Duration.ZERO, fixedAt(NOW));
        assertThrows(
                IdentitySnapshotCodecException.class,
                () -> snapshotBudget.check(snapshot(capturedAt, issuedAt, NOW.plus(Duration.ofHours(1)))),
                "the capture-anchored snapshot-budget term must bind the effective expiry when it is the minimum");
    }

    @Test
    @DisplayName("a snapshot past its signed expiresAt fails closed with reason EXPIRED")
    void expiredBySignedExpiresAtFailsClosed() {
        SnapshotFreshnessPolicy policy =
                new SnapshotFreshnessPolicy(Optional.empty(), Optional.empty(), Duration.ofSeconds(30), fixedAt(NOW));
        IdentitySnapshot expired = snapshot(
                NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofMinutes(2)));
        IdentitySnapshotCodecException failure =
                assertThrows(IdentitySnapshotCodecException.class, () -> policy.check(expired));
        assertEquals(SnapshotDegradationReason.EXPIRED, failure.reason());
    }

    @Test
    @DisplayName("a snapshot past its carrier budget fails closed with reason EXPIRED")
    void expiredByCarrierBudgetFailsClosed() {
        SnapshotFreshnessPolicy policy = new SnapshotFreshnessPolicy(
                Optional.of(Duration.ofMinutes(1)), Optional.empty(), Duration.ZERO, fixedAt(NOW));
        // issuedAt + 1m carrier budget expires well before now; signed expiresAt is far ahead.
        IdentitySnapshot expired = snapshot(
                NOW.minus(Duration.ofMinutes(10)), NOW.minus(Duration.ofMinutes(5)), NOW.plus(Duration.ofHours(1)));
        IdentitySnapshotCodecException failure =
                assertThrows(IdentitySnapshotCodecException.class, () -> policy.check(expired));
        assertEquals(SnapshotDegradationReason.EXPIRED, failure.reason());
    }

    @Test
    @DisplayName("a snapshot past its capture-anchored snapshot budget fails closed with reason EXPIRED")
    void expiredBySnapshotBudgetFailsClosed() {
        // capturedAt + 2m is the binding minimum (a chained re-encode with a fresh issuedAt cannot renew it).
        SnapshotFreshnessPolicy policy = new SnapshotFreshnessPolicy(
                Optional.of(Duration.ofHours(1)), Optional.of(Duration.ofMinutes(2)), Duration.ZERO, fixedAt(NOW));
        IdentitySnapshot expired = snapshot(
                NOW.minus(Duration.ofMinutes(10)), NOW.minus(Duration.ofSeconds(30)), NOW.plus(Duration.ofHours(1)));
        IdentitySnapshotCodecException failure =
                assertThrows(IdentitySnapshotCodecException.class, () -> policy.check(expired));
        assertEquals(SnapshotDegradationReason.EXPIRED, failure.reason());
    }

    @Test
    @DisplayName("with both budgets absent, only the signed expiresAt bounds the snapshot")
    void bothMaxesAbsentOnlySignedExpiresBounds() {
        SnapshotFreshnessPolicy policy =
                new SnapshotFreshnessPolicy(Optional.empty(), Optional.empty(), Duration.ZERO, fixedAt(NOW));
        // capturedAt/issuedAt are ancient, but expiresAt is still in the future — accepted, because no
        // capture-/issue-anchored budget bounds it.
        IdentitySnapshot fresh = snapshot(
                NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(29)), NOW.plus(Duration.ofMinutes(1)));
        assertDoesNotThrow(() -> policy.check(fresh));
    }

    @Test
    @DisplayName("a future-dated capturedAt beyond the clock skew is rejected as MALFORMED_TEMPORAL")
    void futureDatedCaptureRejected() {
        SnapshotFreshnessPolicy policy =
                new SnapshotFreshnessPolicy(Optional.empty(), Optional.empty(), Duration.ofSeconds(30), fixedAt(NOW));
        IdentitySnapshot future = snapshot(
                NOW.plus(Duration.ofMinutes(5)), NOW.plus(Duration.ofMinutes(6)), NOW.plus(Duration.ofHours(1)));
        IdentitySnapshotCodecException failure =
                assertThrows(IdentitySnapshotCodecException.class, () -> policy.check(future));
        assertEquals(SnapshotDegradationReason.MALFORMED_TEMPORAL, failure.reason());
    }

    @Test
    @DisplayName("an issuedAt before capturedAt (impossible ordering) is rejected as MALFORMED_TEMPORAL")
    void issuedBeforeCapturedRejected() {
        SnapshotFreshnessPolicy policy =
                new SnapshotFreshnessPolicy(Optional.empty(), Optional.empty(), Duration.ofSeconds(30), fixedAt(NOW));
        IdentitySnapshot malformed = snapshot(
                NOW.minus(Duration.ofMinutes(5)), NOW.minus(Duration.ofMinutes(10)), NOW.plus(Duration.ofHours(1)));
        IdentitySnapshotCodecException failure =
                assertThrows(IdentitySnapshotCodecException.class, () -> policy.check(malformed));
        assertEquals(SnapshotDegradationReason.MALFORMED_TEMPORAL, failure.reason());
    }

    @Test
    @DisplayName("a snapshot just past effective expiry but within the clock skew is accepted")
    void withinSkewAccepted() {
        SnapshotFreshnessPolicy policy =
                new SnapshotFreshnessPolicy(Optional.empty(), Optional.empty(), Duration.ofSeconds(30), fixedAt(NOW));
        // expiresAt = NOW - 10s: past, but within the 30s skew, so accepted.
        IdentitySnapshot barelyStale =
                snapshot(NOW.minus(Duration.ofMinutes(10)), NOW.minus(Duration.ofMinutes(5)), NOW.minusSeconds(10));
        assertDoesNotThrow(() -> policy.check(barelyStale));
    }
}
