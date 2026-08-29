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
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
            String layout) {
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
                layout,
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
            String layout) {
        return buildResolved(
                name,
                valueType,
                requestedMode,
                requestedTtl,
                identity,
                anonymousPolicy,
                selector,
                asynchronousOnly,
                layout,
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
            String layout,
            boolean register) {
        CacheMode effectiveMode = effectiveMode(name, requestedMode);
        long effectiveTtl = effectiveTtl(name, requestedTtl);
        CacheIdentity effectiveIdentity = identity == null ? CacheIdentity.EFFECTIVE_PRINCIPAL : identity;
        AnonymousCachePolicy effectiveAnonymous =
                anonymousPolicy == null ? AnonymousCachePolicy.BYPASS : anonymousPolicy;
        if (config.enabled()) stores.resolve(effectiveMode);
        if (register)
            register(name, valueType, effectiveMode, effectiveTtl, effectiveIdentity, effectiveAnonymous, layout);
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

        @SafeVarargs
        public final <K> KeyedDefinition<K, V> key(String keyLayout, Function<? super K, ?>... components) {
            return new KeyedDefinition<>(
                    owner, name, valueType, mode, ttl, identity, anonymousPolicy, keyLayout, components);
        }

        public <K> Cache<K, V> build() {
            return owner.build(
                    name,
                    valueType,
                    mode,
                    ttl == null ? -1 : wholeSeconds(ttl),
                    identity,
                    anonymousPolicy,
                    input -> scalar(input),
                    false,
                    "{0}");
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
        private final String layout;
        private final List<Function<? super K, ?>> components;

        @SafeVarargs
        private KeyedDefinition(
                CacheBuilder owner,
                String name,
                Type valueType,
                CacheMode mode,
                Duration ttl,
                CacheIdentity identity,
                AnonymousCachePolicy anonymousPolicy,
                String layout,
                Function<? super K, ?>... components) {
            this.owner = owner;
            this.name = name;
            this.valueType = valueType;
            this.mode = mode;
            this.ttl = ttl;
            this.identity = identity;
            this.anonymousPolicy = anonymousPolicy;
            this.layout = Objects.requireNonNull(layout, "keyLayout");
            this.components = List.of(components.clone());
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
            @SuppressWarnings("unchecked")
            Function<? super K, ?>[] functions = components.toArray(Function[]::new);
            return new KeyedDefinition<>(
                    owner, name, valueType, nextMode, nextTtl, nextIdentity, nextAnonymous, layout, functions);
        }

        public Cache<K, V> build() {
            Layout parsed = Layout.parse(layout, components.size());
            return owner.build(
                    name,
                    valueType,
                    mode,
                    ttl == null ? -1 : wholeSeconds(ttl),
                    identity,
                    anonymousPolicy,
                    input -> parsed.render(input, components),
                    false,
                    layout);
        }
    }

    static String scalar(Object value) {
        if (value == null) throw new IllegalArgumentException("cache selector input must not be null");
        return Scalar.encode(value);
    }

    private static final class Layout {
        private final List<Object> nodes;

        private Layout(List<Object> nodes) {
            this.nodes = List.copyOf(nodes);
        }

        static Layout parse(String source, int componentCount) {
            if (source.length() > 256 || source.isEmpty())
                throw new IllegalArgumentException("invalid cache key layout");
            List<Object> nodes = new ArrayList<>();
            StringBuilder literal = new StringBuilder();
            int functions = 0;
            for (int i = 0; i < source.length(); ) {
                char ch = source.charAt(i++);
                if (ch == '{') {
                    if (i < source.length() && source.charAt(i) == '{') {
                        literal.append('{');
                        i++;
                        continue;
                    }
                    if (literal.length() > 0) {
                        nodes.add(new Literal(literal.toString()));
                        literal.setLength(0);
                    }
                    int end = source.indexOf('}', i);
                    if (end < 0 || end == i) throw new IllegalArgumentException("invalid cache key layout");
                    String token = source.substring(i, end);
                    if (!token.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '.')) {
                        throw new IllegalArgumentException("invalid cache key token");
                    }
                    nodes.add(new Token(token));
                    functions++;
                    i = end + 1;
                } else if (ch == '}') {
                    if (i < source.length() && source.charAt(i) == '}') {
                        literal.append('}');
                        i++;
                    } else throw new IllegalArgumentException("invalid cache key layout");
                } else {
                    if (!isLiteral(ch)) throw new IllegalArgumentException("invalid cache key literal");
                    literal.append(ch);
                }
            }
            if (literal.length() > 0) nodes.add(new Literal(literal.toString()));
            if (functions != componentCount) throw new IllegalArgumentException("cache key component count mismatch");
            int previousToken = -1;
            for (int i = 0; i < nodes.size(); i++) {
                if (!(nodes.get(i) instanceof Token)) continue;
                if (previousToken >= 0) {
                    boolean boundary = false;
                    for (int j = previousToken + 1; j < i; j++) {
                        if (nodes.get(j) instanceof Literal literalNode
                                && literalNode.value().chars().anyMatch(Layout::isBoundary)) {
                            boundary = true;
                            break;
                        }
                    }
                    if (!boundary) throw new IllegalArgumentException("cache key components require a raw boundary");
                }
                previousToken = i;
            }
            return new Layout(nodes);
        }

        String render(Object input, List<? extends Function<?, ?>> functions) {
            StringBuilder result = new StringBuilder();
            int functionIndex = 0;
            for (Object node : nodes) {
                if (node instanceof Literal literal) {
                    result.append(literal.value());
                } else {
                    @SuppressWarnings("unchecked")
                    Object value = ((Function<Object, ?>) functions.get(functionIndex++)).apply(input);
                    result.append(Scalar.encode(value));
                }
            }
            return result.toString();
        }

        private static boolean isLiteral(char ch) {
            return ch <= 0x7f
                    && (ch >= 'A' && ch <= 'Z'
                            || ch >= 'a' && ch <= 'z'
                            || ch >= '0' && ch <= '9'
                            || "._~:/-=%%".indexOf(ch) >= 0);
        }

        private static boolean isBoundary(int ch) {
            return ch == ':' || ch == '/' || ch == '=' || ch == '{' || ch == '}';
        }

        private record Literal(String value) {}

        private record Token(String value) {}
    }

    private static final class Scalar {
        static String encode(Object value) {
            Objects.requireNonNull(value, "cache selector component");
            String tag;
            String payload;
            if (value instanceof String string) {
                tag = "S";
                payload = text(string);
            } else if (value instanceof Character character) {
                if (Character.isSurrogate(character))
                    throw new IllegalArgumentException("surrogate Character is unsupported");
                tag = "C";
                payload = text(character.toString());
            } else if (value instanceof Boolean) {
                tag = "Z";
                payload = value.toString();
            } else if (value instanceof Byte) {
                tag = "B";
                payload = value.toString();
            } else if (value instanceof Short) {
                tag = "H";
                payload = value.toString();
            } else if (value instanceof Integer) {
                tag = "I";
                payload = value.toString();
            } else if (value instanceof Long) {
                tag = "L";
                payload = value.toString();
            } else if (value instanceof java.math.BigInteger) {
                tag = "N";
                payload = value.toString();
            } else if (value instanceof Float f && Float.isFinite(f)) {
                tag = "F";
                payload = f.toString();
            } else if (value instanceof Double d && Double.isFinite(d)) {
                tag = "D";
                payload = d.toString();
            } else if (value instanceof java.math.BigDecimal decimal) {
                tag = "M";
                payload = decimal.scale() + "~" + decimal.unscaledValue();
            } else if (value instanceof Enum<?> enumeration) {
                tag = "E";
                String type = percent(enumeration.getDeclaringClass().getName());
                payload = type.length() + "~" + type + percent(enumeration.name());
            } else if (value instanceof UUID) {
                tag = "U";
                payload = value.toString();
            } else if (value instanceof java.time.Instant) {
                tag = "T";
                payload = value.toString();
            } else if (value instanceof java.time.LocalDate) {
                tag = "A";
                payload = value.toString();
            } else if (value instanceof java.time.LocalDateTime) {
                tag = "J";
                payload = value.toString();
            } else if (value instanceof java.time.OffsetDateTime) {
                tag = "O";
                payload = value.toString();
            } else if (value instanceof java.time.ZonedDateTime) {
                tag = "W";
                payload = value.toString();
            } else
                throw new IllegalArgumentException("unsupported cache selector component: "
                        + value.getClass().getName());
            return "k2" + tag + percent(payload);
        }

        private static String text(String value) {
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (Character.isHighSurrogate(ch)) {
                    if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) {
                        throw new IllegalArgumentException("cache selector contains ill-formed UTF-16");
                    }
                } else if (Character.isLowSurrogate(ch)) {
                    throw new IllegalArgumentException("cache selector contains ill-formed UTF-16");
                }
            }
            return Normalizer.normalize(value, Normalizer.Form.NFC);
        }

        private static String percent(String value) {
            StringBuilder result = new StringBuilder();
            for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                char ch = (char) (b & 0xff);
                if (ch >= 'A' && ch <= 'Z'
                        || ch >= 'a' && ch <= 'z'
                        || ch >= '0' && ch <= '9'
                        || "._~%-".indexOf(ch) >= 0) result.append(ch);
                else result.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xff));
            }
            return result.toString();
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

    private void register(
            String name,
            Type type,
            CacheMode mode,
            long ttl,
            CacheIdentity identity,
            AnonymousCachePolicy anonymous,
            String layout) {
        String validatedName = requireName(name);
        DefinitionFingerprint candidate = new DefinitionFingerprint(
                type,
                mode,
                ttl,
                identity,
                anonymous,
                effectiveProfile(validatedName),
                new CacheRegion("cache", validatedName, 2),
                normalizeLayout(layout));
        synchronized (catalog) {
            DefinitionFingerprint existing = catalog.get(validatedName);
            if (existing != null) {
                if (!existing.equals(candidate)) {
                    throw new IllegalStateException("incompatible definitions for cache " + validatedName);
                }
                return;
            }
            if (catalog.size() >= 1_024) throw new IllegalStateException("cache catalog exceeds 1024 logical names");
            catalog.put(validatedName, candidate);
        }
    }

    private static String normalizeLayout(String layout) {
        Objects.requireNonNull(layout, "keyLayout");
        return layout.replace("{{", "{").replace("}}", "}");
    }

    private record DefinitionFingerprint(
            Type type,
            CacheMode mode,
            long ttl,
            CacheIdentity identity,
            AnonymousCachePolicy anonymous,
            String jsonProfile,
            CacheRegion region,
            String layout) {}
}
