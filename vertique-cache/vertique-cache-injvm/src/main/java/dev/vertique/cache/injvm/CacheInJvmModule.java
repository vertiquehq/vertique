// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.injvm;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dev.vertique.cache.CacheCoreModule;
import dev.vertique.cache.CacheMode;
import dev.vertique.cache.CacheModeKey;
import dev.vertique.cache.CacheProviderIdKey;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.json.JsonRuntimeModule;
import jakarta.inject.Singleton;

/** Dagger contribution for the local Caffeine cache store. */
@Module(includes = {CacheCoreModule.class, JsonRuntimeModule.class})
public final class CacheInJvmModule {
    @Provides
    @IntoMap
    @CacheModeKey(CacheMode.LOCAL)
    @Singleton
    static CacheStore cacheStore(CacheConfig config) {
        return new CaffeineCacheStore(config);
    }

    @Provides
    @IntoMap
    @CacheProviderIdKey(CacheMode.LOCAL)
    static String providerId() {
        return "caffeine";
    }
}
