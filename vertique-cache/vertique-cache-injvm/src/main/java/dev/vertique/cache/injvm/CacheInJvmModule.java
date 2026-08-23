// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.injvm;

import dagger.Module;
import dagger.Provides;
import dev.vertique.cache.CacheCoreModule;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheStore;
import jakarta.inject.Singleton;

/** Dagger contribution for the local Caffeine cache store. */
@Module(includes = CacheCoreModule.class)
public final class CacheInJvmModule {
    @Provides
    @Singleton
    static CacheStore cacheStore(CacheConfig config) {
        return new CaffeineCacheStore(config);
    }
}
