// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.config.CacheEntryConfig;
import dev.vertique.cache.spi.CacheKey;
import dev.vertique.cache.spi.CacheRegion;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the provider-neutral cache API defaults and value boundaries. */
class CacheContractsTest {

    @Test
    @DisplayName("cache annotations expose the frozen defaults")
    void cacheAnnotationsExposeFrozenDefaults() throws NoSuchMethodException {
        var cacheable = Sample.class.getDeclaredMethod("cached").getAnnotation(Cacheable.class);

        assertEquals(CacheMode.DEFAULT, cacheable.mode());
        assertEquals(-1, cacheable.ttlSeconds());
        assertEquals(CacheIdentity.NONE, cacheable.identity());
        assertEquals(AnonymousCachePolicy.BYPASS, cacheable.anonymous());
    }

    @Test
    @DisplayName("cache keys use the canonical region and selector shape")
    void cacheKeysUseCanonicalShape() {
        var key = new CacheKey(new CacheRegion("cache", "profile", 1), "actor:USER:alice", "user-42");

        assertEquals("cache:v1:profile:actor:USER:alice:user-42", key.canonical());
    }

    @Test
    @DisplayName("cache regions reject invalid identity components")
    void cacheRegionsRejectInvalidIdentityComponents() {
        assertThrows(IllegalArgumentException.class, () -> new CacheRegion("cache", "", 1));
        assertThrows(IllegalArgumentException.class, () -> new CacheRegion("cache:name", "profile", 1));
    }

    @Test
    @DisplayName("cache defaults use the approved operational limits")
    void cacheDefaultsUseApprovedLimits() {
        var config = CacheConfig.defaults();

        assertEquals(CacheMode.LOCAL, config.defaultMode());
        assertEquals(60, config.defaultTtlSeconds());
        assertEquals(1_024, config.maxKeyBytes());
        assertEquals(100, config.backendTimeoutMs());
    }

    @Test
    @DisplayName("cache configuration rejects an entry TTL above the configured maximum")
    void cacheConfigurationRejectsEntryTtlAboveMaximum() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CacheConfig(
                        true,
                        CacheMode.LOCAL,
                        60,
                        120,
                        1_024,
                        1_048_576,
                        10_000,
                        100,
                        Map.of("profile", new CacheEntryConfig(CacheMode.LOCAL, 121))));
    }

    static final class Sample {
        @Cacheable(name = "profile", key = "{0}")
        Object cached() {
            return null;
        }
    }
}
