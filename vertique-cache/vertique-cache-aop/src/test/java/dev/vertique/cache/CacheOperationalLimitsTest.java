// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.config.CacheEntryConfig;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies provider-neutral cache operational-limit rejection at the annotation boundary. */
class CacheOperationalLimitsTest {

    @Test
    @DisplayName("an annotation TTL above maxTtlSeconds is rejected")
    void rejectsTtlAboveMaximum() throws NoSuchMethodException {
        var config = new CacheConfig(true, CacheMode.LOCAL, 60, 120, "vertx", 1_024, 1_048_576, 10_000, 100, Map.of());
        var method = Target.class.getDeclaredMethod("value");
        var metadata = CacheTestFixtures.metadata(method, "unused");

        assertThrows(IllegalArgumentException.class, () -> new CacheableAspect(
                        new CacheTestFixtures.RecordingStore(), config, Set.of())
                .interceptor(metadata, method.getAnnotation(Cacheable.class)));
    }

    @Test
    @DisplayName("an annotation TTL equal to maxTtlSeconds is accepted")
    void acceptsTtlAtMaximum() throws NoSuchMethodException {
        var store = new CacheTestFixtures.RecordingStore();
        var config = new CacheConfig(true, CacheMode.LOCAL, 60, 120, "vertx", 1_024, 1_048_576, 10_000, 100, Map.of());
        var method = Target.class.getDeclaredMethod("valueAtMaximum");
        var metadata = CacheTestFixtures.metadata(method, "unused");
        var targetCalls = new AtomicInteger();

        assertDoesNotThrow(() -> new CacheableAspect(store, config, Set.of())
                .interceptor(metadata, method.getAnnotation(Cacheable.class))
                .intercept(CacheTestFixtures.invocation(metadata, new Object[0], targetCalls))
                .toCompletionStage()
                .toCompletableFuture()
                .join());

        assertEquals(Duration.ofSeconds(120), store.lastTtl);
    }

    @Test
    @DisplayName("a per-cache TTL override takes precedence over an oversized annotation TTL")
    void perCacheTtlOverrideTakesPrecedenceOverOversizedAnnotationTtl() throws NoSuchMethodException {
        var store = new CacheTestFixtures.RecordingStore();
        var config = new CacheConfig(
                true,
                CacheMode.LOCAL,
                60,
                120,
                "vertx",
                1_024,
                1_048_576,
                10_000,
                100,
                Map.of("profile", new CacheEntryConfig(CacheMode.LOCAL, 7, null)));
        var method = Target.class.getDeclaredMethod("value");
        var metadata = CacheTestFixtures.metadata(method, "unused");
        var targetCalls = new AtomicInteger();

        assertDoesNotThrow(() -> new CacheableAspect(store, config, Set.of())
                .interceptor(metadata, method.getAnnotation(Cacheable.class))
                .intercept(CacheTestFixtures.invocation(metadata, new Object[0], targetCalls))
                .toCompletionStage()
                .toCompletableFuture()
                .join());

        assertEquals(Duration.ofSeconds(7), store.lastTtl);
    }

    static final class Target {
        @Cacheable(
                name = "profile",
                key = {},
                ttlSeconds = 121,
                identity = CacheIdentity.NONE)
        String value() {
            return "unused";
        }

        @Cacheable(
                name = "profile-at-maximum",
                key = {},
                ttlSeconds = 120,
                identity = CacheIdentity.NONE)
        String valueAtMaximum() {
            return "unused";
        }
    }
}
