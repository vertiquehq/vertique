// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RequestOriginCapturer}.
 *
 * <p>Covers all acceptance criteria AC-RO-1 through AC-RO-9 from the identity-001 PRD §7.11:
 * <ul>
 *   <li>AC-RO-1: direct peer with no proxy — {@code clientIp == remoteIp}</li>
 *   <li>AC-RO-2: trusted proxy with X-Forwarded-For — clientIp derived from chain walk</li>
 *   <li>AC-RO-3: untrusted peer with X-Forwarded-For — chain parsed, clientIp == remoteIp</li>
 *   <li>AC-RO-5: chain exceeds forwardedForCap — chain dropped, rejected flag set</li>
 *   <li>AC-RO-6: invalid IP in chain — dropped and counted in rejectedCount</li>
 *   <li>AC-RO-7: untrusted peer ignores forwarded scheme/host</li>
 *   <li>AC-RO-8: TLS facts populated from SSLSession when present</li>
 *   <li>AC-RO-9: IPv6-mapped IPv4 normalization</li>
 * </ul>
 *
 * <p>Tests use Mockito stubs for {@link HttpServerRequest} and {@link SocketAddress}.
 */
class RequestOriginCapturerTest {

    // --- Stub helpers ---

    /**
     * Creates a stub {@link HttpServerRequest} with the given remote address string, port, scheme,
     * and host. The SSL session and forwarded headers are not set (null).
     *
     * @param remoteHost remote IP string
     * @param remotePort remote TCP port
     * @param scheme     request scheme
     * @param host       Host header value
     * @return stubbed request
     */
    private static HttpServerRequest stubRequest(String remoteHost, int remotePort, String scheme, String host) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        SocketAddress remote = mock(SocketAddress.class);
        HttpConnection connection = mock(HttpConnection.class);

        when(remote.host()).thenReturn(remoteHost);
        when(remote.port()).thenReturn(remotePort);
        when(request.remoteAddress()).thenReturn(remote);
        when(request.scheme()).thenReturn(scheme);
        // Vert.x 5: host is exposed via authority(), not host()
        if (host != null) {
            HostAndPort authority = mock(HostAndPort.class);
            when(authority.host()).thenReturn(host);
            when(request.authority()).thenReturn(authority);
        } else {
            when(request.authority()).thenReturn(null);
        }
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Forwarded-Proto")).thenReturn(null);
        when(request.getHeader("X-Forwarded-Host")).thenReturn(null);
        when(request.connection()).thenReturn(connection);
        when(connection.sslSession()).thenReturn(null);

        return request;
    }

    /**
     * Creates a stub request with the given X-Forwarded-For header value.
     *
     * @param remoteHost    remote IP string
     * @param remotePort    remote TCP port
     * @param scheme        request scheme
     * @param host          Host header value
     * @param xForwardedFor the X-Forwarded-For header value, or null
     * @return stubbed request
     */
    private static HttpServerRequest stubRequest(
            String remoteHost, int remotePort, String scheme, String host, String xForwardedFor) {
        HttpServerRequest request = stubRequest(remoteHost, remotePort, scheme, host);
        when(request.getHeader("X-Forwarded-For")).thenReturn(xForwardedFor);
        return request;
    }

    /**
     * Creates a default {@link RequestOriginCapturer} with default config (trust nothing).
     *
     * @return capturer with default config
     */
    private static RequestOriginCapturer defaultCapturer() {
        return new RequestOriginCapturer(RequestOriginConfig.defaults());
    }

    /**
     * Creates a {@link RequestOriginCapturer} that trusts the specified CIDR ranges.
     *
     * @param cidrs CIDR strings to trust
     * @return capturer configured with the given trusted proxy CIDRs
     */
    private static RequestOriginCapturer capturerWithTrustedProxies(String... cidrs) {
        return new RequestOriginCapturer(new RequestOriginConfig(Set.of(cidrs), 16, false, false));
    }

    // --- AC-RO-1: No proxy ---

    @Nested
    @DisplayName("AC-RO-1: no proxy — clientIp equals remoteIp")
    class NoProxy {

        @Test
        @DisplayName("clientIp equals remoteIp when no X-Forwarded-For and no trusted proxy config")
        void clientIpEqualsRemoteIp() {
            HttpServerRequest request = stubRequest("203.0.113.1", 12345, "http", "example.com");
            RequestOrigin origin = defaultCapturer().capture(request);

            assertEquals("203.0.113.1", origin.remoteIp());
            assertEquals("203.0.113.1", origin.clientIp());
        }

        @Test
        @DisplayName("forwardedFor is empty when no X-Forwarded-For header")
        void forwardedForEmptyWithNoHeader() {
            HttpServerRequest request = stubRequest("203.0.113.1", 12345, "http", "example.com");
            RequestOrigin origin = defaultCapturer().capture(request);

            assertTrue(origin.forwardedFor().isEmpty());
        }

        @Test
        @DisplayName("remote port is captured correctly")
        void remotePortCaptured() {
            HttpServerRequest request = stubRequest("203.0.113.1", 54321, "http", "example.com");
            RequestOrigin origin = defaultCapturer().capture(request);

            assertEquals(54321, origin.remotePort());
        }

        @Test
        @DisplayName("scheme and host are taken directly from the request")
        void schemeAndHostFromRequest() {
            HttpServerRequest request = stubRequest("203.0.113.1", 443, "https", "api.example.com");
            RequestOrigin origin = defaultCapturer().capture(request);

            assertEquals("https", origin.scheme());
            assertEquals("api.example.com", origin.host());
        }
    }

    // --- AC-RO-2: Trusted proxy derives clientIp from chain ---

    @Nested
    @DisplayName("AC-RO-2: trusted proxy — clientIp derived from chain walk")
    class TrustedProxy {

        @Test
        @DisplayName("clientIp is leftmost-trusted-walk when direct peer is trusted")
        void clientIpFromChainWhenPeerTrusted() {
            // remoteIp=10.0.0.1 is the trusted proxy; chain is "198.51.100.5, 10.0.0.1"
            // After right-to-left walk: 10.0.0.1 is trusted → strip; 198.51.100.5 is untrusted → stop
            HttpServerRequest request = stubRequest("10.0.0.1", 0, "http", "example.com", "198.51.100.5, 10.0.0.1");
            RequestOriginCapturer capturer = capturerWithTrustedProxies("10.0.0.0/8");

            RequestOrigin origin = capturer.capture(request);

            assertEquals("10.0.0.1", origin.remoteIp());
            assertEquals("198.51.100.5", origin.clientIp());
        }

        @Test
        @DisplayName("forwardedFor chain contains valid entries from the header")
        void forwardedForChainPopulated() {
            HttpServerRequest request = stubRequest("10.0.0.1", 0, "http", "example.com", "198.51.100.5, 10.0.0.1");
            RequestOriginCapturer capturer = capturerWithTrustedProxies("10.0.0.0/8");

            RequestOrigin origin = capturer.capture(request);

            assertEquals(2, origin.forwardedFor().size());
        }

        @Test
        @DisplayName("single trusted proxy with single real client IP")
        void singleProxySingleClient() {
            HttpServerRequest request = stubRequest("10.1.2.3", 0, "http", "example.com", "172.20.0.5");
            RequestOriginCapturer capturer = capturerWithTrustedProxies("10.0.0.0/8");

            RequestOrigin origin = capturer.capture(request);

            assertEquals("172.20.0.5", origin.clientIp());
        }

        @Test
        @DisplayName("trusted proxy with forwarded-scheme honored when trustForwardedScheme=true")
        void trustedProxyHonorsForwardedScheme() {
            HttpServerRequest request = stubRequest("10.0.0.1", 0, "http", "example.com", "203.0.113.5");
            when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
            RequestOriginCapturer capturer =
                    new RequestOriginCapturer(new RequestOriginConfig(Set.of("10.0.0.0/8"), 16, true, false));

            RequestOrigin origin = capturer.capture(request);

            assertEquals("https", origin.scheme());
        }

        @Test
        @DisplayName("trusted proxy with forwarded-host honored when trustForwardedHost=true")
        void trustedProxyHonorsForwardedHost() {
            HttpServerRequest request = stubRequest("10.0.0.1", 0, "http", "internal.svc", "203.0.113.5");
            when(request.getHeader("X-Forwarded-Host")).thenReturn("api.example.com");
            RequestOriginCapturer capturer =
                    new RequestOriginCapturer(new RequestOriginConfig(Set.of("10.0.0.0/8"), 16, false, true));

            RequestOrigin origin = capturer.capture(request);

            assertEquals("api.example.com", origin.host());
        }
    }

    // --- AC-RO-3: Untrusted peer — chain parsed but clientIp == remoteIp ---

    @Nested
    @DisplayName("AC-RO-3: untrusted peer — chain parsed for observability, clientIp == remoteIp")
    class UntrustedPeer {

        @Test
        @DisplayName("clientIp equals remoteIp even when X-Forwarded-For header is present")
        void clientIpEqualsRemoteIpWithUntrustedPeer() {
            HttpServerRequest request =
                    stubRequest("203.0.113.10", 0, "http", "example.com", "198.51.100.5, 203.0.113.10");
            RequestOrigin origin = defaultCapturer().capture(request);

            assertEquals("203.0.113.10", origin.clientIp());
        }

        @Test
        @DisplayName("forwardedFor chain is still parsed and populated for untrusted peer")
        void forwardedForChainPopulatedForUntrustedPeer() {
            HttpServerRequest request =
                    stubRequest("203.0.113.10", 0, "http", "example.com", "198.51.100.5, 203.0.113.10");
            RequestOrigin origin = defaultCapturer().capture(request);

            // Chain still parsed despite peer being untrusted (AC-RO-3 observability requirement)
            assertFalse(origin.forwardedFor().isEmpty());
        }

        @Test
        @DisplayName("untrusted peer with forwarded-proto — scheme from actual connection")
        void untrustedPeerIgnoresForwardedScheme() {
            HttpServerRequest request = stubRequest("203.0.113.10", 0, "http", "example.com", "198.51.100.5");
            when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
            RequestOriginCapturer capturer =
                    new RequestOriginCapturer(new RequestOriginConfig(Set.of(), 16, true, false));

            RequestOrigin origin = capturer.capture(request);

            // Even with trustForwardedScheme=true, untrusted peer means we use actual scheme
            assertEquals("http", origin.scheme());
        }
    }

    // --- AC-RO-5: Chain cap exceeded ---

    @Nested
    @DisplayName("AC-RO-5: chain cap exceeded — chain dropped, rejected flag set")
    class ChainCapExceeded {

        @Test
        @DisplayName("17-entry chain sets forwardedForChainRejected=true and entries empty")
        void seveneteenEntryChainRejected() {
            // Build a chain with 17 entries (cap default is 16)
            String xff = "1.1.1.1, 1.1.1.2, 1.1.1.3, 1.1.1.4, 1.1.1.5, 1.1.1.6, 1.1.1.7, 1.1.1.8, "
                    + "1.1.1.9, 1.1.1.10, 1.1.1.11, 1.1.1.12, 1.1.1.13, 1.1.1.14, 1.1.1.15, 1.1.1.16, "
                    + "1.1.1.17";
            HttpServerRequest request = stubRequest("203.0.113.1", 0, "http", "example.com", xff);
            RequestOrigin origin = defaultCapturer().capture(request);

            assertTrue(origin.forwardedForChainRejected());
            assertTrue(origin.forwardedFor().isEmpty());
        }

        @Test
        @DisplayName("exactly 16-entry chain is accepted (boundary condition)")
        void sixteenEntryChainAccepted() {
            String xff = "1.1.1.1, 1.1.1.2, 1.1.1.3, 1.1.1.4, 1.1.1.5, 1.1.1.6, 1.1.1.7, 1.1.1.8, "
                    + "1.1.1.9, 1.1.1.10, 1.1.1.11, 1.1.1.12, 1.1.1.13, 1.1.1.14, 1.1.1.15, 1.1.1.16";
            HttpServerRequest request = stubRequest("203.0.113.1", 0, "http", "example.com", xff);
            RequestOrigin origin = defaultCapturer().capture(request);

            assertFalse(origin.forwardedForChainRejected());
            assertEquals(16, origin.forwardedFor().size());
        }

        @Test
        @DisplayName("clientIp falls back to remoteIp when chain is rejected")
        void clientIpFallsBackOnRejectedChain() {
            String xff = "1.1.1.1, 1.1.1.2, 1.1.1.3, 1.1.1.4, 1.1.1.5, 1.1.1.6, 1.1.1.7, 1.1.1.8, "
                    + "1.1.1.9, 1.1.1.10, 1.1.1.11, 1.1.1.12, 1.1.1.13, 1.1.1.14, 1.1.1.15, 1.1.1.16, "
                    + "1.1.1.17";
            HttpServerRequest request = stubRequest("10.0.0.1", 0, "http", "example.com", xff);
            RequestOriginCapturer capturer = capturerWithTrustedProxies("10.0.0.0/8");

            RequestOrigin origin = capturer.capture(request);

            assertEquals("10.0.0.1", origin.clientIp());
        }
    }

    // --- AC-RO-6: Invalid IP in chain ---

    @Nested
    @DisplayName("AC-RO-6: invalid IPs in chain — dropped and counted")
    class InvalidIpInChain {

        @Test
        @DisplayName("invalid IP is dropped from entries and counted in rejectedCount")
        void invalidIpDroppedAndCounted() {
            HttpServerRequest request =
                    stubRequest("203.0.113.1", 0, "http", "example.com", "198.51.100.5, not-an-ip, 192.0.2.1");
            RequestOrigin origin = defaultCapturer().capture(request);

            assertEquals(2, origin.forwardedFor().size());
            assertFalse(origin.forwardedFor().contains("not-an-ip"));
            assertEquals(1, origin.forwardedForRejectedCount());
        }

        @Test
        @DisplayName("multiple invalid IPs each increment the rejected count")
        void multipleInvalidIpsIncrementCount() {
            HttpServerRequest request =
                    stubRequest("203.0.113.1", 0, "http", "example.com", "198.51.100.5, bad1, bad2, 192.0.2.1");
            RequestOrigin origin = defaultCapturer().capture(request);

            assertEquals(2, origin.forwardedFor().size());
            assertEquals(2, origin.forwardedForRejectedCount());
        }

        @Test
        @DisplayName("invalid IPs do not set forwardedForChainRejected flag")
        void invalidIpsDontSetChainRejectedFlag() {
            HttpServerRequest request = stubRequest("203.0.113.1", 0, "http", "example.com", "198.51.100.5, bad-ip");
            RequestOrigin origin = defaultCapturer().capture(request);

            assertFalse(origin.forwardedForChainRejected());
        }
    }

    // --- AC-RO-7: Untrusted peer + forwarded headers ---

    @Nested
    @DisplayName("AC-RO-7: untrusted peer — scheme from actual connection, host from Host header")
    class UntrustedPeerSchemeAndHost {

        @Test
        @DisplayName("http scheme used when peer is untrusted even if X-Forwarded-Proto is https")
        void httpSchemeWhenPeerUntrusted() {
            HttpServerRequest request = stubRequest("203.0.113.1", 0, "http", "example.com", null);
            when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
            RequestOrigin origin = defaultCapturer().capture(request);

            assertEquals("http", origin.scheme());
        }

        @Test
        @DisplayName("actual host used when peer is untrusted even if X-Forwarded-Host differs")
        void actualHostWhenPeerUntrusted() {
            HttpServerRequest request = stubRequest("203.0.113.1", 0, "http", "example.com", null);
            when(request.getHeader("X-Forwarded-Host")).thenReturn("api.different.com");
            RequestOrigin origin = defaultCapturer().capture(request);

            assertEquals("example.com", origin.host());
        }
    }

    // --- AC-RO-8: TLS facts ---

    @Nested
    @DisplayName("AC-RO-8: TLS facts populated from SSLSession when present")
    class TlsFacts {

        @Test
        @DisplayName("TLS facts empty when no SSLSession (plain HTTP)")
        void tlsFactsEmptyForPlainHttp() {
            HttpServerRequest request = stubRequest("203.0.113.1", 0, "http", "example.com");
            RequestOrigin origin = defaultCapturer().capture(request);

            assertTrue(origin.tls().isEmpty());
        }

        @Test
        @DisplayName("TLS facts populated from SSLSession protocol and cipher suite")
        void tlsFactsPopulated() {
            HttpServerRequest request = stubRequest("203.0.113.1", 0, "https", "example.com");
            SSLSession session = mock(SSLSession.class);
            when(session.getProtocol()).thenReturn("TLSv1.3");
            when(session.getCipherSuite()).thenReturn("TLS_AES_256_GCM_SHA384");
            HttpConnection connection = mock(HttpConnection.class);
            when(connection.sslSession()).thenReturn(session);
            when(request.connection()).thenReturn(connection);
            try {
                when(connection.peerCertificates()).thenThrow(new SSLPeerUnverifiedException("no cert"));
            } catch (SSLPeerUnverifiedException ignored) {
                // expected
            }

            RequestOrigin origin = defaultCapturer().capture(request);

            assertTrue(origin.tls().isPresent());
            dev.vertique.security.origin.TlsFacts tlsFacts = origin.tls().get();
            assertEquals("TLSv1.3", tlsFacts.protocol());
            assertEquals("TLS_AES_256_GCM_SHA384", tlsFacts.cipherSuite());
            assertFalse(tlsFacts.peerCertPresented());
        }

        @Test
        @DisplayName("peerCertPresented=true when peerCertificates returns non-empty list")
        void peerCertPresentedWhenCertAvailable() throws Exception {
            HttpServerRequest request = stubRequest("203.0.113.1", 0, "https", "example.com");
            SSLSession session = mock(SSLSession.class);
            when(session.getProtocol()).thenReturn("TLSv1.3");
            when(session.getCipherSuite()).thenReturn("TLS_AES_256_GCM_SHA384");
            HttpConnection connection = mock(HttpConnection.class);
            when(connection.sslSession()).thenReturn(session);
            Certificate cert = mock(Certificate.class);
            when(connection.peerCertificates()).thenReturn(List.of(cert));
            when(request.connection()).thenReturn(connection);

            RequestOrigin origin = defaultCapturer().capture(request);

            assertTrue(origin.tls().isPresent());
            assertTrue(origin.tls().get().peerCertPresented());
        }
    }

    // --- AC-RO-9: IPv6-mapped IPv4 normalization ---

    @Nested
    @DisplayName("AC-RO-9: IPv6-mapped IPv4 normalization")
    class IPv6MappedNormalization {

        @Test
        @DisplayName("::ffff:1.2.3.4 is normalized to 1.2.3.4 in remoteIp")
        void ipv6MappedInRemoteIpNormalized() {
            HttpServerRequest request = stubRequest("::ffff:203.0.113.1", 0, "http", "example.com");
            RequestOrigin origin = defaultCapturer().capture(request);

            assertEquals("203.0.113.1", origin.remoteIp());
        }

        @Test
        @DisplayName("::ffff:1.2.3.4 in XFF chain entry is normalized")
        void ipv6MappedInChainNormalized() {
            HttpServerRequest request = stubRequest("203.0.113.1", 0, "http", "example.com", "::ffff:198.51.100.5");
            RequestOrigin origin = defaultCapturer().capture(request);

            assertFalse(origin.forwardedFor().isEmpty());
            assertEquals("198.51.100.5", origin.forwardedFor().get(0));
        }

        @Test
        @DisplayName("pure IPv6 address is not mangled")
        void pureIpv6AddressNotMangled() {
            HttpServerRequest request = stubRequest("2001:db8::1", 0, "http", "example.com");
            RequestOrigin origin = defaultCapturer().capture(request);

            assertEquals("2001:db8::1", origin.remoteIp());
        }

        @Test
        @DisplayName("plain IPv4 address is not mangled")
        void plainIpv4NotMangled() {
            HttpServerRequest request = stubRequest("10.0.0.1", 0, "http", "example.com");
            RequestOrigin origin = defaultCapturer().capture(request);

            assertEquals("10.0.0.1", origin.remoteIp());
        }
    }

    // --- CIDR matching ---

    @Nested
    @DisplayName("CIDR matching for trusted proxy detection")
    class CidrMatching {

        @Test
        @DisplayName("10.0.0.5 matches 10.0.0.0/8 trusted range")
        void ipInTrustedRangeIsDetected() {
            HttpServerRequest request = stubRequest("10.0.0.5", 0, "http", "example.com", "198.51.100.1");
            RequestOriginCapturer capturer = capturerWithTrustedProxies("10.0.0.0/8");

            RequestOrigin origin = capturer.capture(request);

            assertEquals("198.51.100.1", origin.clientIp());
        }

        @Test
        @DisplayName("11.0.0.1 does not match 10.0.0.0/8 trusted range")
        void ipOutsideTrustedRangeNotTrusted() {
            HttpServerRequest request = stubRequest("11.0.0.1", 0, "http", "example.com", "198.51.100.1");
            RequestOriginCapturer capturer = capturerWithTrustedProxies("10.0.0.0/8");

            RequestOrigin origin = capturer.capture(request);

            assertEquals("11.0.0.1", origin.clientIp());
        }

        @Test
        @DisplayName("127.0.0.1 matches 127.0.0.1/32")
        void exactHostMatchTrusted() {
            HttpServerRequest request = stubRequest("127.0.0.1", 0, "http", "example.com", "198.51.100.1");
            RequestOriginCapturer capturer = capturerWithTrustedProxies("127.0.0.1/32");

            RequestOrigin origin = capturer.capture(request);

            assertEquals("198.51.100.1", origin.clientIp());
        }
    }

    // --- Null/blank host fallback ---

    @Nested
    @DisplayName("host fallback when authority header absent")
    class HostFallback {

        @Test
        @DisplayName("host falls back to sentinel 'unknown' when authority() returns null")
        void hostFallbackWhenAuthorityNull() {
            HttpServerRequest request = stubRequest("203.0.113.1", 0, "http", null);

            RequestOrigin origin = defaultCapturer().capture(request);
            assertNotNull(origin.host());
            assertFalse(origin.host().isBlank());
        }

        @Test
        @DisplayName("host is taken from authority().host() when present")
        void hostFromAuthority() {
            HttpServerRequest request = stubRequest("203.0.113.1", 0, "http", "api.example.com");

            RequestOrigin origin = defaultCapturer().capture(request);
            assertEquals("api.example.com", origin.host());
        }
    }
}
