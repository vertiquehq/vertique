// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.context.DurableTarget;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotCarrierBinding;
import dev.vertique.security.authz.AuthorizationClaims;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CapturedAuthorityActivatedEvent}.
 *
 * <p>Verifies compact-constructor null rejection for each required component. Seam behavior
 * (emit-and-await ordering, mode stamping, per-activation id, uncollapsed identity) lives in
 * {@code vertique-security-runtime}.
 */
class CapturedAuthorityActivatedEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-07-01T10:15:32Z");
    private static final CorrelationContext CORRELATION = EventTestFixtures.minimalCorrelation();
    private static final AuthenticationState AUTHENTICATION = new AuthenticationState(
            DefaultAuthMethod.custom("jwt"), List.of(), Optional.empty(), Optional.empty(), Map.of());
    private static final SecurityIdentity IDENTITY = new SecurityIdentity(
            new PrincipalRef(PrincipalType.SERVICE, "svc-scheduler", Map.of("tenant", "acme")),
            Optional.of(new PrincipalRef(PrincipalType.USER, "user-42", Map.of("realm", "acme-realm"))),
            Optional.empty(),
            Optional.empty());
    private static final AuthorizationClaims AUTHORIZATION = AuthorizationClaims.empty();
    private static final CapturedAuthorityActivatedEvent.Mode MODE = CapturedAuthorityActivatedEvent.Mode.RESUME;
    private static final UUID ACTIVATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final SnapshotCarrierBinding CARRIER =
            new SnapshotCarrierBinding("carrier-1", new DurableTarget("outbox", "orders", Optional.empty()));

    @Nested
    @DisplayName("null rejection")
    class NullRejection {

        @Test
        @DisplayName("rejects null occurredAt")
        void rejectsNullOccurredAt() {
            NullPointerException failure = assertThrows(
                    NullPointerException.class,
                    () -> new CapturedAuthorityActivatedEvent(
                            null,
                            CORRELATION,
                            Optional.empty(),
                            AUTHENTICATION,
                            IDENTITY,
                            AUTHORIZATION,
                            MODE,
                            ACTIVATION_ID,
                            CARRIER));
            assertEquals("occurredAt", failure.getMessage(), "the rejection must name the offending component");
        }

        @Test
        @DisplayName("rejects null correlation")
        void rejectsNullCorrelation() {
            NullPointerException failure = assertThrows(
                    NullPointerException.class,
                    () -> new CapturedAuthorityActivatedEvent(
                            OCCURRED_AT,
                            null,
                            Optional.empty(),
                            AUTHENTICATION,
                            IDENTITY,
                            AUTHORIZATION,
                            MODE,
                            ACTIVATION_ID,
                            CARRIER));
            assertEquals("correlation", failure.getMessage(), "the rejection must name the offending component");
        }

        @Test
        @DisplayName("rejects null origin Optional reference")
        void rejectsNullOriginOptional() {
            NullPointerException failure = assertThrows(
                    NullPointerException.class,
                    () -> new CapturedAuthorityActivatedEvent(
                            OCCURRED_AT,
                            CORRELATION,
                            null,
                            AUTHENTICATION,
                            IDENTITY,
                            AUTHORIZATION,
                            MODE,
                            ACTIVATION_ID,
                            CARRIER));
            assertEquals("origin", failure.getMessage(), "the rejection must name the offending component");
        }

        @Test
        @DisplayName("rejects null authentication")
        void rejectsNullAuthentication() {
            NullPointerException failure = assertThrows(
                    NullPointerException.class,
                    () -> new CapturedAuthorityActivatedEvent(
                            OCCURRED_AT,
                            CORRELATION,
                            Optional.empty(),
                            null,
                            IDENTITY,
                            AUTHORIZATION,
                            MODE,
                            ACTIVATION_ID,
                            CARRIER));
            assertEquals("authentication", failure.getMessage(), "the rejection must name the offending component");
        }

        @Test
        @DisplayName("rejects null identity")
        void rejectsNullIdentity() {
            NullPointerException failure = assertThrows(
                    NullPointerException.class,
                    () -> new CapturedAuthorityActivatedEvent(
                            OCCURRED_AT,
                            CORRELATION,
                            Optional.empty(),
                            AUTHENTICATION,
                            null,
                            AUTHORIZATION,
                            MODE,
                            ACTIVATION_ID,
                            CARRIER));
            assertEquals("identity", failure.getMessage(), "the rejection must name the offending component");
        }

        @Test
        @DisplayName("the compact constructor rejects null activated authority — an audit record must never claim an "
                + "activation occurred without stating which privileges it granted")
        void constructorRejectsNullAuthorization() {
            NullPointerException failure = assertThrows(
                    NullPointerException.class,
                    () -> new CapturedAuthorityActivatedEvent(
                            OCCURRED_AT,
                            CORRELATION,
                            Optional.empty(),
                            AUTHENTICATION,
                            IDENTITY,
                            null,
                            MODE,
                            ACTIVATION_ID,
                            CARRIER),
                    "null activated authority must be rejected at construction, never carried onto an audit record");
            assertEquals("authorization", failure.getMessage(), "the rejection must name the offending component");
        }

        @Test
        @DisplayName("the compact constructor rejects a null activation mode — the invariant an unchecked "
                + "safeAttributes marker could not enforce")
        void constructorRejectsNullMode() {
            NullPointerException failure = assertThrows(
                    NullPointerException.class,
                    () -> new CapturedAuthorityActivatedEvent(
                            OCCURRED_AT,
                            CORRELATION,
                            Optional.empty(),
                            AUTHENTICATION,
                            IDENTITY,
                            AUTHORIZATION,
                            null,
                            ACTIVATION_ID,
                            CARRIER),
                    "a null activation mode must be rejected at construction, never carried onto an audit record");
            assertEquals("mode", failure.getMessage(), "the rejection must name the offending component");
        }

        @Test
        @DisplayName("rejects null activationId")
        void rejectsNullActivationId() {
            NullPointerException failure = assertThrows(
                    NullPointerException.class,
                    () -> new CapturedAuthorityActivatedEvent(
                            OCCURRED_AT,
                            CORRELATION,
                            Optional.empty(),
                            AUTHENTICATION,
                            IDENTITY,
                            AUTHORIZATION,
                            MODE,
                            null,
                            CARRIER));
            assertEquals("activationId", failure.getMessage(), "the rejection must name the offending component");
        }

        @Test
        @DisplayName("rejects null carrier")
        void rejectsNullCarrier() {
            NullPointerException failure = assertThrows(
                    NullPointerException.class,
                    () -> new CapturedAuthorityActivatedEvent(
                            OCCURRED_AT,
                            CORRELATION,
                            Optional.empty(),
                            AUTHENTICATION,
                            IDENTITY,
                            AUTHORIZATION,
                            MODE,
                            ACTIVATION_ID,
                            null));
            assertEquals("carrier", failure.getMessage(), "the rejection must name the offending component");
        }
    }
}
