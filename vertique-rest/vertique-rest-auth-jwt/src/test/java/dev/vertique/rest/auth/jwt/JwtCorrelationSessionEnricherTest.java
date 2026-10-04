// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.correlation.CorrelationSessionRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JwtCorrelationSessionEnricher} claim selection and ref shape (FR-COR-100..105).
 */
class JwtCorrelationSessionEnricherTest {

    @Test
    @DisplayName("prefers sid over jti with kind jwt-sid, source jwt-claim, durableSafe=false")
    void prefersSid() {
        JwtCorrelationSessionEnricher enricher =
                new JwtCorrelationSessionEnricher(JwtSessionCorrelationConfig.defaults());

        Optional<CorrelationSessionRef> ref =
                enricher.enrich(Map.of("sid", "session-1", "jti", "token-1", "sub", "user-1"));

        assertTrue(ref.isPresent());
        assertEquals("session-1", ref.get().id());
        assertEquals("jwt-sid", ref.get().kind());
        assertEquals("jwt-claim", ref.get().source());
        assertEquals("sid", ref.get().claimName());
        assertFalse(ref.get().durableSafe());
    }

    @Test
    @DisplayName("falls back to jti when sid is absent")
    void fallsBackToJti() {
        JwtCorrelationSessionEnricher enricher =
                new JwtCorrelationSessionEnricher(JwtSessionCorrelationConfig.defaults());

        Optional<CorrelationSessionRef> ref = enricher.enrich(Map.of("jti", "token-1"));

        assertTrue(ref.isPresent());
        assertEquals("token-1", ref.get().id());
        assertEquals("jwt-jti", ref.get().kind());
        assertEquals("jti", ref.get().claimName());
    }

    @Test
    @DisplayName("honors custom claim preference and durableSafe opt-in")
    void customClaimAndDurableSafe() {
        JwtSessionCorrelationConfig config = new JwtSessionCorrelationConfig(List.of("session_id", "sid"), true, true);
        JwtCorrelationSessionEnricher enricher = new JwtCorrelationSessionEnricher(config);

        Optional<CorrelationSessionRef> ref = enricher.enrich(Map.of("session_id", "custom-1", "sid", "session-1"));

        assertTrue(ref.isPresent());
        assertEquals("custom-1", ref.get().id());
        assertEquals("jwt-claim", ref.get().kind());
        assertEquals("session_id", ref.get().claimName());
        assertTrue(ref.get().durableSafe());
    }

    @Test
    @DisplayName("disabled config yields empty")
    void disabled() {
        JwtSessionCorrelationConfig config = new JwtSessionCorrelationConfig(List.of("sid"), false, false);
        JwtCorrelationSessionEnricher enricher = new JwtCorrelationSessionEnricher(config);

        assertTrue(enricher.enrich(Map.of("sid", "session-1")).isEmpty());
    }

    @Test
    @DisplayName("non-string or blank claim values are skipped")
    void skipsNonStringAndBlank() {
        JwtCorrelationSessionEnricher enricher =
                new JwtCorrelationSessionEnricher(JwtSessionCorrelationConfig.defaults());

        assertTrue(enricher.enrich(Map.of("sid", 123, "jti", "  ")).isEmpty());
    }
}
