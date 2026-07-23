// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.security.DelegationGrant;
import dev.vertique.security.DelegationGrantDecision;
import dev.vertique.security.DelegationReasonCodes;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import io.vertx.core.Future;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InMemoryDelegationGrantValidator} — the framework-shipped default
 * {@link dev.vertique.security.DelegationGrantValidator} (PRD identity-002 FR-ID-DG-003).
 *
 * <p>Verifies the actor=grantee / subject=grantor direction, the expiry and scope checks the
 * framework default ships, and the fail-closed guarantee: an internal evaluation failure (including a
 * backing-store lookup failure) always resolves to a <em>succeeded</em> future carrying a
 * {@link DelegationReasonCodes#GRANT_LOOKUP_FAILED} deny, never a failed future.
 */
class DelegationGrantValidatorTest {

    private static final PrincipalRef GRANTOR = new PrincipalRef(PrincipalType.USER, "user-1", Map.of());
    private static final PrincipalRef GRANTEE = new PrincipalRef(PrincipalType.SERVICE, "svc-1", Map.of());
    private static final String SCOPE_KIND = "cms.content";
    private static final String SCOPE_REF = "article-42";
    private static final String GRANT_ID = "grant-1";
    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    private static Clock fixedAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static DelegationGrant grant(Instant expiresAt) {
        return new DelegationGrant(GRANT_ID, GRANTOR, GRANTEE, SCOPE_KIND, SCOPE_REF, expiresAt, "consent-record-99");
    }

    private static DelegationGrantDecision await(Future<DelegationGrantDecision> future) {
        assertTrue(future.succeeded(), "validate() must always return an already-succeeded future");
        return future.result();
    }

    @Nested
    @DisplayName("validGrantPermits")
    class ValidGrantPermits {

        @Test
        @DisplayName("a live, in-scope, unexpired grant permits with reason GRANT_VALID")
        void validGrantPermits() {
            Instant expiresAt = NOW.plus(Duration.ofHours(1));
            InMemoryDelegationGrantValidator validator =
                    new InMemoryDelegationGrantValidator(List.of(grant(expiresAt)), fixedAt(NOW));

            DelegationGrantDecision decision =
                    await(validator.validate(GRANTEE, GRANTOR, SCOPE_KIND, SCOPE_REF, GRANT_ID));

            assertTrue(decision.permitted());
            assertEquals(DelegationReasonCodes.GRANT_VALID, decision.reasonCode());
            assertEquals(GRANT_ID, decision.grantId());
            assertEquals(expiresAt, decision.expiryUsed().orElseThrow());
        }
    }

    @Nested
    @DisplayName("expiredGrantDenies")
    class ExpiredGrantDenies {

        @Test
        @DisplayName("a grant past its expiresAt denies with reason GRANT_EXPIRED")
        void expiredGrantDenies() {
            Instant expiresAt = NOW.minus(Duration.ofMinutes(1));
            InMemoryDelegationGrantValidator validator =
                    new InMemoryDelegationGrantValidator(List.of(grant(expiresAt)), fixedAt(NOW));

            DelegationGrantDecision decision =
                    await(validator.validate(GRANTEE, GRANTOR, SCOPE_KIND, SCOPE_REF, GRANT_ID));

            assertFalse(decision.permitted());
            assertEquals(DelegationReasonCodes.GRANT_EXPIRED, decision.reasonCode());
            assertEquals(GRANT_ID, decision.grantId());
            assertEquals(expiresAt, decision.expiryUsed().orElseThrow());
        }
    }

    @Nested
    @DisplayName("outOfScopeDenies")
    class OutOfScopeDenies {

        @Test
        @DisplayName("a grant that exists but has a mismatched scopeRef denies with reason GRANT_OUT_OF_SCOPE")
        void outOfScopeDenies() {
            Instant expiresAt = NOW.plus(Duration.ofHours(1));
            InMemoryDelegationGrantValidator validator =
                    new InMemoryDelegationGrantValidator(List.of(grant(expiresAt)), fixedAt(NOW));

            DelegationGrantDecision decision =
                    await(validator.validate(GRANTEE, GRANTOR, SCOPE_KIND, "different-article", GRANT_ID));

            assertFalse(decision.permitted());
            assertEquals(DelegationReasonCodes.GRANT_OUT_OF_SCOPE, decision.reasonCode());
            assertEquals(GRANT_ID, decision.grantId());
        }

        @Test
        @DisplayName("a grant that exists but has a mismatched actor/subject direction denies with reason "
                + "GRANT_OUT_OF_SCOPE")
        void actorSubjectDirectionMismatchDenies() {
            Instant expiresAt = NOW.plus(Duration.ofHours(1));
            InMemoryDelegationGrantValidator validator =
                    new InMemoryDelegationGrantValidator(List.of(grant(expiresAt)), fixedAt(NOW));

            // Reversed direction: actor is the grantor, subject is the grantee — not what the grant permits.
            DelegationGrantDecision decision =
                    await(validator.validate(GRANTOR, GRANTEE, SCOPE_KIND, SCOPE_REF, GRANT_ID));

            assertFalse(decision.permitted());
            assertEquals(DelegationReasonCodes.GRANT_OUT_OF_SCOPE, decision.reasonCode());
        }
    }

    @Nested
    @DisplayName("lookupFailureFailsClosed")
    class LookupFailureFailsClosed {

        @Test
        @DisplayName("a grant source that throws on lookup resolves to a succeeded future denying with reason "
                + "GRANT_LOOKUP_FAILED, never a failed future")
        @SuppressWarnings("unchecked")
        void lookupFailureFailsClosed() {
            Map<String, DelegationGrant> throwingSource = mock(Map.class);
            when(throwingSource.get(any())).thenThrow(new RuntimeException("backing store unreachable"));
            InMemoryDelegationGrantValidator validator =
                    new InMemoryDelegationGrantValidator(throwingSource, fixedAt(NOW));

            Future<DelegationGrantDecision> future =
                    validator.validate(GRANTEE, GRANTOR, SCOPE_KIND, SCOPE_REF, GRANT_ID);

            assertTrue(future.succeeded(), "a lookup failure must never surface as a failed future");
            DelegationGrantDecision decision = future.result();
            assertFalse(decision.permitted());
            assertEquals(DelegationReasonCodes.GRANT_LOOKUP_FAILED, decision.reasonCode());
            assertEquals(GRANT_ID, decision.grantId());
            assertTrue(decision.expiryUsed().isEmpty());
        }
    }

    @Nested
    @DisplayName("unknown grant id")
    class UnknownGrant {

        @Test
        @DisplayName("no grant with the requested id denies with reason GRANT_NOT_FOUND")
        void unknownGrantIdDenies() {
            InMemoryDelegationGrantValidator validator = new InMemoryDelegationGrantValidator(List.of(), fixedAt(NOW));

            DelegationGrantDecision decision =
                    await(validator.validate(GRANTEE, GRANTOR, SCOPE_KIND, SCOPE_REF, "missing-grant"));

            assertFalse(decision.permitted());
            assertEquals(DelegationReasonCodes.GRANT_NOT_FOUND, decision.reasonCode());
            assertTrue(decision.expiryUsed().isEmpty());
        }
    }
}
