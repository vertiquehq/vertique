// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import dagger.Module;
import dagger.Provides;
import dev.vertique.cache.CacheCoreModule;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.redis.RedisClientRegistry;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/** Dagger contribution for the clustered Redis cache store. */
@Module(includes = CacheCoreModule.class)
public final class CacheRedisModule {
    @Provides
    @Singleton
    static CacheRedisConfig cacheRedisConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "cache.redis"), CacheRedisConfig.class);
    }

    @Provides
    @Singleton
    static CacheStore cacheStore(RedisClientRegistry clients, CacheRedisConfig config) {
        return new RedisCacheStore(clients, config);
    }
}
