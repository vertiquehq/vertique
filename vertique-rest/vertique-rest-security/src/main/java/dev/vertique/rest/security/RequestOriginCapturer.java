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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
        byte[] bytes = addressBytes(raw);
        if (bytes == null) {
            // Not an IP literal — return unchanged.
            return raw;
        }
        if (bytes.length == 4) {
            return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "." + (bytes[2] & 0xFF) + "." + (bytes[3] & 0xFF);
        }
        // Pure IPv6 — return the raw string unchanged so addresses like "2001:db8::1" are
        // not expanded to their full form.
        return raw;
    }

    /**
     * Returns {@code true} if {@code s} is an IP address literal (IPv4, IPv6, or IPv4-mapped IPv6).
     *
     * <p>The check is a syntactic parse that never touches the JDK resolver, so it is safe on the
     * event loop for attacker-supplied {@code X-Forwarded-For} data. See {@link #parseLiteral}.
     *
     * @param s the string to test; may be {@code null}
     * @return {@code true} when {@code s} is a well-formed IP literal
     */
    static boolean isIpLiteral(String s) {
        return parseLiteral(s) != null;
    }

    /**
     * Parses an IP literal into its address bytes, converting IPv4-mapped IPv6 to the 4-byte IPv4
     * form so IPv4 and mapped entries compare and match identically.
     *
     * @param s the candidate literal; may be {@code null}
     * @return 4 bytes for IPv4 (or IPv4-mapped IPv6), 16 bytes for other IPv6, {@code null} when
     *         {@code s} is not an IP literal
     */
    private static byte[] addressBytes(String s) {
        byte[] bytes = parseLiteral(s);
        if (bytes != null
                && bytes.length == 16
                && isZeroRange(bytes, 0, 10)
                && (bytes[10] & 0xFF) == 0xFF
                && (bytes[11] & 0xFF) == 0xFF) {
            return Arrays.copyOfRange(bytes, 12, 16);
        }
        return bytes;
    }

    /**
     * Parses an IPv4 or IPv6 literal without any name resolution. {@code X-Forwarded-For} is
     * attacker-supplied and parsed before authentication, so the header must never reach
     * {@link InetAddress#getByName(String)}: any string the JDK does not accept as a literal is
     * handed to the OS resolver, a blocking lookup on the event loop.
     *
     * <p>Accepted forms:
     * <ul>
     *   <li>IPv4: four dot-separated decimal octets of one to three digits, each at most
     *       {@code 255}. Leading zeros are rejected ({@code 010} is octal to some parsers and
     *       decimal to others).</li>
     *   <li>IPv6: up to eight 1-4 digit hex groups, at most one {@code ::}, an optional trailing
     *       dotted IPv4 (which fills two groups), and an optional {@code %zone} suffix of
     *       {@code [a-zA-Z0-9._~-]+}, which is syntax-checked and not resolved.</li>
     * </ul>
     *
     * @param s the candidate literal; may be {@code null}
     * @return 4 bytes (IPv4) or 16 bytes (IPv6), or {@code null} when {@code s} is not a literal
     */
    private static byte[] parseLiteral(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        if (s.indexOf(':') < 0) {
            return parseIpv4(s);
        }
        String address = s;
        int percent = s.indexOf('%');
        if (percent >= 0) {
            if (!isValidZone(s, percent + 1)) {
                return null;
            }
            address = s.substring(0, percent);
        }
        return parseIpv6(address);
    }

    private static boolean isValidZone(String s, int from) {
        if (from >= s.length()) {
            return false;
        }
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || c == '.'
                    || c == '_'
                    || c == '~'
                    || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static byte[] parseIpv4(String s) {
        byte[] out = new byte[4];
        int index = 0;
        int octetStart = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i < s.length() && s.charAt(i) != '.') {
                continue;
            }
            if (index == 4) {
                return null;
            }
            int len = i - octetStart;
            if (len < 1 || len > 3 || (len > 1 && s.charAt(octetStart) == '0')) {
                return null;
            }
            int value = 0;
            for (int j = octetStart; j < i; j++) {
                char c = s.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return null;
            }
            out[index++] = (byte) value;
            octetStart = i + 1;
        }
        return index == 4 ? out : null;
    }

    private static byte[] parseIpv6(String s) {
        int gap = s.indexOf("::");
        if (gap >= 0 && s.indexOf("::", gap + 1) >= 0) {
            return null;
        }
        String left = gap >= 0 ? s.substring(0, gap) : s;
        String right = gap >= 0 ? s.substring(gap + 2) : "";
        int[] groups = new int[8];
        int[] leftCount = {0};
        // The trailing IPv4 form may only end the whole address: the right side when "::" is
        // present, otherwise the only side.
        boolean leftEndsAddress = gap < 0;
        if (!parseGroups(left, groups, 0, leftCount, leftEndsAddress)) {
            return null;
        }
        if (gap < 0) {
            return leftCount[0] == 8 ? toBytes(groups) : null;
        }
        int[] rightGroups = new int[8];
        int[] rightCount = {0};
        if (!parseGroups(right, rightGroups, 0, rightCount, true)) {
            return null;
        }
        if (leftCount[0] + rightCount[0] > 7) {
            return null;
        }
        System.arraycopy(rightGroups, 0, groups, 8 - rightCount[0], rightCount[0]);
        return toBytes(groups);
    }

    /**
     * Parses a colon-separated run of hex groups into {@code out}, optionally ending in a dotted
     * IPv4 that fills two groups. An empty {@code part} yields zero groups.
     */
    private static boolean parseGroups(String part, int[] out, int offset, int[] count, boolean mayEndInIpv4) {
        if (part.isEmpty()) {
            return true;
        }
        String[] tokens = part.split(":", -1);
        int n = 0;
        for (int t = 0; t < tokens.length; t++) {
            String token = tokens[t];
            if (token.isEmpty()) {
                return false;
            }
            if (token.indexOf('.') >= 0) {
                if (!mayEndInIpv4 || t != tokens.length - 1 || n > 6) {
                    return false;
                }
                byte[] v4 = parseIpv4(token);
                if (v4 == null) {
                    return false;
                }
                out[offset + n++] = ((v4[0] & 0xFF) << 8) | (v4[1] & 0xFF);
                out[offset + n++] = ((v4[2] & 0xFF) << 8) | (v4[3] & 0xFF);
                continue;
            }
            if (token.length() > 4 || n > 7) {
                return false;
            }
            int value = 0;
            for (int j = 0; j < token.length(); j++) {
                int digit = Character.digit(token.charAt(j), 16);
                // Character.digit also accepts non-ASCII digits; restrict to ASCII.
                if (digit < 0 || token.charAt(j) > 'f') {
                    return false;
                }
                value = (value << 4) | digit;
            }
            out[offset + n++] = value;
        }
        count[0] = n;
        return true;
    }

    private static byte[] toBytes(int[] groups) {
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[2 * i] = (byte) (groups[i] >>> 8);
            out[2 * i + 1] = (byte) groups[i];
        }
        return out;
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
     * <p>Otherwise, each entry is parsed as an IP literal without name resolution. Invalid entries
     * (parse failure) are silently dropped and counted in {@code rejectedCount} (AC-RO-6). Valid entries
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
        // Only IP literals are accepted. The syntactic parse never resolves names, so attacker-supplied
        // entries cannot reach the OS resolver from the event loop.
        List<String> valid = new ArrayList<>(rawEntries.size());
        int rejectedCount = 0;
        for (String entry : rawEntries) {
            if (isIpLiteral(entry)) {
                valid.add(normalizeIp(entry));
            } else {
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
            byte[] candidateBytes = addressBytes(ip);
            if (candidateBytes == null) {
                // Non-literal (null, hostname) can never match a CIDR range.
                return false;
            }

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
        }
    }
}
