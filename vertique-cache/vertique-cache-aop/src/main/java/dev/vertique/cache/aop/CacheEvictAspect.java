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

/** Annotation adapter that delegates invalidation to a prepared {@link Cache}. */
@Singleton
public final class CacheEvictAspect implements AspectProvider<CacheEvict> {
    private final CacheAnnotationAdapter adapter;

    @Inject
    CacheEvictAspect(CacheAnnotationAdapter adapter) {
        this.adapter = adapter;
    }

    /** Direct construction hook for isolated adapter tests. */
    public CacheEvictAspect(
            CacheStore store, dev.vertique.cache.config.CacheConfig config, Set<CacheObserver> observers) {
        this(new CacheAnnotationAdapter(store, config, observers));
    }

    @Override
    public MethodInterceptor interceptor(MethodMetadata target, CacheEvict annotation) {
        CacheAnnotationAdapter.PreparedEviction eviction;
        try {
            eviction = adapter.prepared(target, annotation);
        } catch (RuntimeException invalidDefinition) {
            return invocation -> invocation.proceed();
        }
        return invocation -> invocation.proceed().compose(result -> eviction.run(invocation.arguments())
                .map(result));
    }
}
