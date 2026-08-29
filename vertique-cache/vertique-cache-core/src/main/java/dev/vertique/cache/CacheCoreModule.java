// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheIdentityResolver;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Dagger configuration contribution shared by all cache providers. */
@Module(includes = ContextRuntimeModule.class)
public abstract class CacheCoreModule {
    @Multibinds
    abstract Map<CacheMode, CacheStore> cacheStores();

    @Multibinds
    abstract Map<CacheMode, String> cacheProviderIds();

    @Multibinds
    abstract Set<dev.vertique.cache.spi.CacheObserver> cacheObservers();

    @BindsOptionalOf
    abstract CacheIdentityResolver optionalCacheIdentityResolver();

    @Provides
    @Singleton
    static CacheBuilder cacheBuilder(
            CacheStoreResolver stores,
            CacheConfig config,
            Set<dev.vertique.cache.spi.CacheObserver> observers,
            DefaultCacheIdentityResolver defaultResolver,
            Optional<CacheIdentityResolver> customResolver) {
        return new CacheBuilder(stores, config, observers, Optional.of(customResolver.orElse(defaultResolver)));
    }

    @Provides
    @Singleton
    static CacheConfig cacheConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        JsonObject cache = JsonConfigPaths.navigateObject(config, "cache");
        return cache.isEmpty() ? CacheConfig.defaults() : parser.parse(cache, CacheConfig.class);
    }
}
