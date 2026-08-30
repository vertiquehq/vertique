// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.aop;

import dev.vertique.aop.AspectProvider;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.cache.Cache;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.core.codegen.MethodMetadata;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Set;

/** Annotation adapter that delegates cache behavior to a prepared {@link Cache}. */
@Singleton
public final class CacheableAspect implements AspectProvider<Cacheable> {
    private final CacheAnnotationAdapter adapter;

    @Inject
    CacheableAspect(CacheAnnotationAdapter adapter) {
        this.adapter = adapter;
    }

    /** Direct construction hook for isolated adapter tests. */
    public CacheableAspect(
            CacheStore store, dev.vertique.cache.config.CacheConfig config, Set<CacheObserver> observers) {
        this(new CacheAnnotationAdapter(store, config, observers));
    }

    /** Direct construction hook for isolated adapter tests with a custom identity resolver. */
    public CacheableAspect(
            CacheStore store,
            dev.vertique.cache.config.CacheConfig config,
            Set<CacheObserver> observers,
            Set<dev.vertique.cache.spi.CacheIdentityResolver> identityResolvers) {
        this(new CacheAnnotationAdapter(store, config, observers, identityResolvers));
    }

    @Override
    public MethodInterceptor interceptor(MethodMetadata target, Cacheable annotation) {
        Cache<Object, Object> cache = adapter.cacheable(target, annotation);
        return invocation -> cache.get(invocation.arguments(), ignored -> invocation.proceed());
    }
}
