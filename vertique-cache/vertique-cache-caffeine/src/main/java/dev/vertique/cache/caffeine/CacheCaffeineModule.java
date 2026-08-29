// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.caffeine;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dev.vertique.cache.CacheAopModule;
import dev.vertique.cache.CacheCoreModule;
import dev.vertique.cache.CacheMode;
import dev.vertique.cache.CacheModeKey;
import dev.vertique.cache.CacheProviderIdKey;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.json.JsonRuntimeModule;
import jakarta.inject.Singleton;

/** Dagger contribution for the local Caffeine cache store. */
@Module(includes = {CacheCoreModule.class, CacheAopModule.class, JsonRuntimeModule.class})
public final class CacheCaffeineModule {
    @Provides
    @IntoMap
    @CacheModeKey(CacheMode.LOCAL)
    @Singleton
    static CacheStore cacheStore(CacheConfig config, JsonMapperProfileRegistry profiles) {
        return new CaffeineCacheStore(config, profiles);
    }

    @Provides
    @IntoMap
    @CacheProviderIdKey(CacheMode.LOCAL)
    static String providerId() {
        return "caffeine";
    }
}
