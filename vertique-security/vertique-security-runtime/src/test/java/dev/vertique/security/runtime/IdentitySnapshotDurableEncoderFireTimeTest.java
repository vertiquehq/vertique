// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.core.exception.DurableEncodeRejectedException;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IdentitySnapshotDurableEncoder}'s F5 doomed-expiry degrade path
 * (PRD-ID-002 §14.6 amendment A9, P2.S0 security review): a delayed-job row scheduled beyond the
 * signed envelope's effective expiry must be caught at encode time and policy-mirrored, rather than
 * persisting a snapshot that is guaranteed to fail closed at first decode with no signal at
 * schedule time.
 */
class IdentitySnapshotDurableEncoderFireTimeTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-07-01T10:15:30Z");
    private static final Instant ENCODE_TIME = CAPTURED_AT.plusSeconds(1);
    private static final Duration MAX_SNAPSHOT_LIFETIME = Duration.ofMinutes(10);

    private static final PrincipalRef ACTOR =
            new PrincipalRef(PrincipalType.SERVICE, "svc-scheduler", Map.of("tenant", "acme"));

    private static final DurableCarrierDescriptor CARRIER = new DurableCarrierDescriptor(
            "execution-1", new DurableTarget("delayed-job", "job.delayed.test-handler", Optional.empty()));

    /**
     * Builds a codec whose effective envelope expiry is deterministic and independent of the real
     * wall clock: a fixed {@code maxSnapshotLifetimeMs} budget of {@link #MAX_SNAPSHOT_LIFETIME},
     * anchored on the immutable {@link #CAPTURED_AT}, so {@code envelopeExpiry(CAPTURED_AT) ==
     * CAPTURED_AT + MAX_SNAPSHOT_LIFETIME} regardless of when the test actually runs.
     *
     * @return a codec with a deterministic snapshot-lifetime budget
     */
    private static IdentitySnapshotCodec newCodec() {
        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        return new IdentitySnapshotCodec(
                hmac,
                new SnapshotFreshnessPolicy(
                        Optional.empty(),
                        Optional.of(MAX_SNAPSHOT_LIFETIME),
                        Duration.ofSeconds(30),
                        Clock.fixed(ENCODE_TIME, ZoneOffset.UTC)));
    }

    private static IdentitySnapshotContent content() {
        return new IdentitySnapshotContent(
                ACTOR,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "jwt",
                CAPTURED_AT,
                Optional.empty(),
                List.of(),
                "rest:authenticated",
                CAPTURED_AT);
    }

    @Test
    @DisplayName("F5: a fire time beyond the signed effective expiry is rejected under FAIL policy")
    void doomedExpirySnapshotRejectedUnderFail() {
        IdentitySnapshotDurableEncoder encoder =
                new IdentitySnapshotDurableEncoder(newCodec(), IdentitySnapshotDegradationPolicy.FAIL);
        // effective expiry == CAPTURED_AT + MAX_SNAPSHOT_LIFETIME; fireTime is well beyond it.
        Instant doomedFireTime = CAPTURED_AT.plus(MAX_SNAPSHOT_LIFETIME).plusSeconds(60);
        DurableEncodeContext context =
                new DurableEncodeContext("delayed-job", Optional.of(CARRIER), Optional.of(doomedFireTime));

        DurableEncodeRejectedException thrown = assertThrows(
                DurableEncodeRejectedException.class,
                () -> encoder.encode(IdentitySnapshotContext.of(content()), context),
                "a snapshot whose effective expiry precedes the scheduled fire time must be rejected outright "
                        + "under FAIL policy, not silently persisted");
        assertTrue(
                thrown.getMessage().contains("onDegradation=FAIL"),
                "the rejection message must name the policy that caused the rejection");
    }

    @Test
    @DisplayName("F5: a fire time beyond the signed effective expiry enqueues without identity metadata "
            + "under CONTINUE_WITHOUT_IDENTITY policy")
    void doomedExpiryEnqueuesWithoutIdentity() {
        IdentitySnapshotDurableEncoder encoder = new IdentitySnapshotDurableEncoder(
                newCodec(), IdentitySnapshotDegradationPolicy.CONTINUE_WITHOUT_IDENTITY);
        Instant doomedFireTime = CAPTURED_AT.plus(MAX_SNAPSHOT_LIFETIME).plusSeconds(60);
        DurableEncodeContext context =
                new DurableEncodeContext("delayed-job", Optional.of(CARRIER), Optional.of(doomedFireTime));

        DurableMetadata result = encoder.encode(IdentitySnapshotContext.of(content()), context);

        assertTrue(
                result.isEmpty(),
                "a doomed snapshot under CONTINUE_WITHOUT_IDENTITY must encode to empty metadata — no "
                        + "identity-snapshot "
                        + "namespace persisted, so the row enqueues subjectless rather than with a snapshot "
                        + "guaranteed to fail closed at first decode");
        assertFalse(result.has(IdentitySnapshotDurableEncoder.NAMESPACE));
    }

    @Test
    @DisplayName("F5: a fire time within the signed effective expiry encodes normally (no false-positive rejection)")
    void fireTimeWithinExpirySignsNormally() {
        IdentitySnapshotDurableEncoder encoder =
                new IdentitySnapshotDurableEncoder(newCodec(), IdentitySnapshotDegradationPolicy.FAIL);
        Instant safeFireTime = CAPTURED_AT.plusSeconds(30);
        DurableEncodeContext context =
                new DurableEncodeContext("delayed-job", Optional.of(CARRIER), Optional.of(safeFireTime));

        DurableMetadata result = encoder.encode(IdentitySnapshotContext.of(content()), context);

        assertTrue(
                result.has(IdentitySnapshotDurableEncoder.NAMESPACE),
                "a fire time safely within the signed effective expiry must sign and encode normally");
    }

    @Test
    @DisplayName("F5: no fireTime on the encode context runs no doomed-window check (current behavior preserved)")
    void absentFireTimeSkipsDoomedCheck() {
        IdentitySnapshotDurableEncoder encoder =
                new IdentitySnapshotDurableEncoder(newCodec(), IdentitySnapshotDegradationPolicy.FAIL);
        DurableEncodeContext context = new DurableEncodeContext("delayed-job", Optional.of(CARRIER));

        DurableMetadata result = encoder.encode(IdentitySnapshotContext.of(content()), context);

        assertTrue(
                result.has(IdentitySnapshotDurableEncoder.NAMESPACE),
                "an absent fireTime must never trigger the doomed-window check, regardless of policy");
    }
}
