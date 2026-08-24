// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.config.CacheEntryConfig;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CacheResolutionTest {

    @Test
    void configurationOverridesAnnotationModeAndTtl() throws NoSuchMethodException {
        var store = new CacheTestFixtures.RecordingStore();
        CacheConfig config = new CacheConfig(
                true,
                CacheMode.LOCAL,
                60,
                120,
                "vertx",
                1_024,
                1_048_576,
                10_000,
                100,
                Map.of("profile", new CacheEntryConfig(CacheMode.LOCAL, 7)));
        var method = Target.class.getDeclaredMethod("value", String.class);
        var metadata = CacheTestFixtures.metadata(method, "user");
        var annotation = method.getAnnotation(Cacheable.class);
        var aspect = new CacheableAspect(store, config, Set.of());
        AtomicInteger targetCalls = new AtomicInteger();

        Object result = aspect.interceptor(metadata, annotation)
                .intercept(CacheTestFixtures.invocation(metadata, new Object[] {"alice"}, targetCalls))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals("result-1", result);
        assertEquals(1, store.getCalls, "the configured local mode must override clustered annotation mode");
        assertEquals(Duration.ofSeconds(7), store.lastTtl, "the per-cache TTL must override the annotation TTL");
    }

    @Test
    void zeroTtlIsResolvedAsNeverExpire() throws NoSuchMethodException {
        var store = new CacheTestFixtures.RecordingStore();
        CacheConfig config = new CacheConfig(
                true,
                CacheMode.LOCAL,
                60,
                120,
                "vertx",
                1_024,
                1_048_576,
                10_000,
                100,
                Map.of("profile", new CacheEntryConfig(CacheMode.LOCAL, 0)));
        var method = Target.class.getDeclaredMethod("value", String.class);
        var metadata = CacheTestFixtures.metadata(method, "user");
        AtomicInteger targetCalls = new AtomicInteger();

        new CacheableAspect(store, config, Set.of())
                .interceptor(metadata, method.getAnnotation(Cacheable.class))
                .intercept(CacheTestFixtures.invocation(metadata, new Object[] {"alice"}, targetCalls))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals(Duration.ZERO, store.lastTtl);
    }

    static final class Target {
        @Cacheable(name = "profile", key = "{0}", mode = CacheMode.CLUSTERED, ttlSeconds = 1)
        String value(String user) {
            return "unused";
        }
    }
}
