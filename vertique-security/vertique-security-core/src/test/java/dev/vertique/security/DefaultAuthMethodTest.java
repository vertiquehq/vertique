// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultAuthMethod} factory methods.
 *
 * <p>Verifies that each factory returns an instance with the expected {@code id},
 * {@link AuthMethodKind}, and an empty {@code attributes} map (where applicable).
 */
class DefaultAuthMethodTest {

    // --- HMAC factory ---

    @Test
    void hmacFactoryReturnsCorrectId() {
        assertEquals("hmac", DefaultAuthMethod.hmac().id());
    }

    @Test
    void hmacFactoryReturnsCorrectKind() {
        assertEquals(AuthMethodKind.HMAC, DefaultAuthMethod.hmac().normalizedKind());
    }

    @Test
    void hmacFactoryReturnsEmptyAttributes() {
        assertTrue(DefaultAuthMethod.hmac().attributes().isEmpty());
    }

    // --- Parity checks for other factories ---

    @Test
    void noneFactory() {
        AuthMethod m = DefaultAuthMethod.none();
        assertEquals("none", m.id());
        assertEquals(AuthMethodKind.NONE, m.normalizedKind());
        assertTrue(m.attributes().isEmpty());
    }

    @Test
    void jwtFactory() {
        AuthMethod m = DefaultAuthMethod.jwt();
        assertEquals("jwt", m.id());
        assertEquals(AuthMethodKind.JWT, m.normalizedKind());
        assertTrue(m.attributes().isEmpty());
    }

    @Test
    void apiKeyFactory() {
        AuthMethod m = DefaultAuthMethod.apiKey();
        assertEquals("api_key", m.id());
        assertEquals(AuthMethodKind.API_KEY, m.normalizedKind());
        assertTrue(m.attributes().isEmpty());
    }

    @Test
    void basicFactory() {
        AuthMethod m = DefaultAuthMethod.basic();
        assertEquals("basic", m.id());
        assertEquals(AuthMethodKind.BASIC, m.normalizedKind());
        assertTrue(m.attributes().isEmpty());
    }

    @Test
    void mtlsFactory() {
        AuthMethod m = DefaultAuthMethod.mtls();
        assertEquals("mtls", m.id());
        assertEquals(AuthMethodKind.MTLS, m.normalizedKind());
        assertTrue(m.attributes().isEmpty());
    }

    @Test
    void customFactoryWithIdOnly() {
        AuthMethod m = DefaultAuthMethod.custom("my-auth");
        assertEquals("my-auth", m.id());
        assertEquals(AuthMethodKind.CUSTOM, m.normalizedKind());
        assertTrue(m.attributes().isEmpty());
    }

    @Test
    void unknownFactory() {
        AuthMethod m = DefaultAuthMethod.unknown();
        assertEquals("unknown", m.id());
        assertEquals(AuthMethodKind.UNKNOWN, m.normalizedKind());
        assertTrue(m.attributes().isEmpty());
    }
}
