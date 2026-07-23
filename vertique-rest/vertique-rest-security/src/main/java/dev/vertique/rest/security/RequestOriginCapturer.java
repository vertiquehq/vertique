// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.security.origin.TlsFacts;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.InetAddress;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Builds a {@link RequestOrigin} from an inbound {@link HttpServerRequest} by applying the
 * trusted-proxy CIDR policy from {@link RequestOriginConfig}.
 *
 * <p>Implements the trusted-proxy + forwarded-header rules from PRD identity-001 §7.11
 * (AC-RO-1..9):
 * <ul>
 *   <li><strong>AC-RO-1</strong>: No proxy configured — {@code clientIp == remoteIp}.</li>
 *   <li><strong>AC-RO-2</strong>: Trusted proxy — {@code clientIp} is the leftmost untrusted
 *       entry obtained by walking the {@code X-Forwarded-For} chain right-to-left, peeling off
 *       trusted entries.</li>
 *   <li><strong>AC-RO-3</strong>: Untrusted peer — chain is still parsed and exposed in
 *       {@code forwardedFor} for observability; {@code clientIp == remoteIp}.</li>
 *   <li><strong>AC-RO-5</strong>: If raw XFF entry count exceeds
 *       {@link RequestOriginConfig#forwardedForCap()}, the entire chain is discarded
 *       ({@code forwardedForChainRejected=true}, {@code entries=[]}). The {@code rejectedCount}
 *       reflects the raw entry count.</li>
 *   <li><strong>AC-RO-6</strong>: Invalid IPs (parse failure) are silently dropped; each
 *       increments {@code forwardedForRejectedCount}; they do not cause chain rejection.</li>
 *   <li><strong>AC-RO-7</strong>: Untrusted peer — scheme and host are taken from the actual
 *       connection, ignoring {@code X-Forwarded-Proto} / {@code X-Forwarded-Host}.</li>
 *   <li><strong>AC-RO-8</strong>: TLS facts are populated from {@code SSLSession} when present,
 *       regardless of trust configuration.</li>
 *   <li><strong>AC-RO-9</strong>: IPv6-mapped IPv4 addresses (e.g., {@code ::ffff:1.2.3.4}) are
 *       normalized to their plain IPv4 form in both {@code remoteIp} and chain entries.</li>
 * </ul>
 *
 * <p>This class is {@code @Singleton} and stateless beyond the injected config.
 */
@Singleton
public final class RequestOriginCapturer {

    /**
     * Pattern that matches IP address literals (IPv4, IPv6, IPv4-mapped IPv6) without performing
     * DNS resolution. Used as a guard before calling {@link InetAddress#getByName(String)} to
     * ensure the call never triggers a blocking DNS lookup on the event loop.
     *
     * <p>Groups covered:
     * <ul>
     *   <li>IPv4: four dot-separated decimal octets</li>
     *   <li>IPv6: colon-hex notation with optional zone id ({@code %scope})</li>
     *   <li>IPv4-mapped IPv6: {@code ::ffff:} prefix followed by four decimal octets</li>
     * </ul>
     */
    private static final Pattern IP_LITERAL = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}"
            + "|[0-9a-fA-F:]+(?:%[a-zA-Z0-9._~-]+)?"
            + "|::ffff:\\d{1,3}(?:\\.\\d{1,3}){3}");

    private final RequestOriginConfig config;
    private final List<CidrMatcher> trustedProxies;

    /**
     * Creates a new {@link RequestOriginCapturer} with the given configuration.
     *
     * @param config the trusted-proxy policy configuration; must not be {@code null}
     */
    @Inject
    public RequestOriginCapturer(RequestOriginConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.trustedProxies =
                config.trustedProxyCidrs().stream().map(CidrMatcher::parse).toList();
    }

    /**
     * Builds a {@link RequestOrigin} snapshot from the given inbound HTTP request.
     *
     * @param request the inbound HTTP server request; must not be {@code null}
     * @return a populated {@link RequestOrigin} snapshot; never {@code null}
     */
    public RequestOrigin capture(HttpServerRequest request) {
        String remoteIp = normalizeIp(request.remoteAddress().host());
        int remotePort = request.remoteAddress().port();
        boolean peerTrusted = isPeerTrusted(remoteIp);

        // Always parse X-Forwarded-For for observability (AC-RO-3).
        List<String> rawXff = parseXffHeader(request);
        ChainParseResult chain = sanitizeAndCapChain(rawXff);

        String clientIp;
        String scheme;
        String host;

        if (peerTrusted && !chain.rejected && !chain.entries.isEmpty()) {
            // Trusted peer: derive clientIp by right-to-left chain walk, peeling trusted entries.
            clientIp = derivClientIpFromChain(chain.entries, remoteIp);
            scheme = config.trustForwardedScheme()
                    ? Optional.ofNullable(request.getHeader("X-Forwarded-Proto"))
                            .orElse(resolveScheme(request))
                    : resolveScheme(request);
            host = config.trustForwardedHost()
                    ? Optional.ofNullable(request.getHeader("X-Forwarded-Host")).orElse(resolveHost(request))
                    : resolveHost(request);
        } else {
            clientIp = remoteIp;
            scheme = resolveScheme(request);
            host = resolveHost(request);
        }

        Optional<TlsFacts> tls = captureTls(request);

        return new RequestOrigin(
                remoteIp, remotePort, chain.entries, chain.rejectedCount, chain.rejected, clientIp, scheme, host, tls);
    }

    // --- Private helpers ---

    /**
     * Resolves the effective scheme from the request. Vert.x 5 exposes scheme via
     * {@link HttpServerRequest#scheme()}.
     *
     * @param request the inbound request
     * @return a non-blank scheme string; defaults to {@code "http"} when absent
     */
    private static String resolveScheme(HttpServerRequest request) {
        String scheme = request.scheme();
        if (scheme != null && !scheme.isBlank()) {
            return scheme;
        }
        return "http";
    }

    /**
     * Resolves the effective host from the request authority, falling back to a default sentinel
     * when the {@code Host} / {@code :authority} header is absent or blank.
     *
     * <p>Vert.x 5 exposes the host via {@link HttpServerRequest#authority()}, which returns the
     * {@code Host} header for HTTP/1.x and the {@code :authority} pseudo-header for HTTP/2.
     *
     * @param request the inbound request
     * @return a non-blank host string
     */
    private static String resolveHost(HttpServerRequest request) {
        var authority = request.authority();
        if (authority != null) {
            String host = authority.host();
            if (host != null && !host.isBlank()) {
                return host;
            }
        }
        // Final fallback: use a default sentinel so RequestOrigin validation passes.
        return "unknown";
    }

    /**
     * Normalizes an IP address string, converting IPv6-mapped IPv4 addresses
     * (e.g., {@code ::ffff:1.2.3.4}) to their plain IPv4 form (AC-RO-9).
     *
     * @param raw the raw IP string from the socket or XFF header entry
     * @return normalized IP string; {@code raw} unchanged if not an IPv6-mapped IPv4 address
     */
    static String normalizeIp(String raw) {
        if (!isIpLiteral(raw)) {
            // Not an IP literal — return unchanged so hostnames are never resolved on the event loop.
            return raw;
        }
        try {
            InetAddress addr = InetAddress.getByName(raw);
            byte[] bytes = addr.getAddress();

            // Case 1: JDK already resolved ::ffff:x.x.x.x to a 4-byte Inet4Address — return
            // the canonical dotted-decimal form so callers never see the ::ffff: prefix.
            if (bytes.length == 4) {
                return addr.getHostAddress();
            }

            // Case 2: 16-byte Inet6Address — check for IPv4-mapped pattern manually.
            // Format: first 10 bytes zero, bytes 10-11 are 0xFF 0xFF, bytes 12-15 are IPv4.
            if (isZeroRange(bytes, 0, 10) && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF) {
                return (bytes[12] & 0xFF)
                        + "."
                        + (bytes[13] & 0xFF)
                        + "."
                        + (bytes[14] & 0xFF)
                        + "."
                        + (bytes[15] & 0xFF);
            }

            // Pure IPv6 — return the raw string unchanged so addresses like "2001:db8::1" are
            // not expanded to their full form by InetAddress.getHostAddress().
            return raw;
        } catch (Exception e) {
            // Not a parseable IP address — return raw unchanged.
            return raw;
        }
    }

    /**
     * Returns {@code true} if {@code s} is an IP address literal (IPv4, IPv6, or IPv4-mapped IPv6)
     * that can be passed to {@link InetAddress#getByName(String)} without triggering a blocking DNS
     * lookup.
     *
     * <p>This guard must be checked before every runtime call to {@code getByName()} on the event
     * loop. Strings that fail the check (hostnames, blank values) are rejected or returned unchanged.
     *
     * @param s the string to test; may be {@code null}
     * @return {@code true} when {@code s} matches an IP literal pattern
     */
    static boolean isIpLiteral(String s) {
        return s != null && IP_LITERAL.matcher(s).matches();
    }

    /**
     * Returns {@code true} when bytes {@code [from, to)} of the given array are all zero.
     *
     * @param bytes the byte array to inspect
     * @param from  start index (inclusive)
     * @param to    end index (exclusive)
     * @return {@code true} if all bytes in the range are zero
     */
    private static boolean isZeroRange(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if the direct peer IP falls within one of the configured trusted
     * proxy CIDR ranges.
     *
     * @param remoteIp the normalized remote IP address
     * @return {@code true} when the peer is trusted
     */
    private boolean isPeerTrusted(String remoteIp) {
        for (CidrMatcher matcher : trustedProxies) {
            if (matcher.matches(remoteIp)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses the raw {@code X-Forwarded-For} header value into a list of trimmed entries.
     * Returns an empty list when the header is absent.
     *
     * @param request the inbound request
     * @return raw XFF entries, un-validated; may be empty
     */
    private static List<String> parseXffHeader(HttpServerRequest request) {
        String header = request.getHeader("X-Forwarded-For");
        if (header == null || header.isBlank()) {
            return List.of();
        }
        String[] parts = header.split(",");
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Sanitizes and caps a raw XFF entry list.
     *
     * <p>If the raw entry count exceeds {@link RequestOriginConfig#forwardedForCap()}, the entire
     * chain is rejected (AC-RO-5): {@code rejected=true}, {@code entries=[]},
     * {@code rejectedCount=rawEntries.size()}.
     *
     * <p>Otherwise, each entry is parsed as an {@link InetAddress}. Invalid entries (parse
     * failure) are silently dropped and counted in {@code rejectedCount} (AC-RO-6). Valid entries
     * are normalized via {@link #normalizeIp(String)}.
     *
     * @param rawEntries the raw trimmed XFF entries
     * @return the parse result carrying sanitized entries, rejected count, and rejection flag
     */
    private ChainParseResult sanitizeAndCapChain(List<String> rawEntries) {
        if (rawEntries.isEmpty()) {
            return new ChainParseResult(List.of(), 0, false);
        }

        // AC-RO-5: cap exceeded → drop entire chain.
        if (rawEntries.size() > config.forwardedForCap()) {
            return new ChainParseResult(List.of(), rawEntries.size(), true);
        }

        // AC-RO-6: validate each entry individually.
        // Guard: only accept IP literals — non-literals are rejected immediately without any
        // DNS resolution attempt, keeping getByName() calls safe for the event loop.
        List<String> valid = new ArrayList<>(rawEntries.size());
        int rejectedCount = 0;
        for (String entry : rawEntries) {
            if (!isIpLiteral(entry)) {
                rejectedCount++;
                continue;
            }
            try {
                InetAddress.getByName(entry);
                valid.add(normalizeIp(entry));
            } catch (Exception e) {
                rejectedCount++;
            }
        }
        return new ChainParseResult(List.copyOf(valid), rejectedCount, false);
    }

    /**
     * Derives the effective {@code clientIp} by walking the sanitized XFF chain right-to-left
     * and peeling off trusted entries.
     *
     * <p>The walk starts from the rightmost chain entry (nearest proxy), peeling entries that
     * fall within a trusted CIDR range. The first untrusted entry encountered is the derived
     * client IP. If all entries are trusted, the {@code remoteIp} is returned as the fallback.
     *
     * @param entries  sanitized, normalized chain entries (insertion order, leftmost = client)
     * @param remoteIp the direct peer IP (already normalized)
     * @return the derived client IP; never {@code null}
     */
    private String derivClientIpFromChain(List<String> entries, String remoteIp) {
        // Walk right-to-left: skip trusted entries, stop at the first untrusted one.
        for (int i = entries.size() - 1; i >= 0; i--) {
            String entry = entries.get(i);
            if (!isPeerTrusted(entry)) {
                return entry;
            }
        }
        // All chain entries are trusted — use the remote IP as the last-resort fallback.
        return remoteIp;
    }

    /**
     * Extracts {@link TlsFacts} from the request's {@link SSLSession} when the connection uses
     * TLS. Returns {@link Optional#empty()} for plain HTTP connections (AC-RO-8).
     *
     * @param request the inbound request
     * @return {@link TlsFacts} wrapped in {@link Optional}, or empty for non-TLS
     */
    private static Optional<TlsFacts> captureTls(HttpServerRequest request) {
        SSLSession session = request.connection().sslSession();
        if (session == null) {
            return Optional.empty();
        }

        String protocol = session.getProtocol();
        String cipherSuite = session.getCipherSuite();

        boolean peerCertPresented;
        try {
            List<Certificate> certs = request.connection().peerCertificates();
            peerCertPresented = certs != null && !certs.isEmpty();
        } catch (SSLPeerUnverifiedException | UnsupportedOperationException e) {
            peerCertPresented = false;
        }

        return Optional.of(new TlsFacts(
                protocol != null ? protocol : "", cipherSuite != null ? cipherSuite : "", peerCertPresented));
    }

    // --- Inner types ---

    /**
     * Internal result of parsing and sanitizing the {@code X-Forwarded-For} chain.
     *
     * @param entries       sanitized, normalized valid IP entries
     * @param rejectedCount number of entries that were invalid or cap-overflow count when rejected
     * @param rejected      {@code true} when the entire chain was discarded due to cap overflow
     */
    private record ChainParseResult(List<String> entries, int rejectedCount, boolean rejected) {}

    /**
     * CIDR range matcher for trusted-proxy detection.
     *
     * <p>Supports both IPv4 ({@code "10.0.0.0/8"}) and IPv6 ({@code "fd00::/8"}) CIDR notation.
     * Constructs a bitmask from the prefix length and compares network addresses bitwise.
     * IPv6-mapped IPv4 addresses in the test IP are normalized before matching.
     */
    static final class CidrMatcher {

        private final byte[] networkBytes;
        private final int prefixLength;

        private CidrMatcher(byte[] networkBytes, int prefixLength) {
            this.networkBytes = networkBytes;
            this.prefixLength = prefixLength;
        }

        /**
         * Parses a CIDR string (e.g., {@code "10.0.0.0/8"}) into a {@link CidrMatcher}.
         *
         * @param cidr the CIDR notation string; must contain a {@code /} separator
         * @return a configured matcher
         * @throws IllegalArgumentException if the CIDR string is malformed
         */
        static CidrMatcher parse(String cidr) {
            int slash = cidr.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException("Invalid CIDR (no '/'): " + cidr);
            }
            String networkPart = cidr.substring(0, slash);
            int prefix;
            try {
                prefix = Integer.parseInt(cidr.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid CIDR prefix length: " + cidr, e);
            }
            try {
                InetAddress networkAddr = InetAddress.getByName(networkPart);
                return new CidrMatcher(networkAddr.getAddress(), prefix);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid CIDR network address: " + cidr, e);
            }
        }

        /**
         * Returns {@code true} if the given IP address falls within this CIDR range.
         *
         * @param ip the candidate IP address string; normalized before comparison
         * @return {@code true} if {@code ip} is in the CIDR range
         */
        boolean matches(String ip) {
            if (!isIpLiteral(ip)) {
                // Non-literal (null, hostname) can never match a CIDR range; reject immediately.
                return false;
            }
            try {
                String normalized = normalizeIp(ip);
                InetAddress candidate = InetAddress.getByName(normalized);
                byte[] candidateBytes = candidate.getAddress();

                // Address family must match.
                if (candidateBytes.length != networkBytes.length) {
                    return false;
                }

                // Compare prefix bits.
                int remainingBits = prefixLength;
                for (int i = 0; i < candidateBytes.length && remainingBits > 0; i++) {
                    int bits = Math.min(8, remainingBits);
                    int mask = 0xFF & (0xFF << (8 - bits));
                    if ((candidateBytes[i] & mask) != (networkBytes[i] & mask)) {
                        return false;
                    }
                    remainingBits -= bits;
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
