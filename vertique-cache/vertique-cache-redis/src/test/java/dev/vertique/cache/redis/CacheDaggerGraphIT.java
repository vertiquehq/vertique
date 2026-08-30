// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.cache.CacheMode;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.job.cron.CronScheduler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dagger composition proof for the explicitly installed clustered Redis provider. */
class CacheDaggerGraphIT {

    @Test
    @DisplayName("the clustered cache graph resolves one Redis provider without connecting at startup")
    void generatedProxyComposesWithClusteredProvider() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            ClusterComponent component = DaggerCacheDaggerGraphIT_ClusterComponent.builder()
                    .vertxModule(new VertxModule(vertx, configuration()))
                    .cronScheduler(mock(CronScheduler.class))
                    .build();

            // When: the application graph resolves its provider map and requests the clustered store.
            CacheStore store = component.providers().get(CacheMode.CLUSTERED).get();

            // Then: Redis is the bounded provider selection; construction is lazy and does not require a live server.
            assertEquals(Set.of(CacheMode.CLUSTERED), component.providers().keySet());
            assertInstanceOf(RedisCacheStore.class, store);
            assertTrue(component.shutdownSteps().stream().anyMatch(RedisCleanupLifecycle.class::isInstance));
            for (ApplicationShutdownStep step : component.shutdownSteps()) {
                step.stop().toCompletionStage().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static JsonObject configuration() {
        return new JsonObject()
                .put(
                        "cache",
                        new JsonObject()
                                .put("enabled", true)
                                .put("defaultMode", "CLUSTERED")
                                .put("defaultTtlSeconds", 60)
                                .put("maxTtlSeconds", 86_400)
                                .put("jsonProfile", "vertx")
                                .put("maxKeyBytes", 1_024)
                                .put("maxValueBytes", 1_048_576)
                                .put("maximumEntries", 10_000)
                                .put("backendTimeoutMs", 100)
                                .put("caches", new JsonObject())
                                .put(
                                        "redis",
                                        new JsonObject()
                                                .put("connection", "primary")
                                                .put("namespace", "cache")
                                                .put("formatVersion", 1)))
                .put(
                        "redis",
                        new JsonObject()
                                .put(
                                        "connections",
                                        new JsonObject()
                                                .put(
                                                        "primary",
                                                        new JsonObject()
                                                                .put("endpoints", List.of("redis://127.0.0.1:6379"))
                                                                .put("connectTimeoutMs", 100)
                                                                .put("maxPoolSize", 1)
                                                                .put("maxPoolWaiting", 1))));
    }

    @Singleton
    @Component(modules = {CacheRedisModule.class, ConfigParsingModule.class, VertxModule.class})
    interface ClusterComponent {
        @Component.Builder
        interface Builder {
            @BindsInstance
            Builder cronScheduler(CronScheduler scheduler);

            Builder vertxModule(VertxModule module);

            ClusterComponent build();
        }

        Map<CacheMode, Provider<CacheStore>> providers();

        Set<ApplicationShutdownStep> shutdownSteps();
    }
}
