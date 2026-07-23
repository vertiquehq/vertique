// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.UnboundCorrelationContext;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.ReconstructionMarker;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.ReconstructedAuthorityMode;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.origin.RequestOrigin;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthorizationDecisionEvent}.
 *
 * <p>Verifies: happy-path construction with all fields; null rejection for each required field;
 * non-null Optional {@code origin} reference; accessor return values match inputs; and the derived
 * {@link AuthorizationDecisionEvent#authorityMode()} accessor's stamp-preferred,
 * marker-fallback resolution order.
 */
class AuthorizationDecisionEventTest {

    /** Minimal {@link SecurityContext} stub with a user actor. */
    private static final SecurityContext STUB_CTX = new SecurityContext() {
        private final SecurityIdentity identity =
                SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        private final AuthenticationState authentication = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());

        @Override
        public SecurityIdentity identity() {
            return identity;
        }

        @Override
        public AuthenticationState authentication() {
            return authentication;
        }

        @Override
        public AuthorizationClaims authorization() {
            return AuthorizationClaims.empty();
        }

        @Override
        public Optional<RequestOrigin> origin() {
            return Optional.empty();
        }
    };

    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T11:00:00Z");
    private static final CorrelationContext CORRELATION = EventTestFixtures.minimalCorrelation();
    private static final AuthorizationRequest REQUEST =
            new AuthorizationRequest(STUB_CTX, "READ", new ResourceRef("order", "order-42", Map.of()), Map.of());
    private static final AuthorizationDecision DECISION = AuthorizationDecision.permit("PERMITTED");

    // --- happy path ---

    @Test
    @DisplayName("constructs with origin present and accessors return expected values")
    void happyPathWithOrigin() {
        AuthorizationDecisionEvent event = new AuthorizationDecisionEvent(
                OCCURRED_AT, CORRELATION, Optional.of(EventTestFixtures.minimalRequestOrigin()), REQUEST, DECISION);

        assertEquals(OCCURRED_AT, event.occurredAt());
        assertEquals(CORRELATION, event.correlation());
        assertTrue(event.origin().isPresent());
        assertEquals(REQUEST, event.request());
        assertEquals(DECISION, event.decision());
    }

    @Test
    @DisplayName("constructs with empty origin Optional")
    void happyPathEmptyOrigin() {
        AuthorizationDecisionEvent event =
                new AuthorizationDecisionEvent(OCCURRED_AT, CORRELATION, Optional.empty(), REQUEST, DECISION);

        assertFalse(event.origin().isPresent());
    }

    @Test
    @DisplayName("embedsInvocationOrigin: an event built over a request with a seeded origin exposes it via "
            + "invocationOrigin() and via the embedded request().origin()")
    void embedsInvocationOrigin() {
        InvocationOrigin seeded = InvocationOrigin.of("rest");
        AuthorizationRequest requestWithOrigin = new AuthorizationRequest(
                STUB_CTX, "READ", new ResourceRef("order", "order-42", Map.of()), seeded, Map.of());
        AuthorizationDecisionEvent event =
                new AuthorizationDecisionEvent(OCCURRED_AT, CORRELATION, Optional.empty(), requestWithOrigin, DECISION);

        assertEquals(seeded, event.invocationOrigin());
        assertEquals(seeded, event.request().origin());
    }

    // --- null rejection ---

    @Nested
    @DisplayName("null rejection")
    class NullRejection {

        @Test
        @DisplayName("rejects null occurredAt")
        void rejectsNullOccurredAt() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthorizationDecisionEvent(null, CORRELATION, Optional.empty(), REQUEST, DECISION));
        }

        @Test
        @DisplayName("rejects null correlation")
        void rejectsNullCorrelation() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthorizationDecisionEvent(OCCURRED_AT, null, Optional.empty(), REQUEST, DECISION));
        }

        @Test
        @DisplayName("rejects null origin Optional reference")
        void rejectsNullOriginOptional() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthorizationDecisionEvent(OCCURRED_AT, CORRELATION, null, REQUEST, DECISION));
        }

        @Test
        @DisplayName("rejects null request")
        void rejectsNullRequest() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthorizationDecisionEvent(OCCURRED_AT, CORRELATION, Optional.empty(), null, DECISION));
        }

        @Test
        @DisplayName("rejects null decision")
        void rejectsNullDecision() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthorizationDecisionEvent(OCCURRED_AT, CORRELATION, Optional.empty(), REQUEST, null));
        }
    }

    // --- unbound correlation sentinel (relocated from vertique-core's UnboundCorrelationContextTest) ---

    @Nested
    @DisplayName("unbound correlation sentinel")
    class UnboundCorrelationSentinel {

        @Test
        @DisplayName("builds successfully with the UnboundCorrelationContext sentinel")
        void buildsWithSentinel() {
            AuthorizationDecisionEvent event = assertDoesNotThrow(() -> new AuthorizationDecisionEvent(
                    Instant.now(),
                    UnboundCorrelationContext.INSTANCE,
                    Optional.empty(),
                    new AuthorizationRequest(
                            STUB_CTX, "cms.content.read", new ResourceRef("cms", "doc-1", Map.of()), Map.of()),
                    AuthorizationDecision.deny("ACTION_NOT_ALLOWED")));

            assertNotNull(event);
            assertSame(UnboundCorrelationContext.INSTANCE, event.correlation());
            assertEquals("unavailable", event.correlation().requestId().value());
            assertEquals("unavailable", event.correlation().correlationId().value());
        }
    }

    // --- authorityMode() derivation (stamp-preferred, marker fallback) ---

    @Nested
    @DisplayName("authorityMode derivation")
    class AuthorityModeDerivation {

        @Test
        @DisplayName("a CAPTURED-marked reconstructed context with no decision stamp yields CAPTURED via the "
                + "intrinsic marker fallback")
        void carriesCapturedAuthorityMode() {
            SecurityContext capturedCtx = reconstructedCtx(ReconstructedAuthorityMode.CAPTURED);
            AuthorizationRequest capturedRequest = new AuthorizationRequest(
                    capturedCtx, "READ", new ResourceRef("order", "order-42", Map.of()), Map.of());
            AuthorizationDecisionEvent event = new AuthorizationDecisionEvent(
                    OCCURRED_AT, CORRELATION, Optional.empty(), capturedRequest, DECISION);

            assertEquals(Optional.of(ReconstructedAuthorityMode.CAPTURED), event.authorityMode());
        }

        @Test
        @DisplayName("an explicit LIVE_RESOLVED decision stamp is preferred even over a non-reconstructed request")
        void reflectsLiveResolvedStampFromDecision() {
            AuthorizationDecision stampedDecision = new AuthorizationDecision(
                    true,
                    "PERMITTED",
                    Optional.empty(),
                    Optional.empty(),
                    Map.of(
                            ReconstructedAuthorityMode.DECISION_ATTRIBUTE,
                            ReconstructedAuthorityMode.LIVE_RESOLVED.name()));
            AuthorizationDecisionEvent event = new AuthorizationDecisionEvent(
                    OCCURRED_AT, CORRELATION, Optional.empty(), REQUEST, stampedDecision);

            assertEquals(Optional.of(ReconstructedAuthorityMode.LIVE_RESOLVED), event.authorityMode());
        }

        @Test
        @DisplayName("a non-reconstructed request with no decision stamp yields an empty authorityMode")
        void nonReconstructedNoStampIsEmpty() {
            AuthorizationDecisionEvent event =
                    new AuthorizationDecisionEvent(OCCURRED_AT, CORRELATION, Optional.empty(), REQUEST, DECISION);

            assertTrue(event.authorityMode().isEmpty());
        }

        private static SecurityContext reconstructedCtx(ReconstructedAuthorityMode mode) {
            SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
            AuthenticationState auth = new AuthenticationState(
                    DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
            return SecurityContexts.assembleReconstructed(
                    identity, auth, AuthorizationClaims.empty(), Optional.empty(), new ReconstructionMarker(mode));
        }
    }
}
