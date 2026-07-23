// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.verification.JwksVerificationSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthenticationState}.
 *
 * <p>Verifies: construction with required {@code primaryMethod} and evidence; null rejections;
 * {@code earliestNotAfter()} across multiple evidence scenarios; defensive copies of evidence
 * list and safeAttributes map.
 */
class AuthenticationStateTest {

    private static final AuthMethod JWT_METHOD = DefaultAuthMethod.jwt();
    private static final AuthMethod MTLS_METHOD = DefaultAuthMethod.mtls();
    private static final JwksVerificationSource JWKS_SOURCE = new JwksVerificationSource(
            Optional.of("https://idp.example.com"),
            Optional.of("https://idp.example.com/.well-known/jwks.json"),
            Optional.of("key-001"),
            Optional.of("RS256"));
    private static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-01T13:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-01T14:00:00Z");

    private static AuthenticationEvidence evidenceWithNotAfter(AuthMethod method, Instant notAfter) {
        return new AuthenticationEvidence(method, Optional.empty(), T0, Optional.of(notAfter), JWKS_SOURCE, Map.of());
    }

    private static AuthenticationEvidence evidenceWithoutNotAfter(AuthMethod method) {
        return new AuthenticationEvidence(method, Optional.empty(), T0, Optional.empty(), JWKS_SOURCE, Map.of());
    }

    // --- construction ---

    @Test
    @DisplayName("constructs with required primaryMethod and one evidence entry")
    void basicConstruction() {
        AuthenticationEvidence evidence = evidenceWithNotAfter(JWT_METHOD, T1);
        AuthenticationState state =
                new AuthenticationState(JWT_METHOD, List.of(evidence), Optional.empty(), Optional.empty(), Map.of());

        assertEquals(JWT_METHOD, state.primaryMethod());
        assertEquals(1, state.evidence().size());
        assertFalse(state.assurance().isPresent());
        assertFalse(state.tokens().isPresent());
    }

    @Test
    @DisplayName("constructs with assurance and tokens")
    void constructionWithFullOptionals() {
        AuthenticationAssurance assurance = new AuthenticationAssurance(
                Optional.of("urn:mace:incommon:iap:bronze"), null, Optional.empty(), Optional.empty());
        TokenAttributes tokens = new TokenAttributes(Optional.empty(), Optional.empty(), Optional.empty());

        AuthenticationState state = new AuthenticationState(
                JWT_METHOD, List.of(), Optional.of(assurance), Optional.of(tokens), Map.of("session", "abc"));

        assertTrue(state.assurance().isPresent());
        assertTrue(state.tokens().isPresent());
        assertEquals("abc", state.safeAttributes().get("session"));
    }

    // --- null checks ---

    @Nested
    @DisplayName("null rejection")
    class NullRejection {

        @Test
        @DisplayName("rejects null primaryMethod")
        void rejectsNullPrimaryMethod() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthenticationState(null, List.of(), Optional.empty(), Optional.empty(), Map.of()));
        }

        @Test
        @DisplayName("rejects null assurance Optional")
        void rejectsNullAssurance() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthenticationState(JWT_METHOD, List.of(), null, Optional.empty(), Map.of()));
        }

        @Test
        @DisplayName("rejects null tokens Optional")
        void rejectsNullTokens() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthenticationState(JWT_METHOD, List.of(), Optional.empty(), null, Map.of()));
        }
    }

    // --- earliestNotAfter() ---

    @Nested
    @DisplayName("earliestNotAfter()")
    class EarliestNotAfter {

        @Test
        @DisplayName("returns empty when evidence list is empty")
        void emptyEvidenceList() {
            AuthenticationState state =
                    new AuthenticationState(JWT_METHOD, List.of(), Optional.empty(), Optional.empty(), Map.of());

            assertFalse(state.earliestNotAfter().isPresent());
        }

        @Test
        @DisplayName("returns empty when all evidence entries have empty notAfter")
        void allEvidenceWithoutNotAfter() {
            AuthenticationState state = new AuthenticationState(
                    JWT_METHOD,
                    List.of(evidenceWithoutNotAfter(JWT_METHOD), evidenceWithoutNotAfter(MTLS_METHOD)),
                    Optional.empty(),
                    Optional.empty(),
                    Map.of());

            assertFalse(state.earliestNotAfter().isPresent());
        }

        @Test
        @DisplayName("returns notAfter when exactly one evidence has it")
        void singleEvidenceWithNotAfter() {
            AuthenticationState state = new AuthenticationState(
                    JWT_METHOD,
                    List.of(evidenceWithNotAfter(JWT_METHOD, T1)),
                    Optional.empty(),
                    Optional.empty(),
                    Map.of());

            assertEquals(Optional.of(T1), state.earliestNotAfter());
        }

        @Test
        @DisplayName("returns earliest notAfter across mixed evidence list")
        void mixedEvidenceReturnsEarliest() {
            // T1 < T2; one evidence without notAfter; should return T1
            AuthenticationState state = new AuthenticationState(
                    JWT_METHOD,
                    List.of(
                            evidenceWithNotAfter(JWT_METHOD, T2),
                            evidenceWithoutNotAfter(MTLS_METHOD),
                            evidenceWithNotAfter(MTLS_METHOD, T1)),
                    Optional.empty(),
                    Optional.empty(),
                    Map.of());

            assertEquals(Optional.of(T1), state.earliestNotAfter());
        }

        @Test
        @DisplayName("returns empty when only evidence without notAfter remains in mix")
        void onlyEvidenceWithoutNotAfterInMix() {
            AuthenticationState state = new AuthenticationState(
                    JWT_METHOD,
                    List.of(evidenceWithoutNotAfter(JWT_METHOD), evidenceWithoutNotAfter(MTLS_METHOD)),
                    Optional.empty(),
                    Optional.empty(),
                    Map.of());

            assertFalse(state.earliestNotAfter().isPresent());
        }
    }

    // --- defensive copies ---

    @Test
    @DisplayName("evidence list is a defensive copy — mutations to original list do not affect record")
    void evidenceListDefensiveCopy() {
        AuthenticationEvidence ev1 = evidenceWithNotAfter(JWT_METHOD, T1);
        AuthenticationEvidence ev2 = evidenceWithNotAfter(MTLS_METHOD, T2);
        List<AuthenticationEvidence> mutable = new ArrayList<>();
        mutable.add(ev1);
        AuthenticationState state =
                new AuthenticationState(JWT_METHOD, mutable, Optional.empty(), Optional.empty(), Map.of());

        mutable.add(ev2);
        assertEquals(1, state.evidence().size());
    }

    @Test
    @DisplayName("null evidence list treated as empty list")
    void nullEvidenceListTreatedAsEmpty() {
        AuthenticationState state =
                new AuthenticationState(JWT_METHOD, null, Optional.empty(), Optional.empty(), Map.of());

        assertTrue(state.evidence().isEmpty());
    }

    @Test
    @DisplayName("safeAttributes is a defensive copy")
    void safeAttributesDefensiveCopy() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v1");
        AuthenticationState state =
                new AuthenticationState(JWT_METHOD, List.of(), Optional.empty(), Optional.empty(), mutable);

        mutable.put("k", "v2");
        assertEquals("v1", state.safeAttributes().get("k"));
    }

    @Test
    @DisplayName("null safeAttributes treated as empty map")
    void nullSafeAttributesTreatedAsEmpty() {
        AuthenticationState state =
                new AuthenticationState(JWT_METHOD, List.of(), Optional.empty(), Optional.empty(), null);

        assertTrue(state.safeAttributes().isEmpty());
    }
}
