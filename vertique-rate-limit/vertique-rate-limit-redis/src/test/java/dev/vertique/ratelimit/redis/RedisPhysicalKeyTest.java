// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * P02/P03 review repair (T017, item 11): pins {@link RedisPhysicalKey#physicalKey}'s output equal
 * to {@code dev.vertique.ratelimit.RateLimitStorageIdentity#clustered}'s output for identical
 * inputs — the deliberate duplication this class's own javadoc documents (T002's equivalent is
 * package-private to {@code vertique-rate-limit-core} and unreachable from this module, so this
 * class independently re-derives the same algorithm from JDK primitives instead of sharing code).
 *
 * <p>The expected literals below are copied verbatim from {@code
 * dev.vertique.ratelimit.RateLimitStorageIdentityTest}'s own pinned golden vectors (namespace
 * {@code "rl"}, canonical input {@code "policy-a:r1:Scaller-1"} — policy {@code "policy-a"},
 * revision {@code "r1"}, one-component key {@code "caller-1"}) rather than re-derived here, so a
 * change to either class's algorithm that silently diverges from the other is caught by this test
 * without this test also silently drifting alongside it.
 */
class RedisPhysicalKeyTest {

    private static final String NAMESPACE = "rl";
    private static final String CANONICAL_INPUT = "policy-a:r1:Scaller-1";

    @Test
    void shouldMatchCoreRateLimitStorageIdentityClusteredGoldenVectors() {
        assertThat(RedisPhysicalKey.physicalKey(NAMESPACE, "rl-test-secret", CANONICAL_INPUT))
                .isEqualTo("rl:v1:7791113a:c091a0c4e6b73e6ae1432257d3988428772aaac411575d1e83f83b73a4eacbe6");
        assertThat(RedisPhysicalKey.physicalKey(NAMESPACE, "rl-test-secret-2", CANONICAL_INPUT))
                .isEqualTo("rl:v1:4f97e019:b4bcca6ec96d29e9ba2ad1bc264650217a3d53d20d108240b5d6bba05a365bea");
    }
}
