// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Decides the {@code url} format {@link PatternInputGuard} routes here: {@link URI} parsing plus a
 * post-parse check on the parsed scheme, host, and port that preserves the vertx-json-schema 5.1.8
 * {@code url} expression's scheme and host filtering, with no new parser and no regular expression.
 *
 * <p>A value is a valid {@code url} when {@link URI} parses it as absolute; its scheme is {@code http},
 * {@code https}, or {@code ftp}, compared with {@link String#equalsIgnoreCase}; it has a non-empty host
 * that is not an IPv6 literal (a host starting with {@code [}); when the raw authority carries a
 * {@code :} after the host, the remainder is 2 to 5 ASCII digits (an empty port fails); and the host,
 * with one trailing dot removed, is either a dotted quad outside the {@code 0/8}, {@code 10/8}, {@code
 * 127/8}, {@code 169.254/16}, {@code 192.168/16}, {@code 172.16/12}, and {@code 224/4}-and-above ranges,
 * or ends in an alphabetic label of two or more characters.
 *
 * <p>A dotted-quad label longer than one character that starts with {@code 0} is not treated as an
 * octet, so a host with such a label, for example {@code 8.08.8.8}, is rejected as not a dotted quad
 * rather than checked against the ranges above. This is stricter than the vertx-json-schema 5.1.8
 * {@code url} expression's own filtering, which this check otherwise preserves.
 *
 * <p>{@code format: url} is not an SSRF control: it resolves no names (for example {@code
 * 127.0.0.1.nip.io}) and follows no redirects; validate the resolved address at the outbound call.
 */
final class UrlFormatCheck {

    /** The schemes the post-parse check accepts, compared case-insensitively. */
    private static final String[] ALLOWED_SCHEMES = {"http", "https", "ftp"};

    /** The lowest first octet of a dotted quad rejected as reserved or multicast, {@code 224/4} and above. */
    private static final int MULTICAST_AND_ABOVE_FIRST_OCTET = 224;

    private UrlFormatCheck() {}

    /**
     * Decides the {@code url} format.
     *
     * @param value the instance
     * @return {@code true} when {@code value} is a valid {@code url}
     */
    static boolean isUrl(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException notAUrl) {
            return false;
        }
        if (!uri.isAbsolute()) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !isAllowedScheme(scheme)) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty() || host.charAt(0) == '[') {
            return false;
        }
        if (!hasAcceptablePort(uri, host)) {
            return false;
        }
        String trimmedHost = stripOneTrailingDot(host);
        int[] octets = dottedQuadOctets(trimmedHost);
        return octets != null ? !isRejectedDottedQuad(octets) : endsWithAlphabeticLabel(trimmedHost);
    }

    /**
     * Reports whether {@code scheme} is {@code http}, {@code https}, or {@code ftp}, compared with
     * {@link String#equalsIgnoreCase}.
     *
     * @param scheme the parsed scheme, never {@code null}
     * @return {@code true} when {@code scheme} is one of the accepted schemes
     */
    private static boolean isAllowedScheme(String scheme) {
        for (String allowed : ALLOWED_SCHEMES) {
            if (scheme.equalsIgnoreCase(allowed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports whether the port, present when the raw authority carries a {@code :} right after {@code
     * host}, is 2 to 5 ASCII digits; a port carried but empty or out of range fails, and no port present
     * passes.
     *
     * @param uri  the parsed, absolute URI
     * @param host {@code uri}'s host, non-empty
     * @return {@code true} when the port, if any, is acceptable
     */
    private static boolean hasAcceptablePort(URI uri, String host) {
        String rawAuthority = uri.getRawAuthority();
        String rawUserInfo = uri.getRawUserInfo();
        String afterUserInfo = rawUserInfo == null ? rawAuthority : rawAuthority.substring(rawUserInfo.length() + 1);
        if (afterUserInfo.length() <= host.length()) {
            return true;
        }
        String remainder = afterUserInfo.substring(host.length());
        if (remainder.charAt(0) != ':') {
            return false;
        }
        String portDigits = remainder.substring(1);
        int length = portDigits.length();
        if (length < 2 || length > 5) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (!isAsciiDigit(portDigits.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes one trailing {@code .} from {@code host}, when present.
     *
     * @param host the parsed host
     * @return {@code host} with at most one trailing dot removed
     */
    private static String stripOneTrailingDot(String host) {
        int length = host.length();
        return length > 0 && host.charAt(length - 1) == '.' ? host.substring(0, length - 1) : host;
    }

    /**
     * Reads {@code host} as a dotted quad in one left-to-right pass, without {@link String#split}: four
     * dot-separated, non-empty, all-ASCII-digit labels of at most nine characters each, so every value
     * fits in an {@code int} without overflow. A label longer than one character that starts with
     * {@code 0} is not an octet, so {@code host} is not a dotted quad; a single {@code 0} is still an
     * octet.
     *
     * @param host the host, its trailing dot already removed
     * @return the four octets in order, or {@code null} when {@code host} is not a dotted quad
     */
    private static int[] dottedQuadOctets(String host) {
        int[] octets = new int[4];
        int octetIndex = 0;
        int segmentStart = 0;
        int length = host.length();
        for (int i = 0; i <= length; i++) {
            if (i != length && host.charAt(i) != '.') {
                continue;
            }
            if (octetIndex >= 4) {
                return null;
            }
            int octet = parseAsciiDigits(host, segmentStart, i);
            if (octet < 0) {
                return null;
            }
            octets[octetIndex] = octet;
            octetIndex++;
            segmentStart = i + 1;
        }
        return octetIndex == 4 ? octets : null;
    }

    /**
     * Parses {@code text[start, end)} as a non-empty run of ASCII digits, at most nine characters. A
     * run longer than one character that starts with {@code 0} is not an octet and is rejected; a
     * single {@code 0} is still an octet.
     *
     * @param text  the host
     * @param start the segment's start index, inclusive
     * @param end   the segment's end index, exclusive
     * @return the parsed value, or {@code -1} when the segment is empty, too long, not all digits, or a
     *     multi-character run starting with {@code 0}
     */
    private static int parseAsciiDigits(String text, int start, int end) {
        int segmentLength = end - start;
        if (segmentLength <= 0 || segmentLength > 9) {
            return -1;
        }
        if (segmentLength > 1 && text.charAt(start) == '0') {
            return -1;
        }
        int value = 0;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (!isAsciiDigit(c)) {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }

    /**
     * Reports whether a dotted-quad host's octets fall in a rejected private or reserved band: {@code
     * 0/8}, {@code 10/8}, {@code 127/8}, {@code 169.254/16}, {@code 192.168/16}, {@code 172.16/12}, or
     * {@code 224/4} and above.
     *
     * @param octets the host's four octets, in order
     * @return {@code true} when the host is rejected
     */
    private static boolean isRejectedDottedQuad(int[] octets) {
        int first = octets[0];
        int second = octets[1];
        return first == 0
                || first == 10
                || first == 127
                || (first == 169 && second == 254)
                || (first == 192 && second == 168)
                || (first == 172 && second >= 16 && second <= 31)
                || first >= MULTICAST_AND_ABOVE_FIRST_OCTET;
    }

    /**
     * Reports whether {@code host} contains a dot and its last, dot-separated label has at least two
     * characters, all ASCII letters.
     *
     * @param host the host, its trailing dot already removed
     * @return {@code true} when {@code host} ends in a qualifying alphabetic label
     */
    private static boolean endsWithAlphabeticLabel(String host) {
        int lastDot = host.lastIndexOf('.');
        if (lastDot < 0) {
            return false;
        }
        int labelStart = lastDot + 1;
        int length = host.length();
        if (length - labelStart < 2) {
            return false;
        }
        for (int i = labelStart; i < length; i++) {
            if (!isAsciiLetter(host.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether {@code c} is an ASCII digit, {@code 0} through {@code 9}.
     *
     * @param c the character
     * @return {@code true} when {@code c} is an ASCII digit
     */
    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Reports whether {@code c} is an ASCII letter, {@code a} through {@code z} or {@code A} through
     * {@code Z}.
     *
     * @param c the character
     * @return {@code true} when {@code c} is an ASCII letter
     */
    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
