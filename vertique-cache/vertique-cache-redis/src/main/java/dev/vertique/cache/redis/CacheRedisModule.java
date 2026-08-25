// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;
import dev.vertique.cache.CacheCoreModule;
import dev.vertique.cache.CacheMode;
import dev.vertique.cache.CacheModeKey;
import dev.vertique.cache.CacheProviderIdKey;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.job.cron.CronScheduler;
import dev.vertique.json.JsonRuntimeModule;
import dev.vertique.redis.RedisClientRegistry;
import dev.vertique.redis.RedisConnectionModule;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** Dagger contribution for the clustered Redis cache store. */
@Module(includes = {CacheCoreModule.class, JsonRuntimeModule.class, RedisConnectionModule.class})
public abstract class CacheRedisModule {
    @BindsOptionalOf
    abstract CronScheduler optionalCronScheduler();

    @Provides
    @Singleton
    static CacheRedisConfig cacheRedisConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "cache", "redis"), CacheRedisConfig.class);
    }

    @Provides
    @IntoMap
    @CacheModeKey(CacheMode.CLUSTERED)
    @Singleton
    static CacheStore cacheStore(
            RedisClientRegistry clients,
            CacheRedisConfig config,
            CacheConfig cacheConfig,
            JsonMapperProfileRegistry profiles,
            Vertx vertx) {
        return new RedisCacheStore(clients, config, cacheConfig, profiles, vertx);
    }

    @Provides
    @IntoMap
    @CacheProviderIdKey(CacheMode.CLUSTERED)
    static String providerId() {
        return "redis";
    }

    @Provides
    @Singleton
    static RedisCleanupJob redisCleanupJob(
            RedisClientRegistry clients, CacheRedisConfig config, CacheConfig cacheConfig) {
        return new RedisCleanupJob(
                clients.topologyOperations(config.connection()),
                VertxRedisCommandClient.from(clients, config.connection()),
                config,
                cacheConfig,
                RedisCleanupJob.Policy.defaults(),
                new Object(),
                () -> ThreadLocalRandom.current().nextInt(),
                System::nanoTime);
    }

    @Provides
    @Singleton
    @IntoSet
    static ApplicationShutdownStep redisCleanupLifecycle(
            RedisCleanupJob job,
            Optional<CronScheduler> scheduler,
            RedisClientRegistry clients,
            Vertx vertx,
            EventBusClient eventBus) {
        Optional<MessageConsumer<Object>> cleanupConsumer = scheduler.map(ignored -> {
            MessageConsumer<Object> consumer = new RedisCleanupHandler(job, eventBus).register(vertx);
            job.register(ignored);
            return consumer;
        });
        return new RedisCleanupLifecycle(
                job,
                () -> cleanupConsumer
                        .map(MessageConsumer::unregister)
                        .orElseGet(Future::succeededFuture)
                        .compose(ignored -> job.unregister()),
                clients::close);
    }
}
