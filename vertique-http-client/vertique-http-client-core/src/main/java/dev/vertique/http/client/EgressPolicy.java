// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpEgressDeniedException;
import dev.vertique.http.client.exception.HttpEgressDeniedException.Reason;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which request targets a client may contact.
 *
 * <p>{@link #allowAll()} admits every target that has a host and no user information. {@link
 * #allowHosts(List, boolean)} admits only listed hosts. Matching works on the text of the target and
 * never resolves a name:
 *
 * <ul>
 *   <li>A target without a host, or with user information, is refused.
 *   <li>Hosts compare in lower case with one trailing dot removed.
 *   <li>An entry {@code *.suffix} matches {@code a.suffix} and {@code b.a.suffix}, but neither
 *       {@code suffix} nor {@code xsuffix}. The entry {@code *} matches any host name.
 *   <li>A wildcard never matches an IP literal or {@code localhost}; those need an exact entry. A host
 *       whose last label is all decimal digits or a {@code 0x} number counts as an IP literal.
 *   <li>{@code https} always passes the scheme rule; {@code http} passes only for {@code localhost}, a
 *       loopback IP literal, or when plain HTTP is permitted.
 * </ul>
 *
 * <p>Under a host list the client also refuses a request that names a server address that is not an
 * inet address whose host passes these rules, a virtual host (including a caller-set {@code Host}
 * header) and a request-level proxy whose host does not pass. Under {@link #allowAll()} those three are
 * allowed, but a domain-socket server address is refused by every policy.
 *
 * <p>Instances are immutable and safe to share between clients.
 */
public final class EgressPolicy {

    private static final EgressPolicy ALLOW_ALL = new EgressPolicy(false, false, Set.of(), List.of());

    private static final Pattern DECIMAL_IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-f:.]+");
    private static final Pattern HEX_NUMBER = Pattern.compile("0x[0-9a-f]*");
    private static final String LOCALHOST = "localhost";

    private final boolean restricted;
    private final boolean allowPlainHttp;
    private final Set<String> exact;
    private final List<String> suffixes;
    private final boolean anyName;

    private EgressPolicy(boolean restricted, boolean allowPlainHttp, Set<String> exact, List<String> suffixes) {
        this.restricted = restricted;
        this.allowPlainHttp = allowPlainHttp;
        this.exact = exact;
        this.suffixes = suffixes;
        this.anyName = exact.contains("*");
    }

    /**
     * Returns the policy that admits every target with a host and without user information, over
     * {@code http} or {@code https}. A domain-socket server address is still refused.
     *
     * @return the allow-all policy
     */
    public static EgressPolicy allowAll() {
        return ALLOW_ALL;
    }

    /**
     * Returns a policy that admits only the listed hosts.
     *
     * <p>Each entry is an exact host name or IP literal, a {@code *.suffix} wildcard, or {@code *} for
     * any host name. Entries compare in lower case with one trailing dot removed; an IPv6 literal may
     * be written with or without brackets.
     *
     * @param hosts the admitted hosts; an empty list admits none
     * @param allowPlainHttp whether {@code http} may be used for hosts other than loopback
     * @return the policy
     * @throws NullPointerException if {@code hosts} is null
     * @throws IllegalArgumentException if an entry is null, blank or not a valid host entry
     */
    public static EgressPolicy allowHosts(List<String> hosts, boolean allowPlainHttp) {
        Objects.requireNonNull(hosts, "hosts");
        Set<String> exact = new java.util.LinkedHashSet<>();
        List<String> suffixes = new ArrayList<>();
        for (String entry : hosts) {
            if (entry == null || entry.isBlank() || entry.chars().anyMatch(c -> Character.isWhitespace(c))) {
                throw invalidEntry();
            }
            String normalized = normalize(entry);
            if (normalized.equals("*")) {
                exact.add("*");
            } else if (normalized.startsWith("*.")) {
                String suffix = normalized.substring(1);
                if (suffix.length() < 2 || suffix.indexOf('*') >= 0) {
                    throw invalidEntry();
                }
                suffixes.add(suffix);
            } else if (normalized.isEmpty() || normalized.indexOf('*') >= 0 || hasUriDelimiter(normalized)) {
                throw invalidEntry();
            } else {
                exact.add(normalized);
            }
        }
        return new EgressPolicy(true, allowPlainHttp, Set.copyOf(exact), List.copyOf(suffixes));
    }

    /**
     * Checks a target against this policy without connecting and without resolving any name.
     *
     * @param target the target to check
     * @throws HttpEgressDeniedException with the reason the target is refused: {@code NO_HOST},
     *     {@code USER_INFO}, {@code HOST} or {@code SCHEME}
     * @throws NullPointerException if {@code target} is null
     */
    public void check(URI target) {
        Objects.requireNonNull(target, "target");
        String authority = target.getRawAuthority();
        if (authority == null || authority.isEmpty()) {
            throw denied(Reason.NO_HOST);
        }
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            throw denied(Reason.USER_INFO);
        }
        String host = normalize(hostOf(authority));
        if (host.isEmpty()) {
            throw denied(Reason.NO_HOST);
        }
        if (restricted && !hostAllowed(host)) {
            throw denied(Reason.HOST);
        }
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        boolean admitted =
                switch (scheme) {
                    case "https" -> true;
                    case "http" -> !restricted || allowPlainHttp || isLoopback(host);
                    default -> false;
                };
        if (!admitted) {
            throw denied(Reason.SCHEME);
        }
    }

    /** Whether this policy lists hosts, so that virtual hosts, server addresses and proxies are checked. */
    boolean restrictsHosts() {
        return restricted;
    }

    @Override
    public String toString() {
        return restricted ? "EgressPolicy[allowHosts]" : "EgressPolicy[allowAll]";
    }

    private boolean hostAllowed(String host) {
        if (exact.contains(host)) {
            return true;
        }
        if (isIpLiteral(host) || host.equals(LOCALHOST)) {
            return false;
        }
        if (anyName) {
            return true;
        }
        for (String suffix : suffixes) {
            if (host.length() > suffix.length() && host.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static String hostOf(String hostAndPort) {
        if (hostAndPort.startsWith("[")) {
            int close = hostAndPort.indexOf(']');
            return close < 0 ? "" : hostAndPort.substring(0, close + 1);
        }
        int colon = hostAndPort.lastIndexOf(':');
        return colon >= 0 ? hostAndPort.substring(0, colon) : hostAndPort;
    }

    /** Lower-cases, removes one trailing dot and the brackets of an IPv6 literal. */
    private static String normalize(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.startsWith("[") && lower.endsWith("]")) {
            return lower.substring(1, lower.length() - 1);
        }
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    private static boolean hasUriDelimiter(String value) {
        return value.indexOf('/') >= 0 || value.indexOf('@') >= 0 || value.indexOf('?') >= 0 || value.indexOf('#') >= 0;
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        String last = host.substring(host.lastIndexOf('.') + 1);
        return !last.isEmpty() && last.chars().allMatch(c -> c >= '0' && c <= '9')
                || HEX_NUMBER.matcher(last).matches();
    }

    private static boolean isLoopback(String host) {
        if (host.equals(LOCALHOST)) {
            return true;
        }
        try {
            if (DECIMAL_IPV4.matcher(host).matches()) {
                String[] parts = host.split("\\.");
                byte[] address = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int value = Integer.parseInt(parts[i]);
                    if (value > 255) {
                        return false;
                    }
                    address[i] = (byte) value;
                }
                return InetAddress.getByAddress(address).isLoopbackAddress();
            }
            if (host.indexOf(':') >= 0 && IPV6_CHARS.matcher(host).matches()) {
                // contains a colon and only address characters, so Java parses it as a literal and never resolves
                return InetAddress.getByName(host).isLoopbackAddress();
            }
        } catch (UnknownHostException | NumberFormatException notAnAddress) {
            return false;
        }
        return false;
    }

    private static HttpEgressDeniedException denied(Reason reason) {
        return new HttpEgressDeniedException(reason);
    }

    private static IllegalArgumentException invalidEntry() {
        return new IllegalArgumentException("An egress host entry must be a host name, an IP literal, *.suffix or *");
    }
}
