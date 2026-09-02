// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * P02/P03 review repair (T017, item 7): pure-unit golden vectors for {@link
 * RateLimitEdgeMiddleware#ipKeyComponent}'s IPv6 prefix-aggregation derivation
 * (contracts/rest-adapter.md, "Cardinality caution"), independent of a full HTTP round trip. Every
 * expected literal below was independently cross-checked against {@link
 * java.net.InetAddress#getByAddress}'s actual {@code getHostAddress()} rendering, which does not
 * compress a trailing zero-group run into {@code ::} the way a human-authored IPv6 literal would.
 */
class RateLimitEdgeMiddlewareIpKeyComponentTest {

    private static final String ADDRESS = "2001:db8:1234:5678:9abc:def0:1234:5678";

    @Test
    void shouldAggregateOntoTheDefaultSlash64Prefix() {
        assertThat(RateLimitEdgeMiddleware.ipKeyComponent(ADDRESS, 64)).isEqualTo("2001:db8:1234:5678:0:0:0:0/64");
    }

    @Test
    void shouldAggregateOntoASlash56Prefix() {
        assertThat(RateLimitEdgeMiddleware.ipKeyComponent(ADDRESS, 56)).isEqualTo("2001:db8:1234:5600:0:0:0:0/56");
    }

    @Test
    void shouldKeepTheFullAddressAtSlash128() {
        assertThat(RateLimitEdgeMiddleware.ipKeyComponent(ADDRESS, 128)).isEqualTo(ADDRESS + "/128");
    }

    @Test
    void shouldAggregateOntoASlash52PrefixWithANonMultipleOfEightBoundary() {
        assertThat(RateLimitEdgeMiddleware.ipKeyComponent(ADDRESS, 52)).isEqualTo("2001:db8:1234:5000:0:0:0:0/52");
    }

    /**
     * An IPv4-mapped IPv6 literal resolves through {@link java.net.InetAddress#getByName} to a
     * 4-byte {@code Inet4Address}, not a 16-byte {@code Inet6Address} — {@code ipKeyComponent}'s own
     * {@code bytes.length != 16} guard then normalizes it to the resolved address's dotted-quad
     * {@code getHostAddress()} form (T021 S1), exactly like a bare IPv4 address, so {@code
     * ::ffff:192.0.2.1} and {@code 192.0.2.1} always key identically — defense-in-depth only:
     * {@code RequestOriginCapturer.normalizeIp} already performs this normalization upstream for
     * origin-capture flows (contracts/rest-adapter.md, "Cardinality caution").
     */
    @Test
    void shouldNormalizeAnIpv4MappedIpv6LiteralToItsDottedQuadForm() {
        assertThat(RateLimitEdgeMiddleware.ipKeyComponent("::ffff:192.0.2.1", 64))
                .isEqualTo("192.0.2.1");
    }

    @Test
    void shouldPassThroughAPlainIpv4AddressRegardlessOfPrefixBits() {
        assertThat(RateLimitEdgeMiddleware.ipKeyComponent("192.0.2.1", 64)).isEqualTo("192.0.2.1");
    }
}
