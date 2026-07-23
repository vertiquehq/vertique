// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.origin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RequestOrigin}.
 *
 * <p>Verifies: happy-path no-proxy and proxy construction; validation rejects null/blank
 * {@code remoteIp}, {@code clientIp}, {@code scheme}, {@code host}; port range enforcement;
 * {@code forwardedFor == null} treated as empty list; {@code forwardedForRejectedCount < 0}
 * rejected; null {@code tls} Optional rejected; defensive copy of {@code forwardedFor} —
 * mutating the source after construction does not affect the record, and the returned list is
 * unmodifiable; {@code forwardedForChainRejected=true} with populated or empty forwardedFor
 * is legal at the record level.
 */
class RequestOriginTest {

    // --- happy path: no-proxy case ---

    @Test
    @DisplayName("constructs direct (no-proxy) RequestOrigin with TLS facts")
    void happyPathDirect() {
        TlsFacts tls = new TlsFacts("TLSv1.3", "TLS_AES_256_GCM_SHA384", false);
        RequestOrigin origin = new RequestOrigin(
                "1.2.3.4", 12345, List.of(), 0, false, "1.2.3.4", "https", "api.example.com", Optional.of(tls));

        assertEquals("1.2.3.4", origin.remoteIp());
        assertEquals(12345, origin.remotePort());
        assertTrue(origin.forwardedFor().isEmpty());
        assertEquals(0, origin.forwardedForRejectedCount());
        assertFalse(origin.forwardedForChainRejected());
        assertEquals("1.2.3.4", origin.clientIp());
        assertEquals("https", origin.scheme());
        assertEquals("api.example.com", origin.host());
        assertTrue(origin.tls().isPresent());
        assertEquals(tls, origin.tls().get());
    }

    @Test
    @DisplayName("constructs RequestOrigin without TLS (plain HTTP)")
    void happyPathNoTls() {
        RequestOrigin origin = new RequestOrigin(
                "10.0.0.1", 8080, List.of(), 0, false, "10.0.0.1", "http", "internal.example.com", Optional.empty());

        assertFalse(origin.tls().isPresent());
    }

    // --- happy path: proxy case ---

    @Test
    @DisplayName("constructs proxy RequestOrigin with forwardedFor chain")
    void happyPathProxy() {
        RequestOrigin origin = new RequestOrigin(
                "172.16.0.1",
                443,
                List.of("10.0.0.1", "203.0.113.5"),
                0,
                false,
                "203.0.113.5",
                "https",
                "api.example.com",
                Optional.empty());

        assertEquals(List.of("10.0.0.1", "203.0.113.5"), origin.forwardedFor());
        assertEquals("203.0.113.5", origin.clientIp());
    }

    // --- port boundary values ---

    @Test
    @DisplayName("port 0 is valid (lower bound)")
    void portZeroValid() {
        RequestOrigin origin = buildOrigin("1.2.3.4", 0, List.of(), 0, false, "1.2.3.4", "https", "h.example.com");
        assertEquals(0, origin.remotePort());
    }

    @Test
    @DisplayName("port 65535 is valid (upper bound)")
    void port65535Valid() {
        RequestOrigin origin = buildOrigin("1.2.3.4", 65535, List.of(), 0, false, "1.2.3.4", "https", "h.example.com");
        assertEquals(65535, origin.remotePort());
    }

    // --- null remoteIp rejection ---

    @Test
    @DisplayName("null remoteIp throws NullPointerException with message containing \"remoteIp\"")
    void nullRemoteIpThrowsNpe() {
        NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> new RequestOrigin(
                        null, 1234, List.of(), 0, false, "1.2.3.4", "https", "h.example.com", Optional.empty()));
        assertTrue(
                ex.getMessage().contains("remoteIp"),
                "NPE message should mention 'remoteIp' but was: " + ex.getMessage());
    }

    @Test
    @DisplayName("blank remoteIp throws IllegalArgumentException")
    void blankRemoteIpThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RequestOrigin(
                        "  ", 1234, List.of(), 0, false, "1.2.3.4", "https", "h.example.com", Optional.empty()));
    }

    @Test
    @DisplayName("empty remoteIp throws IllegalArgumentException")
    void emptyRemoteIpThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RequestOrigin(
                        "", 1234, List.of(), 0, false, "1.2.3.4", "https", "h.example.com", Optional.empty()));
    }

    // --- port range rejection ---

    @Test
    @DisplayName("remotePort < 0 throws IllegalArgumentException")
    void negativePortThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RequestOrigin(
                        "1.2.3.4", -1, List.of(), 0, false, "1.2.3.4", "https", "h.example.com", Optional.empty()));
    }

    @Test
    @DisplayName("remotePort > 65535 throws IllegalArgumentException")
    void portTooHighThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RequestOrigin(
                        "1.2.3.4", 65536, List.of(), 0, false, "1.2.3.4", "https", "h.example.com", Optional.empty()));
    }

    // --- null forwardedFor treated as empty ---

    @Test
    @DisplayName("null forwardedFor treated as empty list — no NullPointerException thrown")
    void nullForwardedForTreatedAsEmpty() {
        RequestOrigin origin = new RequestOrigin(
                "1.2.3.4", 443, null, 0, false, "1.2.3.4", "https", "h.example.com", Optional.empty());
        assertTrue(origin.forwardedFor().isEmpty());
    }

    // --- forwardedForRejectedCount rejection ---

    @Test
    @DisplayName("forwardedForRejectedCount < 0 throws IllegalArgumentException")
    void negativeRejectedCountThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RequestOrigin(
                        "1.2.3.4", 443, List.of(), -1, false, "1.2.3.4", "https", "h.example.com", Optional.empty()));
    }

    @Test
    @DisplayName("forwardedForRejectedCount = 0 is valid")
    void zeroRejectedCountValid() {
        RequestOrigin origin = buildOrigin("1.2.3.4", 443, List.of(), 0, false, "1.2.3.4", "https", "h.example.com");
        assertEquals(0, origin.forwardedForRejectedCount());
    }

    @Test
    @DisplayName("forwardedForRejectedCount > 0 is valid")
    void positiveRejectedCountValid() {
        RequestOrigin origin = buildOrigin("1.2.3.4", 443, List.of(), 3, false, "1.2.3.4", "https", "h.example.com");
        assertEquals(3, origin.forwardedForRejectedCount());
    }

    // --- null clientIp rejection ---

    @Test
    @DisplayName("null clientIp throws NullPointerException with message containing \"clientIp\"")
    void nullClientIpThrowsNpe() {
        NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> new RequestOrigin(
                        "1.2.3.4", 443, List.of(), 0, false, null, "https", "h.example.com", Optional.empty()));
        assertTrue(
                ex.getMessage().contains("clientIp"),
                "NPE message should mention 'clientIp' but was: " + ex.getMessage());
    }

    @Test
    @DisplayName("blank clientIp throws IllegalArgumentException")
    void blankClientIpThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RequestOrigin(
                        "1.2.3.4", 443, List.of(), 0, false, "   ", "https", "h.example.com", Optional.empty()));
    }

    // --- null scheme rejection ---

    @Test
    @DisplayName("null scheme throws NullPointerException with message containing \"scheme\"")
    void nullSchemeThrowsNpe() {
        NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> new RequestOrigin(
                        "1.2.3.4", 443, List.of(), 0, false, "1.2.3.4", null, "h.example.com", Optional.empty()));
        assertTrue(
                ex.getMessage().contains("scheme"), "NPE message should mention 'scheme' but was: " + ex.getMessage());
    }

    @Test
    @DisplayName("blank scheme throws IllegalArgumentException")
    void blankSchemeThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RequestOrigin(
                        "1.2.3.4", 443, List.of(), 0, false, "1.2.3.4", "  ", "h.example.com", Optional.empty()));
    }

    // --- null host rejection ---

    @Test
    @DisplayName("null host throws NullPointerException with message containing \"host\"")
    void nullHostThrowsNpe() {
        NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> new RequestOrigin(
                        "1.2.3.4", 443, List.of(), 0, false, "1.2.3.4", "https", null, Optional.empty()));
        assertTrue(ex.getMessage().contains("host"), "NPE message should mention 'host' but was: " + ex.getMessage());
    }

    @Test
    @DisplayName("blank host throws IllegalArgumentException")
    void blankHostThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RequestOrigin("1.2.3.4", 443, List.of(), 0, false, "1.2.3.4", "https", "", Optional.empty()));
    }

    // --- null tls Optional rejected ---

    @Test
    @DisplayName("null tls Optional throws NullPointerException with message containing \"tls\"")
    void nullTlsOptionalThrowsNpe() {
        NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> new RequestOrigin(
                        "1.2.3.4", 443, List.of(), 0, false, "1.2.3.4", "https", "h.example.com", null));
        assertTrue(ex.getMessage().contains("tls"), "NPE message should mention 'tls' but was: " + ex.getMessage());
    }

    // --- defensive copy of forwardedFor ---

    @Test
    @DisplayName("mutating source forwardedFor list after construction does not affect record")
    void defensivelyCopiesForwardedFor() {
        List<String> mutable = new ArrayList<>();
        mutable.add("10.0.0.1");
        RequestOrigin origin = buildOrigin("1.2.3.4", 443, mutable, 0, false, "1.2.3.4", "https", "h.example.com");
        mutable.add("injected");
        assertEquals(1, origin.forwardedFor().size(), "forwardedFor must not reflect mutation of source list");
    }

    @Test
    @DisplayName("forwardedFor list returned by accessor is unmodifiable")
    void forwardedForIsUnmodifiable() {
        RequestOrigin origin =
                buildOrigin("1.2.3.4", 443, List.of("10.0.0.1"), 0, false, "1.2.3.4", "https", "h.example.com");
        assertThrows(
                UnsupportedOperationException.class, () -> origin.forwardedFor().add("evil"));
    }

    // --- forwardedForChainRejected=true is legal at record level ---

    @Test
    @DisplayName("forwardedForChainRejected=true with non-empty forwardedFor is valid")
    void chainRejectedWithForwardedForPopulatedIsValid() {
        // Chain still populated for forensic logging — record level allows it
        RequestOrigin origin = buildOrigin(
                "1.2.3.4", 443, List.of("203.0.113.5", "10.0.0.1"), 17, true, "1.2.3.4", "https", "h.example.com");
        assertTrue(origin.forwardedForChainRejected());
        assertFalse(origin.forwardedFor().isEmpty());
    }

    @Test
    @DisplayName("forwardedForChainRejected=true with empty forwardedFor is valid")
    void chainRejectedWithEmptyForwardedForIsValid() {
        RequestOrigin origin = buildOrigin("1.2.3.4", 443, List.of(), 17, true, "1.2.3.4", "https", "h.example.com");
        assertTrue(origin.forwardedForChainRejected());
        assertTrue(origin.forwardedFor().isEmpty());
    }

    // --- equals / hashCode ---

    @Test
    @DisplayName("two RequestOrigins with same fields are equal")
    void equalityHolds() {
        RequestOrigin a = buildOrigin("1.2.3.4", 443, List.of(), 0, false, "1.2.3.4", "https", "h.example.com");
        RequestOrigin b = buildOrigin("1.2.3.4", 443, List.of(), 0, false, "1.2.3.4", "https", "h.example.com");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // --- helpers ---

    /**
     * Constructs a {@link RequestOrigin} with no TLS using the supplied fields. Reduces verbosity
     * in tests that only need to vary one parameter.
     */
    private static RequestOrigin buildOrigin(
            String remoteIp,
            int remotePort,
            List<String> forwardedFor,
            int forwardedForRejectedCount,
            boolean forwardedForChainRejected,
            String clientIp,
            String scheme,
            String host) {
        return new RequestOrigin(
                remoteIp,
                remotePort,
                forwardedFor,
                forwardedForRejectedCount,
                forwardedForChainRejected,
                clientIp,
                scheme,
                host,
                Optional.empty());
    }
}
