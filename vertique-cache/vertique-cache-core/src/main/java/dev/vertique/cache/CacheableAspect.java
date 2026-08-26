// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.aop.AspectProvider;
import dev.vertique.aop.Invocation;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.config.CacheEntryConfig;
import dev.vertique.cache.spi.CacheIdentityResolver;
import dev.vertique.cache.spi.CacheKey;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.core.codegen.MethodMetadata;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/** Provider-neutral around interceptor for cacheable object-facing methods. */
@Singleton
public final class CacheableAspect implements AspectProvider<Cacheable> {
    private final CacheStoreResolver stores;
    private final CacheConfig config;
    private final Set<CacheObserver> observers;
    private final Optional<CacheIdentityResolver> identityResolver;

    public CacheableAspect(CacheStore store, CacheConfig config) {
        this(store, config, Set.of(), Set.of());
    }

    public CacheableAspect(CacheStore store, CacheConfig config, Set<CacheObserver> observers) {
        this(store, config, observers, Set.of());
    }

    public CacheableAspect(
            CacheStore store,
            CacheConfig config,
            Set<CacheObserver> observers,
            Set<CacheIdentityResolver> identityResolvers) {
        this.stores = CacheStoreResolver.fixed(store);
        this.config = config;
        this.observers = Set.copyOf(observers);
        this.identityResolver = identityResolvers.size() == 1
                ? Optional.of(identityResolvers.iterator().next())
                : Optional.empty();
    }

    @Inject
    public CacheableAspect(
            CacheStoreResolver stores,
            CacheConfig config,
            Set<CacheObserver> observers,
            DefaultCacheIdentityResolver defaultResolver,
            Optional<CacheIdentityResolver> customResolver) {
        this.stores = stores;
        this.config = config;
        this.observers = Set.copyOf(observers);
        this.identityResolver = Optional.of(customResolver.orElse(defaultResolver));
    }

    @Override
    public MethodInterceptor interceptor(MethodMetadata target, Cacheable annotation) {
        CacheRegion region = new CacheRegion("cache", annotation.name(), 1);
        Type declaredType = valueType(target);
        long ttlSeconds = effectiveTtl(annotation);
        return invocation -> {
            CacheMode mode = effectiveMode(annotation);
            if (!config.enabled() || mode == CacheMode.CLUSTERED && target.returnType() != Future.class) {
                return invocation.proceed();
            }
            CacheStoreSelection selection;
            try {
                selection = stores.resolve(mode);
            } catch (RuntimeException unavailable) {
                return invocation.proceed();
            }
            CacheKey key;
            try {
                String selector = CacheKeyRenderer.render(annotation.key(), target, invocation.arguments());
                Optional<String> identityComponent = identityComponent(annotation);
                if (identityComponent.isEmpty()) {
                    return invocation.proceed();
                }
                key = new CacheKey(region, identityComponent.get(), selector);
                if (key.canonical().getBytes(StandardCharsets.UTF_8).length > config.maxKeyBytes()) {
                    return invocation.proceed();
                }
            } catch (RuntimeException invalidKey) {
                return invocation.proceed();
            }
            return lookupOrProceed(invocation, key, region, declaredType, ttlSeconds, selection);
        };
    }

    private Future<Object> lookupOrProceed(
            Invocation invocation,
            CacheKey key,
            CacheRegion region,
            Type declaredType,
            long ttlSeconds,
            CacheStoreSelection selection) {
        long startedAt = System.nanoTime();
        Future<Optional<Object>> lookup;
        try {
            lookup = selection.store().get(key, declaredType);
        } catch (Throwable failure) {
            observe(selection.providerId(), "get", region, "failure", startedAt);
            return invocation.proceed();
        }
        return lookup.recover(failure -> {
                    observe(selection.providerId(), "get", region, "failure", startedAt);
                    return Future.succeededFuture(Optional.empty());
                })
                .compose(hit -> {
                    if (hit.isPresent()) {
                        observe(selection.providerId(), "get", region, "hit", startedAt);
                        return Future.succeededFuture(hit.get());
                    }
                    observe(selection.providerId(), "get", region, "miss", startedAt);
                    return invocation.proceed().compose(value -> {
                        if (value == null) {
                            return Future.succeededFuture(null);
                        }
                        try {
                            long putStartedAt = System.nanoTime();
                            return selection
                                    .store()
                                    .put(key, value, declaredType, Duration.ofSeconds(ttlSeconds))
                                    .onSuccess(ignored ->
                                            observe(selection.providerId(), "put", region, "stored", putStartedAt))
                                    .onFailure(ignored ->
                                            observe(selection.providerId(), "put", region, "failure", putStartedAt))
                                    .recover(ignored -> Future.succeededFuture())
                                    .map(value);
                        } catch (Throwable failure) {
                            observe(selection.providerId(), "put", region, "failure", System.nanoTime());
                            return Future.succeededFuture(value);
                        }
                    });
                });
    }

    private void observe(String provider, String operation, CacheRegion region, String outcome, long startedAt) {
        CacheObservationSupport.observe(observers, provider, operation, region, outcome, startedAt);
    }

    private long effectiveTtl(Cacheable annotation) {
        CacheEntryConfig entry = config.caches().get(annotation.name());
        if (entry != null && entry.ttlSeconds() >= 0) {
            return entry.ttlSeconds();
        }
        long requested = annotation.ttlSeconds() >= 0 ? annotation.ttlSeconds() : config.defaultTtlSeconds();
        if (annotation.ttlSeconds() > config.maxTtlSeconds()) {
            throw new IllegalArgumentException("cache TTL exceeds maxTtlSeconds: " + annotation.name());
        }
        return Math.min(requested, config.maxTtlSeconds());
    }

    private CacheMode effectiveMode(Cacheable annotation) {
        CacheEntryConfig entry = config.caches().get(annotation.name());
        if (entry != null && entry.mode() != CacheMode.DEFAULT) {
            return entry.mode();
        }
        return annotation.mode() == CacheMode.DEFAULT ? config.defaultMode() : annotation.mode();
    }

    private Optional<String> identityComponent(Cacheable annotation) {
        if (annotation.identity() == CacheIdentity.NONE) {
            return Optional.of("NONE");
        }
        if (identityResolver.isEmpty()) {
            return anonymousComponent(annotation);
        }
        try {
            return identityResolver
                    .orElseThrow()
                    .resolve(annotation.identity())
                    .filter(component -> !component.isBlank())
                    .or(() -> annotation.anonymous() == AnonymousCachePolicy.CACHE_AS_ANONYMOUS
                            ? Optional.of("ANONYMOUS")
                            : Optional.empty());
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private Optional<String> anonymousComponent(Cacheable annotation) {
        return annotation.anonymous() == AnonymousCachePolicy.CACHE_AS_ANONYMOUS
                ? Optional.of("ANONYMOUS")
                : Optional.empty();
    }

    private static Type valueType(MethodMetadata target) {
        Type returnType = target.genericReturnType();
        if (returnType instanceof ParameterizedType parameterized && target.returnType() == Future.class) {
            return parameterized.getActualTypeArguments()[0];
        }
        return returnType;
    }
}
