// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dagger.Module;
import dagger.Provides;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/** Dagger configuration contribution shared by all cache providers. */
@Module
public final class CacheCoreModule {
    @Provides
    @Singleton
    static CacheConfig cacheConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        JsonObject cache = JsonConfigPaths.navigateObject(config, "cache");
        return cache.isEmpty() ? CacheConfig.defaults() : parser.parse(cache, CacheConfig.class);
    }
}
