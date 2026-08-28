// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.config.CacheEntryConfig;
import dev.vertique.cache.spi.CacheIdentityResolver;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.GeneratedCacheMetadata;
import dev.vertique.core.codegen.MethodMetadata;
import io.vertx.core.Future;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final int regionVersion;
    private final boolean legacyObservationOutcomes;
    private final ConcurrentHashMap<String, DefinitionFingerprint> catalog = new ConcurrentHashMap<>();
    private final Map<GeneratedCacheMetadata.OperationId, PreparedOperation> preparedOperations;

    CacheBuilder(
            CacheStoreResolver stores,
            CacheConfig config,
            Set<CacheObserver> observers,
            Optional<CacheIdentityResolver> identityResolver) {
        this(stores, config, observers, identityResolver, Set.of(), 2, false);
    }

    CacheBuilder(
            CacheStoreResolver stores,
            CacheConfig config,
            Set<CacheObserver> observers,
            Optional<CacheIdentityResolver> identityResolver,
            Set<GeneratedCacheMetadata> generatedMetadata,
            int regionVersion,
            boolean legacyObservationOutcomes) {
        this.stores = stores;
        this.config = Objects.requireNonNull(config, "config");
        this.observers = Set.copyOf(observers);
        this.identityResolver = identityResolver;
        this.regionVersion = regionVersion;
        this.legacyObservationOutcomes = legacyObservationOutcomes;
        validateGeneratedMetadata(generatedMetadata);
        this.preparedOperations = prepareGeneratedMetadata(generatedMetadata);
    }

    static CacheBuilder forTesting(
            dev.vertique.cache.spi.CacheStore store,
            CacheConfig config,
            Set<CacheObserver> observers,
            Set<CacheIdentityResolver> resolvers) {
        Optional<CacheIdentityResolver> resolver =
                resolvers.size() == 1 ? Optional.of(resolvers.iterator().next()) : Optional.empty();
        return new CacheBuilder(CacheStoreResolver.fixed(store), config, observers, resolver, Set.of(), 2, false);
    }

    static CacheBuilder forLegacyTesting(
            dev.vertique.cache.spi.CacheStore store,
            CacheConfig config,
            Set<CacheObserver> observers,
            Set<CacheIdentityResolver> resolvers) {
        Optional<CacheIdentityResolver> resolver =
                resolvers.size() == 1 ? Optional.of(resolvers.iterator().next()) : Optional.empty();
        return new CacheBuilder(CacheStoreResolver.fixed(store), config, observers, resolver, Set.of(), 1, true);
    }

    public <V> Definition<V> cache(String name, Class<V> valueType) {
        return new Definition<>(this, requireName(name), valueType, null, null, null, null);
    }

    public <V> Definition<V> cache(String name, TypeRef<V> valueType) {
        Objects.requireNonNull(valueType, "valueType");
        return new Definition<>(this, requireName(name), valueType.capturedType(), null, null, null, null);
    }

    Cache<Object, Object> annotation(MethodMetadata target, Cacheable annotation) {
        PreparedOperation prepared = preparedOperations.get(operationId(target));
        if (prepared != null && prepared.cache() != null) return prepared.cache();
        Type declaredType = valueType(target);
        CacheMode mode = effectiveMode(annotation.name(), annotation.mode());
        long ttl = effectiveTtl(annotation.name(), annotation.ttlSeconds());
        CacheIdentity annotationIdentity = annotation.identity();
        if (config.enabled()) stores.resolve(mode);
        register(
                annotation.name(),
                declaredType,
                mode,
                ttl,
                annotationIdentity,
                annotation.anonymous(),
                annotation.key());
        return new Cache<>(
                stores,
                config,
                observers,
                identityResolver,
                annotation.name(),
                declaredType,
                effectiveProfile(annotation.name()),
                mode,
                ttl,
                annotationIdentity,
                annotation.anonymous(),
                input -> legacyObservationOutcomes
                        ? CacheKeyRenderer.render(annotation.key(), target, (Object[]) input)
                        : CacheKeyRenderer.renderCanonical(
                                annotation.key(), target, (Object[]) input, CacheBuilder::scalar),
                target.returnType() != Future.class,
                legacyObservationOutcomes ? 1 : regionVersion,
                legacyObservationOutcomes);
    }

    Cache<Object, Object> eviction(MethodMetadata target, CacheEvict annotation) {
        PreparedOperation prepared = preparedOperations.get(operationId(target));
        if (prepared != null && !prepared.evictions().isEmpty())
            return prepared.evictions().getFirst().cache();
        CacheIdentity targetIdentity =
                target.findAnnotation(Cacheable.class).map(Cacheable::identity).orElse(CacheIdentity.NONE);
        return new Cache<>(
                stores,
                config,
                observers,
                identityResolver,
                annotation.name(),
                Object.class,
                effectiveProfile(annotation.name()),
                effectiveMode(annotation.name(), CacheMode.DEFAULT),
                effectiveTtl(annotation.name(), -1),
                targetIdentity,
                target.findAnnotation(Cacheable.class).map(Cacheable::anonymous).orElse(AnonymousCachePolicy.BYPASS),
                input -> legacyObservationOutcomes
                        ? CacheKeyRenderer.render(annotation.key(), target, (Object[]) input)
                        : CacheKeyRenderer.renderCanonical(
                                annotation.key(), target, (Object[]) input, CacheBuilder::scalar),
                false,
                legacyObservationOutcomes ? 1 : regionVersion,
                legacyObservationOutcomes);
    }

    List<PreparedEviction> evictions(MethodMetadata target) {
        PreparedOperation prepared = preparedOperations.get(operationId(target));
        if (prepared != null) return prepared.evictions();
        return target.findAnnotation(CacheEvict.class)
                .map(annotation -> List.of(new PreparedEviction(eviction(target, annotation), annotation.clear())))
                .orElse(List.of());
    }

    List<PreparedEviction> evictions(MethodMetadata target, CacheEvict[] declarations) {
        PreparedOperation prepared = preparedOperations.get(operationId(target));
        if (prepared != null) return prepared.evictions();
        List<PreparedEviction> result = new ArrayList<>();
        for (CacheEvict declaration : declarations) {
            result.add(new PreparedEviction(eviction(target, declaration), declaration.clear()));
        }
        return List.copyOf(result);
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
            validateType(valueType, new java.util.HashSet<>());
            CacheMode effectiveMode = owner.effectiveMode(name, mode);
            long effectiveTtl = owner.effectiveTtl(name, ttl == null ? -1 : wholeSeconds(ttl));
            CacheIdentity effectiveIdentity = identity == null ? CacheIdentity.EFFECTIVE_PRINCIPAL : identity;
            AnonymousCachePolicy effectiveAnonymous =
                    anonymousPolicy == null ? AnonymousCachePolicy.BYPASS : anonymousPolicy;
            if (owner.config.enabled()) owner.stores.resolve(effectiveMode);
            owner.register(name, valueType, effectiveMode, effectiveTtl, effectiveIdentity, effectiveAnonymous, "{0}");
            return new Cache<>(
                    owner.stores,
                    owner.config,
                    owner.observers,
                    owner.identityResolver,
                    name,
                    valueType,
                    owner.effectiveProfile(name),
                    effectiveMode,
                    effectiveTtl,
                    effectiveIdentity,
                    effectiveAnonymous,
                    input -> scalar(input),
                    false,
                    owner.regionVersion,
                    owner.legacyObservationOutcomes);
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
            validateType(valueType, new java.util.HashSet<>());
            Layout parsed = Layout.parse(layout, components.size());
            CacheMode effectiveMode = owner.effectiveMode(name, mode);
            long effectiveTtl = owner.effectiveTtl(name, ttl == null ? -1 : wholeSeconds(ttl));
            if (owner.config.enabled()) owner.stores.resolve(effectiveMode);
            owner.register(
                    name,
                    valueType,
                    effectiveMode,
                    effectiveTtl,
                    identity == null ? CacheIdentity.EFFECTIVE_PRINCIPAL : identity,
                    anonymousPolicy == null ? AnonymousCachePolicy.BYPASS : anonymousPolicy,
                    layout);
            return new Cache<>(
                    owner.stores,
                    owner.config,
                    owner.observers,
                    owner.identityResolver,
                    name,
                    valueType,
                    owner.effectiveProfile(name),
                    effectiveMode,
                    effectiveTtl,
                    identity == null ? CacheIdentity.EFFECTIVE_PRINCIPAL : identity,
                    anonymousPolicy == null ? AnonymousCachePolicy.BYPASS : anonymousPolicy,
                    input -> parsed.render(input, components),
                    false,
                    owner.regionVersion,
                    owner.legacyObservationOutcomes);
        }
    }

    private static String scalar(Object value) {
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
            if (functions != componentCount || functions == 0)
                throw new IllegalArgumentException("cache key component count mismatch");
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

    private static Type valueType(MethodMetadata target) {
        Type returnType = target.genericReturnType();
        if (returnType instanceof ParameterizedType parameterized && target.returnType() == Future.class) {
            return parameterized.getActualTypeArguments()[0];
        }
        return returnType;
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.length() > 128 || name.isBlank() || !name.matches("[A-Za-z0-9._~-]+")) {
            throw new IllegalArgumentException("cache name must be 1-128 ASCII cache segment characters");
        }
        return name;
    }

    private void validateGeneratedMetadata(Set<GeneratedCacheMetadata> metadata) {
        List<GeneratedCacheMetadata> ordered = metadata.stream()
                .sorted(java.util.Comparator.comparing(
                        item -> item.operationId().declaringBinaryName()
                                + "#" + item.operationId().methodName()
                                + item.operationId().erasedParameterTypeNames()))
                .toList();
        Set<GeneratedCacheMetadata.OperationId> operationIds = new java.util.HashSet<>();
        for (GeneratedCacheMetadata operation : ordered) {
            Objects.requireNonNull(operation, "generated cache metadata");
            if (!operationIds.add(operation.operationId())) {
                throw new IllegalStateException("duplicate generated cache operation id");
            }
            operation.cacheable().ifPresent(declaration -> {
                validateType(declaration.valueType(), new java.util.HashSet<>());
                effectiveMode(declaration.cacheName(), declaration.mode());
                effectiveTtl(declaration.cacheName(), declaration.ttlSeconds());
                register(
                        declaration.cacheName(),
                        declaration.valueType(),
                        effectiveMode(declaration.cacheName(), declaration.mode()),
                        effectiveTtl(declaration.cacheName(), declaration.ttlSeconds()),
                        declaration.identity(),
                        declaration.anonymous(),
                        declaration.selector().normalizedLayout());
            });
            for (GeneratedCacheMetadata.EvictionDeclaration eviction : operation.evictions()) {
                requireName(eviction.cacheName());
                eviction.selector()
                        .ifPresent(selector -> Layout.parse(
                                selector.normalizedLayout(),
                                selector.components().size()));
            }
        }
    }

    private Map<GeneratedCacheMetadata.OperationId, PreparedOperation> prepareGeneratedMetadata(
            Set<GeneratedCacheMetadata> metadata) {
        if (metadata.isEmpty()) return Map.of();
        Map<String, GeneratedDefinition> definitions = new HashMap<>();
        for (GeneratedCacheMetadata operation : metadata) {
            operation.cacheable().ifPresent(declaration -> {
                GeneratedDefinition candidate = new GeneratedDefinition(declaration, operation.synchronous());
                GeneratedDefinition existing = definitions.putIfAbsent(declaration.cacheName(), candidate);
                if (existing != null && !existing.compatibleWith(candidate)) {
                    throw new IllegalStateException(
                            "incompatible generated definitions for cache " + declaration.cacheName());
                }
                if (operation.synchronous()
                        && effectiveMode(declaration.cacheName(), declaration.mode()) == CacheMode.CLUSTERED) {
                    throw new IllegalStateException(
                            "synchronous cache method cannot use clustered mode: " + declaration.cacheName());
                }
            });
        }
        Map<GeneratedCacheMetadata.OperationId, PreparedOperation> result = new HashMap<>();
        for (GeneratedCacheMetadata operation : metadata) {
            Cache<Object, Object> cache = operation
                    .cacheable()
                    .map(declaration -> buildGeneratedCache(declaration, operation.synchronous()))
                    .orElse(null);
            List<PreparedEviction> evictions = new ArrayList<>();
            for (GeneratedCacheMetadata.EvictionDeclaration declaration : operation.evictions()) {
                GeneratedDefinition target = definitions.get(declaration.cacheName());
                if (target == null) {
                    throw new IllegalStateException(
                            "generated eviction targets unknown cache " + declaration.cacheName());
                }
                Cache<Object, Object> evictionCache =
                        buildGeneratedEvictionCache(target, declaration, operation.synchronous());
                evictions.add(new PreparedEviction(
                        evictionCache, declaration.selector().isEmpty()));
            }
            result.put(operation.operationId(), new PreparedOperation(cache, List.copyOf(evictions)));
        }
        return Collections.unmodifiableMap(result);
    }

    private Cache<Object, Object> buildGeneratedCache(
            GeneratedCacheMetadata.CacheableDeclaration declaration, boolean synchronous) {
        CacheMode mode = effectiveMode(declaration.cacheName(), declaration.mode());
        long ttl = effectiveTtl(declaration.cacheName(), declaration.ttlSeconds());
        return new Cache<>(
                stores,
                config,
                observers,
                identityResolver,
                declaration.cacheName(),
                declaration.valueType(),
                effectiveProfile(declaration.cacheName()),
                mode,
                ttl,
                declaration.identity(),
                declaration.anonymous(),
                selector(declaration.selector()),
                synchronous,
                regionVersion,
                legacyObservationOutcomes);
    }

    private Cache<Object, Object> buildGeneratedEvictionCache(
            GeneratedDefinition target, GeneratedCacheMetadata.EvictionDeclaration declaration, boolean synchronous) {
        if (declaration.selector().isPresent()
                && !selectorCompatible(
                        target.declaration().selector(), declaration.selector().orElseThrow())) {
            throw new IllegalStateException(
                    "generated eviction selector does not match cache " + declaration.cacheName());
        }
        CacheMode mode =
                effectiveMode(declaration.cacheName(), target.declaration().mode());
        long ttl = effectiveTtl(declaration.cacheName(), target.declaration().ttlSeconds());
        Function<Object, String> selector =
                declaration.selector().map(this::selector).orElse(input -> "clear");
        return new Cache<>(
                stores,
                config,
                observers,
                identityResolver,
                declaration.cacheName(),
                target.declaration().valueType(),
                effectiveProfile(declaration.cacheName()),
                mode,
                ttl,
                target.declaration().identity(),
                target.declaration().anonymous(),
                selector,
                synchronous,
                regionVersion,
                legacyObservationOutcomes);
    }

    private Function<Object, String> selector(GeneratedCacheMetadata.Selector generated) {
        Layout parsed = Layout.parse(
                generated.normalizedLayout(), generated.components().size());
        List<Function<Object[], ?>> functions = new ArrayList<>();
        for (GeneratedCacheMetadata.SelectorComponent component : generated.components()) {
            functions.add(component.accessor()::select);
        }
        return input -> parsed.render((Object[]) input, functions);
    }

    private static boolean selectorCompatible(
            GeneratedCacheMetadata.Selector cacheSelector, GeneratedCacheMetadata.Selector evictionSelector) {
        if (!cacheSelector.normalizedLayout().equals(evictionSelector.normalizedLayout())
                || cacheSelector.components().size()
                        != evictionSelector.components().size()) return false;
        for (int i = 0; i < cacheSelector.components().size(); i++) {
            GeneratedCacheMetadata.SelectorComponent cacheComponent =
                    cacheSelector.components().get(i);
            GeneratedCacheMetadata.SelectorComponent evictionComponent =
                    evictionSelector.components().get(i);
            if (!cacheComponent.accessorPath().equals(evictionComponent.accessorPath())
                    || !boxed(cacheComponent.declaredType()).equals(boxed(evictionComponent.declaredType()))) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static GeneratedCacheMetadata.OperationId operationId(MethodMetadata target) {
        return new GeneratedCacheMetadata.OperationId(
                target.declaringType().getName(),
                target.name(),
                java.util.Arrays.stream(target.parameterTypes())
                        .map(Class::getName)
                        .toList());
    }

    record PreparedOperation(Cache<Object, Object> cache, List<PreparedEviction> evictions) {}

    record PreparedEviction(Cache<Object, Object> cache, boolean clear) {}

    private record GeneratedDefinition(GeneratedCacheMetadata.CacheableDeclaration declaration, boolean synchronous) {
        boolean compatibleWith(GeneratedDefinition other) {
            return declaration.valueType().equals(other.declaration.valueType())
                    && declaration.mode() == other.declaration.mode()
                    && declaration.ttlSeconds() == other.declaration.ttlSeconds()
                    && declaration.identity() == other.declaration.identity()
                    && declaration.anonymous() == other.declaration.anonymous()
                    && selectorCompatible(declaration.selector(), other.declaration.selector())
                    && synchronous == other.synchronous;
        }
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
