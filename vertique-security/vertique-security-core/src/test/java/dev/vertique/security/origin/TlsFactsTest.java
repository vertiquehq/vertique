// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.origin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TlsFacts}.
 *
 * <p>Verifies: happy-path construction with typical protocol/cipher values; null {@code protocol}
 * rejected with {@link NullPointerException}; null {@code cipherSuite} rejected with NPE;
 * empty-string {@code protocol} and {@code cipherSuite} accepted (handshake may not yet be fully
 * populated); {@code peerCertPresented} true and false both valid.
 */
class TlsFactsTest {

    // --- happy path ---

    @Test
    @DisplayName("constructs TlsFacts with typical TLS 1.3 values")
    void happyPathTls13() {
        TlsFacts facts = new TlsFacts("TLSv1.3", "TLS_AES_256_GCM_SHA384", false);
        assertEquals("TLSv1.3", facts.protocol());
        assertEquals("TLS_AES_256_GCM_SHA384", facts.cipherSuite());
        assertFalse(facts.peerCertPresented());
    }

    @Test
    @DisplayName("constructs TlsFacts with peer certificate presented")
    void happyPathWithPeerCert() {
        TlsFacts facts = new TlsFacts("TLSv1.3", "TLS_AES_128_GCM_SHA256", true);
        assertEquals("TLSv1.3", facts.protocol());
        assertEquals("TLS_AES_128_GCM_SHA256", facts.cipherSuite());
        assertTrue(facts.peerCertPresented());
    }

    @Test
    @DisplayName("constructs TlsFacts with TLS 1.2 cipher")
    void happyPathTls12() {
        TlsFacts facts = new TlsFacts("TLSv1.2", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", false);
        assertEquals("TLSv1.2", facts.protocol());
        assertEquals("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", facts.cipherSuite());
    }

    // --- null rejection ---

    @Test
    @DisplayName("null protocol throws NullPointerException with message containing \"protocol\"")
    void nullProtocolThrowsNpe() {
        NullPointerException ex =
                assertThrows(NullPointerException.class, () -> new TlsFacts(null, "TLS_AES_256_GCM_SHA384", false));
        assertTrue(
                ex.getMessage().contains("protocol"),
                "NPE message should mention 'protocol' but was: " + ex.getMessage());
    }

    @Test
    @DisplayName("null cipherSuite throws NullPointerException with message containing \"cipherSuite\"")
    void nullCipherSuiteThrowsNpe() {
        NullPointerException ex = assertThrows(NullPointerException.class, () -> new TlsFacts("TLSv1.3", null, false));
        assertTrue(
                ex.getMessage().contains("cipherSuite"),
                "NPE message should mention 'cipherSuite' but was: " + ex.getMessage());
    }

    // --- empty string accepted ---

    @Test
    @DisplayName("empty-string protocol accepted — handshake may not yet be fully populated")
    void emptyProtocolAccepted() {
        TlsFacts facts = new TlsFacts("", "TLS_AES_256_GCM_SHA384", false);
        assertEquals("", facts.protocol());
    }

    @Test
    @DisplayName("empty-string cipherSuite accepted — handshake may not yet be fully populated")
    void emptyCipherSuiteAccepted() {
        TlsFacts facts = new TlsFacts("TLSv1.3", "", false);
        assertEquals("", facts.cipherSuite());
    }

    @Test
    @DisplayName("both protocol and cipherSuite empty-string accepted")
    void bothEmptyStringAccepted() {
        TlsFacts facts = new TlsFacts("", "", false);
        assertEquals("", facts.protocol());
        assertEquals("", facts.cipherSuite());
    }

    // --- peerCertPresented both booleans valid ---

    @Test
    @DisplayName("peerCertPresented=false is valid")
    void peerCertPresentedFalse() {
        TlsFacts facts = new TlsFacts("TLSv1.3", "TLS_AES_256_GCM_SHA384", false);
        assertFalse(facts.peerCertPresented());
    }

    @Test
    @DisplayName("peerCertPresented=true is valid")
    void peerCertPresentedTrue() {
        TlsFacts facts = new TlsFacts("TLSv1.3", "TLS_AES_256_GCM_SHA384", true);
        assertTrue(facts.peerCertPresented());
    }

    // --- equals / hashCode ---

    @Test
    @DisplayName("two TlsFacts with same fields are equal")
    void equalityHolds() {
        TlsFacts a = new TlsFacts("TLSv1.3", "TLS_AES_256_GCM_SHA384", false);
        TlsFacts b = new TlsFacts("TLSv1.3", "TLS_AES_256_GCM_SHA384", false);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
