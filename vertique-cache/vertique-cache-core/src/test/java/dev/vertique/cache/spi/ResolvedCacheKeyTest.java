// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Canonical-component validation: only forms the runtime encoder can emit are accepted. */
class ResolvedCacheKeyTest {

    private static final CacheRegion REGION = new CacheRegion("cache", "users", 1);

    @ParameterizedTest
    @ValueSource(
            strings = {"k2Salice", "k2Sa%2Fb", "k2Sa%252Fb", "%25", "i2:N", "actor:USER:alice", "A-Za-z0-9._~:/=-"})
    void acceptsCanonicalComponents(String component) {
        assertDoesNotThrow(() -> new ResolvedCacheKey(REGION, component, component));
    }

    @Test
    void validatesVeryLongComponentsWithoutStackGrowth() {
        assertDoesNotThrow(() -> new ResolvedCacheKey(REGION, "NONE", "x".repeat(1_000_000)));
        assertDoesNotThrow(() -> new ResolvedCacheKey(REGION, "NONE", "%2F".repeat(300_000)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResolvedCacheKey(REGION, "NONE", "x".repeat(1_000_000) + "%"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "a%b", "a%2", "a%2G", "a%2f", "a%%25", "a b", ""})
    void rejectsComponentsTheEncoderCannotEmit(String component) {
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCacheKey(REGION, component, "k2Salice"));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedCacheKey(REGION, "NONE", component));
    }
}
