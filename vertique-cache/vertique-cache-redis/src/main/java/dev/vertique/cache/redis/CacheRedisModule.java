// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import dagger.Module;
import dagger.Provides;
import dev.vertique.cache.CacheCoreModule;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.json.JsonRuntimeModule;
import dev.vertique.redis.RedisClientRegistry;
import dev.vertique.redis.RedisConnectionModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/** Dagger contribution for the clustered Redis cache store. */
@Module(includes = {CacheCoreModule.class, JsonRuntimeModule.class, RedisConnectionModule.class})
public final class CacheRedisModule {
    @Provides
    @Singleton
    static CacheRedisConfig cacheRedisConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "cache.redis"), CacheRedisConfig.class);
    }

    @Provides
    @Singleton
    static CacheStore cacheStore(
            RedisClientRegistry clients,
            CacheRedisConfig config,
            CacheConfig cacheConfig,
            JsonMapperProfileRegistry profiles,
            Vertx vertx) {
        return new RedisCacheStore(clients, config, cacheConfig, profiles, vertx);
    }
}
