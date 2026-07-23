// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.security.AuthMethod;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.verification.JwksVerificationSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthenticationEvidenceCollector}.
 *
 * <p>Uses a simple list-backed {@link SimpleEvidenceCollector} fixture defined in this file.
 * Verifies: {@code add()} followed by {@code evidence()} returns the same instance; multiple
 * adds preserve insertion order; the list returned from {@code evidence()} is immutable.
 */
class AuthenticationEvidenceCollectorTest {

    // --- list-backed fixture ---

    /**
     * Minimal list-backed implementation of {@link AuthenticationEvidenceCollector} used
     * as a test fixture.
     */
    private static final class SimpleEvidenceCollector implements AuthenticationEvidenceCollector {

        private final List<AuthenticationEvidence> items = new ArrayList<>();

        @Override
        public void add(AuthenticationEvidence evidence) {
            items.add(evidence);
        }

        @Override
        public List<AuthenticationEvidence> evidence() {
            return List.copyOf(items);
        }
    }

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final AuthMethod JWT = DefaultAuthMethod.jwt();
    private static final JwksVerificationSource JWKS = new JwksVerificationSource(
            Optional.of("https://idp.example.com"),
            Optional.of("https://idp.example.com/.well-known/jwks.json"),
            Optional.of("key-001"),
            Optional.of("RS256"));

    private SimpleEvidenceCollector collector;

    @BeforeEach
    void setUp() {
        collector = new SimpleEvidenceCollector();
    }

    private AuthenticationEvidence makeEvidence(String credentialId) {
        return new AuthenticationEvidence(JWT, Optional.of(credentialId), NOW, Optional.empty(), JWKS, Map.of());
    }

    // --- add and evidence ---

    @Test
    @DisplayName("add() then evidence() returns the added instance")
    void addThenEvidenceReturnsSameInstance() {
        AuthenticationEvidence ev = makeEvidence("cred-1");
        collector.add(ev);

        List<AuthenticationEvidence> result = collector.evidence();
        assertEquals(1, result.size());
        assertEquals(ev, result.get(0));
    }

    // --- insertion order ---

    @Test
    @DisplayName("multiple adds preserve insertion order")
    void multipleAddsPreserveInsertionOrder() {
        AuthenticationEvidence ev1 = makeEvidence("cred-1");
        AuthenticationEvidence ev2 = makeEvidence("cred-2");
        AuthenticationEvidence ev3 = makeEvidence("cred-3");

        collector.add(ev1);
        collector.add(ev2);
        collector.add(ev3);

        List<AuthenticationEvidence> result = collector.evidence();
        assertEquals(3, result.size());
        assertEquals(ev1, result.get(0));
        assertEquals(ev2, result.get(1));
        assertEquals(ev3, result.get(2));
    }

    // --- immutability ---

    @Test
    @DisplayName("returned evidence list from evidence() is immutable")
    void returnedListIsImmutable() {
        collector.add(makeEvidence("cred-1"));

        List<AuthenticationEvidence> result = collector.evidence();
        assertThrows(UnsupportedOperationException.class, () -> result.add(makeEvidence("cred-x")));
    }

    @Test
    @DisplayName("empty collector returns immutable empty list")
    void emptyCollectorReturnsImmutableEmptyList() {
        List<AuthenticationEvidence> result = collector.evidence();
        assertEquals(0, result.size());
        assertThrows(UnsupportedOperationException.class, () -> result.add(makeEvidence("cred-x")));
    }
}
