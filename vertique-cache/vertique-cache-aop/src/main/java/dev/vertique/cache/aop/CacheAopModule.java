// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.aop;

import dagger.Binds;
import dagger.Module;
import dev.vertique.aop.AspectProvider;

/** Dagger bindings for cache annotation adapters. */
@Module
public abstract class CacheAopModule {
    @Binds
    abstract AspectProvider<Cacheable> bindCacheableAspect(CacheableAspect aspect);

    @Binds
    abstract AspectProvider<CacheEvict> bindCacheEvictAspect(CacheEvictAspect aspect);

    @Binds
    abstract AspectProvider<CacheEvict.List> bindCacheEvictListAspect(CacheEvictListAspect aspect);
}
