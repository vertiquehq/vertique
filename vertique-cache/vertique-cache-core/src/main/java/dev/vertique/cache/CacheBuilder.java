// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.config.CacheEntryConfig;
import dev.vertique.cache.spi.CacheIdentityResolver;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.CacheRegion;
import io.vertx.core.Future;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Injected factory for immutable application cache handles. */
public final class CacheBuilder {
    private final CacheStoreResolver stores;
    private final CacheConfig config;
    private final Set<CacheObserver> observers;
    private final Optional<CacheIdentityResolver> identityResolver;
    private final ConcurrentHashMap<String, DefinitionFingerprint> catalog = new ConcurrentHashMap<>();

    CacheBuilder(
            CacheStoreResolver stores,
            CacheConfig config,
            Set<CacheObserver> observers,
            Optional<CacheIdentityResolver> identityResolver) {
        this.stores = stores;
        this.config = Objects.requireNonNull(config, "config");
        this.observers = Set.copyOf(observers);
        this.identityResolver = identityResolver;
    }

    static CacheBuilder forTesting(
            dev.vertique.cache.spi.CacheStore store,
            CacheConfig config,
            Set<CacheObserver> observers,
            Set<CacheIdentityResolver> resolvers) {
        Optional<CacheIdentityResolver> resolver =
                resolvers.size() == 1 ? Optional.of(resolvers.iterator().next()) : Optional.empty();
        return new CacheBuilder(CacheStoreResolver.fixed(store), config, observers, resolver);
    }

    public <V> Definition<V> cache(String name, Class<V> valueType) {
        return new Definition<>(this, requireName(name), valueType, null, null, null, null);
    }

    public <V> Definition<V> cache(String name, TypeRef<V> valueType) {
        Objects.requireNonNull(valueType, "valueType");
        return new Definition<>(this, requireName(name), valueType.capturedType(), null, null, null, null);
    }

    /** Builds and registers a cache from a provider-neutral definition supplied by an API adapter. */
    <K, V> Cache<K, V> build(
            String name,
            Type valueType,
            CacheMode requestedMode,
            long requestedTtl,
            CacheIdentity identity,
            AnonymousCachePolicy anonymousPolicy,
            Function<Object, String> selector,
            boolean asynchronousOnly,
            String selectorPaths) {
        validateType(valueType, new java.util.HashSet<>());
        return buildResolved(
                name,
                valueType,
                requestedMode,
                requestedTtl,
                identity,
                anonymousPolicy,
                selector,
                asynchronousOnly,
                selectorPaths,
                true);
    }

    /** Builds an unregistered handle for an adapter operation that never stores values. */
    <K, V> Cache<K, V> buildUnregistered(
            String name,
            Type valueType,
            CacheMode requestedMode,
            long requestedTtl,
            CacheIdentity identity,
            AnonymousCachePolicy anonymousPolicy,
            Function<Object, String> selector,
            boolean asynchronousOnly,
            String selectorPaths) {
        return buildResolved(
                name,
                valueType,
                requestedMode,
                requestedTtl,
                identity,
                anonymousPolicy,
                selector,
                asynchronousOnly,
                selectorPaths,
                false);
    }

    private <K, V> Cache<K, V> buildResolved(
            String name,
            Type valueType,
            CacheMode requestedMode,
            long requestedTtl,
            CacheIdentity identity,
            AnonymousCachePolicy anonymousPolicy,
            Function<Object, String> selector,
            boolean asynchronousOnly,
            String selectorPaths,
            boolean register) {
        CacheMode effectiveMode = effectiveMode(name, requestedMode);
        long effectiveTtl = effectiveTtl(name, requestedTtl);
        CacheIdentity effectiveIdentity = identity == null ? CacheIdentity.EFFECTIVE_PRINCIPAL : identity;
        AnonymousCachePolicy effectiveAnonymous =
                anonymousPolicy == null ? AnonymousCachePolicy.BYPASS : anonymousPolicy;
        if (config.enabled()) stores.resolve(effectiveMode);
        if (register)
            register(
                    name, valueType, effectiveMode, effectiveTtl, effectiveIdentity, effectiveAnonymous, selectorPaths);
        return new Cache<>(
                stores,
                config,
                observers,
                identityResolver,
                name,
                valueType,
                effectiveProfile(name),
                effectiveMode,
                effectiveTtl,
                effectiveIdentity,
                effectiveAnonymous,
                selector,
                asynchronousOnly);
    }

    public abstract static class TypeRef<V> {
        protected TypeRef() {}

        private Type capturedType() {
            Type parent = getClass().getGenericSuperclass();
            if (!(parent instanceof ParameterizedType parameterized)) {
                throw new IllegalArgumentException("TypeRef must be created as an anonymous parameterized subclass");
            }
            return parameterized.getActualTypeArguments()[0];
        }
    }

    public static final class Definition<V> {
        private final CacheBuilder owner;
        private final String name;
        private final Type valueType;
        private final CacheMode mode;
        private final Duration ttl;
        private final CacheIdentity identity;
        private final AnonymousCachePolicy anonymousPolicy;

        private Definition(
                CacheBuilder owner,
                String name,
                Type valueType,
                CacheMode mode,
                Duration ttl,
                CacheIdentity identity,
                AnonymousCachePolicy anonymousPolicy) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.name = Objects.requireNonNull(name, "name");
            this.valueType = Objects.requireNonNull(valueType, "valueType");
            this.mode = mode;
            this.ttl = ttl;
            this.identity = identity;
            this.anonymousPolicy = anonymousPolicy;
        }

        public Definition<V> mode(CacheMode mode) {
            return copy(Objects.requireNonNull(mode, "mode"), ttl, identity, anonymousPolicy);
        }

        public Definition<V> ttl(Duration ttl) {
            return copy(mode, Objects.requireNonNull(ttl, "ttl"), identity, anonymousPolicy);
        }

        public Definition<V> identity(CacheIdentity identity) {
            return copy(mode, ttl, Objects.requireNonNull(identity, "identity"), anonymousPolicy);
        }

        public Definition<V> anonymous(AnonymousCachePolicy policy) {
            return copy(mode, ttl, identity, Objects.requireNonNull(policy, "policy"));
        }

        private Definition<V> copy(
                CacheMode nextMode, Duration nextTtl, CacheIdentity nextIdentity, AnonymousCachePolicy nextAnonymous) {
            return new Definition<>(owner, name, valueType, nextMode, nextTtl, nextIdentity, nextAnonymous);
        }

        public <K> KeyedDefinition<K, V> key(Function<? super K, ?> selector) {
            return new KeyedDefinition<>(owner, name, valueType, mode, ttl, identity, anonymousPolicy, selector);
        }

        public <K> Cache<K, V> build() {
            return owner.build(
                    name,
                    valueType,
                    mode,
                    ttl == null ? -1 : wholeSeconds(ttl),
                    identity,
                    anonymousPolicy,
                    CacheKey::encodeScalar,
                    false,
                    null);
        }
    }

    public static final class KeyedDefinition<K, V> {
        private final CacheBuilder owner;
        private final String name;
        private final Type valueType;
        private final CacheMode mode;
        private final Duration ttl;
        private final CacheIdentity identity;
        private final AnonymousCachePolicy anonymousPolicy;
        private final Function<? super K, ?> selector;

        private KeyedDefinition(
                CacheBuilder owner,
                String name,
                Type valueType,
                CacheMode mode,
                Duration ttl,
                CacheIdentity identity,
                AnonymousCachePolicy anonymousPolicy,
                Function<? super K, ?> selector) {
            this.owner = owner;
            this.name = name;
            this.valueType = valueType;
            this.mode = mode;
            this.ttl = ttl;
            this.identity = identity;
            this.anonymousPolicy = anonymousPolicy;
            this.selector = Objects.requireNonNull(selector, "selector");
        }

        public KeyedDefinition<K, V> mode(CacheMode mode) {
            return copy(Objects.requireNonNull(mode, "mode"), ttl, identity, anonymousPolicy);
        }

        public KeyedDefinition<K, V> ttl(Duration ttl) {
            return copy(mode, Objects.requireNonNull(ttl, "ttl"), identity, anonymousPolicy);
        }

        public KeyedDefinition<K, V> identity(CacheIdentity identity) {
            return copy(mode, ttl, Objects.requireNonNull(identity, "identity"), anonymousPolicy);
        }

        public KeyedDefinition<K, V> anonymous(AnonymousCachePolicy policy) {
            return copy(mode, ttl, identity, Objects.requireNonNull(policy, "policy"));
        }

        private KeyedDefinition<K, V> copy(
                CacheMode nextMode, Duration nextTtl, CacheIdentity nextIdentity, AnonymousCachePolicy nextAnonymous) {
            return new KeyedDefinition<>(
                    owner, name, valueType, nextMode, nextTtl, nextIdentity, nextAnonymous, selector);
        }

        public Cache<K, V> build() {
            Function<? super K, ?> declared = selector;
            return owner.build(
                    name,
                    valueType,
                    mode,
                    ttl == null ? -1 : wholeSeconds(ttl),
                    identity,
                    anonymousPolicy,
                    input -> {
                        @SuppressWarnings("unchecked")
                        K typed = (K) input;
                        return CacheKey.render(declared.apply(typed));
                    },
                    false,
                    null);
        }
    }

    private static void validateType(Type type, Set<Type> active) {
        if (!active.add(type)) throw new IllegalArgumentException("cyclic cache value type");
        try {
            if (type instanceof Class<?> clazz) {
                if (clazz.isPrimitive()
                        || clazz == void.class
                        || clazz == Void.class
                        || clazz == Object.class
                        || java.util.concurrent.CompletionStage.class.isAssignableFrom(clazz)
                        || Future.class.isAssignableFrom(clazz))
                    throw new IllegalArgumentException("unsupported cache value type: " + clazz.getTypeName());
                if (clazz.isArray()) validateType(clazz.getComponentType(), active);
                else if (clazz.getTypeParameters().length > 0)
                    throw new IllegalArgumentException("raw generic cache value type: " + clazz.getTypeName());
            } else if (type instanceof ParameterizedType parameterized) {
                if (!(parameterized.getRawType() instanceof Class<?> raw))
                    throw new IllegalArgumentException("unsupported cache value type");
                if (parameterized.getActualTypeArguments().length != raw.getTypeParameters().length)
                    throw new IllegalArgumentException("invalid cache value type");
                validateOwner(parameterized, raw, active);
                for (Type argument : parameterized.getActualTypeArguments()) validateType(argument, active);
            } else if (type instanceof TypeVariable<?>
                    || type instanceof WildcardType
                    || type instanceof GenericArrayType) {
                throw new IllegalArgumentException("unsupported cache value type: " + type.getTypeName());
            } else throw new IllegalArgumentException("unsupported cache value type: " + type);
        } finally {
            active.remove(type);
        }
    }

    private static void validateOwner(ParameterizedType type, Class<?> raw, Set<Type> active) {
        Class<?> declaring = raw.getDeclaringClass();
        Type owner = type.getOwnerType();
        if (declaring == null) {
            if (owner != null) throw new IllegalArgumentException("top-level cache value type must not have an owner");
            return;
        }
        if (java.lang.reflect.Modifier.isStatic(raw.getModifiers()) || raw.isInterface()) {
            if (owner != declaring)
                throw new IllegalArgumentException("static member cache value has an invalid owner");
            return;
        }
        if (owner == null) throw new IllegalArgumentException("non-static member cache value requires an owner");
        Class<?> ownerRaw = owner instanceof Class<?> clazz
                ? clazz
                : owner instanceof ParameterizedType parameterized
                                && parameterized.getRawType() instanceof Class<?> clazz
                        ? clazz
                        : null;
        if (ownerRaw != declaring)
            throw new IllegalArgumentException("cache value owner does not match declaring class");
        if (declaring.getTypeParameters().length > 0 && !(owner instanceof ParameterizedType)) {
            throw new IllegalArgumentException("generic member cache value requires a parameterized owner");
        }
        if (owner instanceof ParameterizedType parameterized) validateType(parameterized, active);
    }

    private static long wholeSeconds(Duration ttl) {
        if (ttl.isNegative() || ttl.getNano() != 0) {
            throw new IllegalArgumentException("cache TTL must be a whole number of seconds");
        }
        return ttl.getSeconds();
    }

    private CacheMode effectiveMode(String name, CacheMode requested) {
        CacheEntryConfig entry = config.caches().get(name);
        return entry != null && entry.mode() != CacheMode.DEFAULT
                ? entry.mode()
                : requested == null || requested == CacheMode.DEFAULT ? config.defaultMode() : requested;
    }

    private long effectiveTtl(String name, long requested) {
        CacheEntryConfig entry = config.caches().get(name);
        if (entry != null && entry.ttlSeconds() >= 0) {
            if (entry.ttlSeconds() > config.maxTtlSeconds()) {
                throw new IllegalArgumentException("cache TTL exceeds maxTtlSeconds: " + name);
            }
            return entry.ttlSeconds();
        }
        long effective = requested >= 0 ? requested : config.defaultTtlSeconds();
        if (effective > config.maxTtlSeconds())
            throw new IllegalArgumentException("cache TTL exceeds maxTtlSeconds: " + name);
        return effective;
    }

    private String effectiveProfile(String name) {
        CacheEntryConfig entry = config.caches().get(name);
        return entry != null && entry.jsonProfile() != null ? entry.jsonProfile() : config.jsonProfile();
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.length() > 128 || name.isBlank() || !name.matches("[A-Za-z0-9._~-]+")) {
            throw new IllegalArgumentException("cache name must be 1-128 ASCII cache segment characters");
        }
        return name;
    }

    /** Immutable registered-definition policy snapshot for adapter eviction resolution. */
    record RegisteredDefinition(
            Type valueType,
            CacheMode mode,
            long ttlSeconds,
            CacheIdentity identity,
            AnonymousCachePolicy anonymous,
            boolean annotationDeclared) {}

    Optional<RegisteredDefinition> registered(String name) {
        DefinitionFingerprint fingerprint = catalog.get(name);
        return fingerprint == null
                ? Optional.empty()
                : Optional.of(new RegisteredDefinition(
                        fingerprint.type(),
                        fingerprint.mode(),
                        fingerprint.ttl(),
                        fingerprint.identity(),
                        fingerprint.anonymous(),
                        fingerprint.selectorPaths() != null));
    }

    Set<CacheObserver> observers() {
        return observers;
    }

    private void register(
            String name,
            Type type,
            CacheMode mode,
            long ttl,
            CacheIdentity identity,
            AnonymousCachePolicy anonymous,
            String selectorPaths) {
        String validatedName = requireName(name);
        DefinitionFingerprint candidate = new DefinitionFingerprint(
                type,
                mode,
                ttl,
                identity,
                anonymous,
                effectiveProfile(validatedName),
                new CacheRegion("cache", validatedName, 2),
                selectorPaths);
        synchronized (catalog) {
            DefinitionFingerprint existing = catalog.get(validatedName);
            if (existing != null) {
                if (!existing.compatibleWith(candidate)) {
                    throw new IllegalStateException("incompatible definitions for cache " + validatedName);
                }
                if (existing.selectorPaths() == null && candidate.selectorPaths() != null) {
                    catalog.put(validatedName, candidate);
                }
                return;
            }
            if (catalog.size() >= 1_024) throw new IllegalStateException("cache catalog exceeds 1024 logical names");
            catalog.put(validatedName, candidate);
        }
    }

    /**
     * Declaration-time compatibility fingerprint. Annotation declarations carry their
     * ordered selector paths; programmatic selectors are opaque functions, so their
     * paths are null and same-name schema agreement is the documented caller
     * responsibility — semantic selector distinctions ride the cache name.
     */
    private record DefinitionFingerprint(
            Type type,
            CacheMode mode,
            long ttl,
            CacheIdentity identity,
            AnonymousCachePolicy anonymous,
            String jsonProfile,
            CacheRegion region,
            String selectorPaths) {

        boolean compatibleWith(DefinitionFingerprint candidate) {
            return type.equals(candidate.type())
                    && mode == candidate.mode()
                    && ttl == candidate.ttl()
                    && identity == candidate.identity()
                    && anonymous == candidate.anonymous()
                    && Objects.equals(jsonProfile, candidate.jsonProfile())
                    && region.equals(candidate.region())
                    && (selectorPaths == null
                            || candidate.selectorPaths() == null
                            || selectorPaths.equals(candidate.selectorPaths()));
        }
    }
}
