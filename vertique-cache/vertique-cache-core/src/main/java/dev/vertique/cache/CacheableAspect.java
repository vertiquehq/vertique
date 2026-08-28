// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.aop.AspectProvider;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheIdentityResolver;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.core.codegen.MethodMetadata;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Set;

/** Annotation adapter that delegates cache behavior to a prepared {@link Cache}. */
@Singleton
public final class CacheableAspect implements AspectProvider<Cacheable> {
    private final CacheBuilder builder;

    @Inject
    CacheableAspect(CacheBuilder builder) {
        this.builder = builder;
    }

    /** Compatibility constructor retained while the Alpha cache SPI is migrated. */
    public CacheableAspect(CacheStore store, CacheConfig config) {
        this(CacheBuilder.forLegacyTesting(store, config, java.util.Set.of(), java.util.Set.of()));
    }

    /** Compatibility constructor retained while the Alpha cache SPI is migrated. */
    public CacheableAspect(CacheStore store, CacheConfig config, Set<CacheObserver> observers) {
        this(CacheBuilder.forLegacyTesting(store, config, observers, java.util.Set.of()));
    }

    /** Compatibility constructor retained while the Alpha cache SPI is migrated. */
    public CacheableAspect(
            CacheStore store,
            CacheConfig config,
            Set<CacheObserver> observers,
            Set<CacheIdentityResolver> identityResolvers) {
        this(CacheBuilder.forLegacyTesting(store, config, observers, identityResolvers));
    }

    @Override
    public MethodInterceptor interceptor(MethodMetadata target, Cacheable annotation) {
        Cache<Object, Object> cache = builder.annotation(target, annotation);
        return invocation -> cache.get(invocation.arguments(), ignored -> invocation.proceed());
    }
}
