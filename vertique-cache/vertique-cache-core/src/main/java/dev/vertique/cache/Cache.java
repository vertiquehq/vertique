// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheIdentityResolver;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheValueDescriptor;
import dev.vertique.cache.spi.ResolvedCacheKey;
import dev.vertique.cache.spi.event.CacheOperation;
import dev.vertique.cache.spi.event.CacheOutcome;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import io.vertx.core.Future;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Immutable, reusable cache-aside handle. */
public final class Cache<K, V> {
    private final CacheStoreResolver stores;
    private final CacheConfig config;
    private final Set<CacheObserver> observers;
    private final Optional<CacheIdentityResolver> identityResolver;
    private final CacheRegion region;
    private final Type valueType;
    private final String jsonProfile;
    private final CacheMode mode;
    private final long ttlSeconds;
    private final CacheIdentity identity;
    private final AnonymousCachePolicy anonymousPolicy;
    private final Function<? super K, String> selector;
    private final boolean asynchronousOnly;

    Cache(
            CacheStoreResolver stores,
            CacheConfig config,
            Set<CacheObserver> observers,
            Optional<CacheIdentityResolver> identityResolver,
            String name,
            Type valueType,
            String jsonProfile,
            CacheMode mode,
            long ttlSeconds,
            CacheIdentity identity,
            AnonymousCachePolicy anonymousPolicy,
            Function<? super K, String> selector,
            boolean asynchronousOnly) {
        this.stores = stores;
        this.config = config;
        this.observers = Set.copyOf(observers);
        this.identityResolver = identityResolver;
        this.region = new CacheRegion("cache", name, 2);
        this.valueType = valueType;
        this.jsonProfile = jsonProfile;
        this.mode = mode;
        this.ttlSeconds = ttlSeconds;
        this.identity = identity;
        this.anonymousPolicy = anonymousPolicy;
        this.selector = selector;
        this.asynchronousOnly = asynchronousOnly;
    }

    /** Loads a value on a miss and reuses it for subsequent calls. */
    public Future<V> get(K input, Function<? super K, Future<V>> loader) {
        if (loader == null) {
            return Future.failedFuture(new NullPointerException("loader"));
        }
        if (input == null) {
            observe("none", CacheOperation.GET, CacheOutcome.BYPASS_SELECTOR, System.nanoTime());
            return invokeLoader(input, loader);
        }
        if (!config.enabled()) {
            observe("none", CacheOperation.GET, CacheOutcome.DISABLED, System.nanoTime());
            return invokeLoader(input, loader);
        }
        if (asynchronousOnly && mode == CacheMode.CLUSTERED) {
            observe("none", CacheOperation.GET, CacheOutcome.BYPASS_SELECTOR, System.nanoTime());
            return invokeLoader(input, loader);
        }
        ResolvedCacheKey key;
        CacheStoreSelection selection;
        try {
            key = key(input);
            if (key.canonical().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > config.maxKeyBytes()) {
                observe("none", CacheOperation.GET, CacheOutcome.BYPASS_KEY_SIZE, System.nanoTime());
                return invokeLoader(input, loader);
            }
            selection = stores.resolve(mode);
        } catch (RuntimeException invalidOrUnavailable) {
            observe(
                    "none",
                    CacheOperation.GET,
                    invalidOrUnavailable instanceof IdentityUnavailable
                            ? CacheOutcome.BYPASS_IDENTITY
                            : CacheOutcome.BYPASS_SELECTOR,
                    System.nanoTime());
            return invokeLoader(input, loader);
        }
        long startedAt = System.nanoTime();
        Future<Optional<Object>> lookup;
        try {
            lookup = bounded(
                    selection.store().get(key, descriptor()),
                    selection.providerId(),
                    CacheOperation.GET,
                    startedAt,
                    result -> result.succeeded() && result.result().isPresent()
                            ? CacheOutcome.HIT
                            : result.succeeded() ? CacheOutcome.MISS : CacheOutcome.ERROR);
        } catch (Throwable failure) {
            observe(selection.providerId(), CacheOperation.GET, CacheOutcome.ERROR, startedAt);
            return loadAndStore(input, loader, selection, key);
        }
        return lookup.onFailure(failure -> observe(
                        selection.providerId(),
                        CacheOperation.GET,
                        isTimeout(failure) ? CacheOutcome.TIMEOUT : CacheOutcome.ERROR,
                        startedAt))
                .recover(failure -> Future.succeededFuture(null))
                .compose(hit -> {
                    if (hit == null) {
                        return loadAndStore(input, loader, selection, key);
                    }
                    if (hit.isPresent()) {
                        observe(selection.providerId(), CacheOperation.GET, CacheOutcome.HIT, startedAt);
                        @SuppressWarnings("unchecked")
                        V value = (V) hit.get();
                        return Future.succeededFuture(value);
                    }
                    observe(selection.providerId(), CacheOperation.GET, CacheOutcome.MISS, startedAt);
                    return loadAndStore(input, loader, selection, key);
                });
    }

    /** Invalidates the entry for the current identity bucket. */
    public Future<Boolean> invalidate(K input) {
        if (input == null) {
            observe("none", CacheOperation.EVICT, CacheOutcome.BYPASS_SELECTOR, System.nanoTime());
            return Future.succeededFuture(false);
        }
        if (!config.enabled()) {
            observe("none", CacheOperation.EVICT, CacheOutcome.DISABLED, System.nanoTime());
            return Future.succeededFuture(false);
        }
        try {
            CacheStoreSelection selection = stores.resolve(mode);
            ResolvedCacheKey key = key(input);
            if (key.canonical().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > config.maxKeyBytes()) {
                observe(selection.providerId(), CacheOperation.EVICT, CacheOutcome.BYPASS_KEY_SIZE, System.nanoTime());
                return Future.succeededFuture(false);
            }
            long startedAt = System.nanoTime();
            return bounded(
                            selection.store().evict(key),
                            selection.providerId(),
                            CacheOperation.EVICT,
                            startedAt,
                            result -> result.succeeded() ? CacheOutcome.SUCCESS : CacheOutcome.ERROR)
                    .onSuccess(ignored ->
                            observe(selection.providerId(), CacheOperation.EVICT, CacheOutcome.SUCCESS, startedAt))
                    .onFailure(failure -> observe(
                            selection.providerId(),
                            CacheOperation.EVICT,
                            isTimeout(failure) ? CacheOutcome.TIMEOUT : CacheOutcome.ERROR,
                            startedAt))
                    .map(true)
                    .recover(ignored -> Future.succeededFuture(false));
        } catch (Throwable failure) {
            observe(
                    "none",
                    CacheOperation.EVICT,
                    failure instanceof IdentityUnavailable
                            ? CacheOutcome.BYPASS_IDENTITY
                            : CacheOutcome.BYPASS_SELECTOR,
                    System.nanoTime());
            return Future.succeededFuture(false);
        }
    }

    /** Invalidates the complete logical cache region. */
    public Future<Boolean> invalidateAll() {
        if (!config.enabled()) {
            observe("none", CacheOperation.CLEAR, CacheOutcome.DISABLED, System.nanoTime());
            return Future.succeededFuture(false);
        }
        try {
            CacheStoreSelection selection = stores.resolve(mode);
            long startedAt = System.nanoTime();
            return bounded(
                            selection.store().clear(region),
                            selection.providerId(),
                            CacheOperation.CLEAR,
                            startedAt,
                            result -> result.succeeded() ? CacheOutcome.SUCCESS : CacheOutcome.ERROR)
                    .onSuccess(ignored ->
                            observe(selection.providerId(), CacheOperation.CLEAR, CacheOutcome.SUCCESS, startedAt))
                    .onFailure(failure -> observe(
                            selection.providerId(),
                            CacheOperation.CLEAR,
                            isTimeout(failure) ? CacheOutcome.TIMEOUT : CacheOutcome.ERROR,
                            startedAt))
                    .map(true)
                    .recover(ignored -> Future.succeededFuture(false));
        } catch (Throwable failure) {
            observe("none", CacheOperation.CLEAR, CacheOutcome.ERROR, System.nanoTime());
            return Future.succeededFuture(false);
        }
    }

    private Future<V> invokeLoader(K input, Function<? super K, Future<V>> loader) {
        try {
            Future<V> result = loader.apply(input);
            return result == null
                    ? Future.failedFuture(new NullPointerException("loader returned null Future"))
                    : result;
        } catch (Throwable failure) {
            return Future.failedFuture(failure);
        }
    }

    private Future<V> loadAndStore(
            K input, Function<? super K, Future<V>> loader, CacheStoreSelection selection, ResolvedCacheKey key) {
        return invokeLoader(input, loader).compose(value -> {
            if (value == null) {
                observe(selection.providerId(), CacheOperation.PUT, CacheOutcome.SKIPPED_NULL, System.nanoTime());
                return Future.succeededFuture((V) null);
            }
            long putStartedAt = System.nanoTime();
            try {
                Future<Void> put = bounded(
                        selection.store().put(key, descriptor(), value, Duration.ofSeconds(ttlSeconds)),
                        selection.providerId(),
                        CacheOperation.PUT,
                        putStartedAt,
                        result -> result.succeeded() ? CacheOutcome.SUCCESS : CacheOutcome.ERROR);
                return put.onSuccess(ignored ->
                                observe(selection.providerId(), CacheOperation.PUT, CacheOutcome.SUCCESS, putStartedAt))
                        .onFailure(failure -> observe(
                                selection.providerId(),
                                CacheOperation.PUT,
                                isTimeout(failure) ? CacheOutcome.TIMEOUT : CacheOutcome.ERROR,
                                putStartedAt))
                        .recover(ignored -> Future.succeededFuture())
                        .map(value);
            } catch (Throwable failure) {
                observe(selection.providerId(), CacheOperation.PUT, CacheOutcome.ERROR, putStartedAt);
                return Future.succeededFuture(value);
            }
        });
    }

    private ResolvedCacheKey key(K input) {
        String rendered;
        try {
            rendered = selector.apply(input);
        } catch (Throwable failure) {
            throw new SelectorFailure(failure);
        }
        if (rendered == null || rendered.isBlank()) {
            throw new SelectorFailure(new IllegalArgumentException("cache selector must not be blank"));
        }
        String identityComponent = identityComponent();
        if (identityComponent == null) {
            throw new IdentityUnavailable();
        }
        return new ResolvedCacheKey(region, identityComponent, rendered);
    }

    private CacheValueDescriptor descriptor() {
        return new CacheValueDescriptor(valueType, jsonProfile);
    }

    private String identityComponent() {
        if (identity == CacheIdentity.NONE) {
            return "i2:N";
        }
        Optional<SecurityIdentity> typed = identityResolver.flatMap(resolver -> {
            try {
                return resolver.current();
            } catch (RuntimeException unavailable) {
                return Optional.empty();
            }
        });
        if (typed.isPresent()) {
            SecurityIdentity current = typed.get();
            if (current.actor().type() == PrincipalType.ANONYMOUS) {
                return anonymousPolicy == AnonymousCachePolicy.CACHE_AS_ANONYMOUS ? "i2:A" : null;
            }
            return frameIdentity(current);
        }
        return anonymousPolicy == AnonymousCachePolicy.CACHE_AS_ANONYMOUS ? "i2:A" : null;
    }

    private String frameIdentity(SecurityIdentity current) {
        return switch (identity) {
            case ACTOR -> "i2:P" + frame(current.actor());
            case EFFECTIVE_PRINCIPAL -> "i2:P" + frame(current.subject().orElse(current.actor()));
            case ACTOR_AND_SUBJECT ->
                current.subject()
                        .map(subject -> "i2:S" + frame(current.actor()) + "1" + frame(subject))
                        .orElse("i2:S" + frame(current.actor()) + "0");
            case NONE -> "i2:N";
        };
    }

    private static String frame(PrincipalRef principal) {
        String type = principal.type().name();
        String id = principal.id();
        for (int i = 0; i < id.length(); i++) {
            char ch = id.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= id.length() || !Character.isLowSurrogate(id.charAt(++i)))
                    throw new IllegalArgumentException("ill-formed identity id");
            } else if (Character.isLowSurrogate(ch)) throw new IllegalArgumentException("ill-formed identity id");
        }
        String encoded = percentIdentity(id);
        return type.length() + ":" + type + encoded.length() + ":" + encoded;
    }

    private static String percentIdentity(String value) {
        StringBuilder result = new StringBuilder();
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            char ch = (char) (b & 0xff);
            if (ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9' || "._~-".indexOf(ch) >= 0)
                result.append(ch);
            else result.append('%').append(String.format(java.util.Locale.ROOT, "%02X", b & 0xff));
        }
        return result.toString();
    }

    private void observe(String provider, CacheOperation operation, CacheOutcome outcome, long startedAt) {
        CacheObservationSupport.completed(observers, provider, operation, region.name(), outcome, startedAt);
    }

    private <T> Future<T> bounded(
            Future<T> future,
            String provider,
            CacheOperation operation,
            long startedAt,
            Function<io.vertx.core.AsyncResult<T>, CacheOutcome> lateOutcome) {
        if (future == null) return Future.failedFuture(new NullPointerException("cache store returned null Future"));
        AtomicBoolean sourceSettled = new AtomicBoolean();
        AtomicBoolean timedOut = new AtomicBoolean();
        future.onComplete(result -> {
            sourceSettled.set(true);
            if (timedOut.get())
                CacheObservationSupport.late(
                        observers, provider, operation, region.name(), lateOutcome.apply(result), startedAt);
        });
        Future<T> bounded = future.timeout(config.backendTimeoutMs(), TimeUnit.MILLISECONDS);
        return bounded.recover(failure -> {
            if (!sourceSettled.get() && failure instanceof java.util.concurrent.TimeoutException) {
                timedOut.set(true);
                return Future.failedFuture(new CacheDeadlineExceeded(failure));
            }
            return Future.failedFuture(failure);
        });
    }

    private static boolean isTimeout(Throwable failure) {
        // Only the shared deadline wrapper winning its race classifies as timeout; a
        // provider failure merely named or typed as a timeout stays a provider error.
        return failure instanceof CacheDeadlineExceeded;
    }

    private static final class CacheDeadlineExceeded extends RuntimeException {
        CacheDeadlineExceeded(Throwable cause) {
            super(cause);
        }
    }

    private static final class SelectorFailure extends RuntimeException {
        SelectorFailure(Throwable cause) {
            super(cause);
        }
    }

    private static final class IdentityUnavailable extends RuntimeException {}
}
