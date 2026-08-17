// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import io.vertx.core.json.jackson.DatabindCodec;
import jakarta.annotation.Nullable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Jackson-backed {@link InputFieldNameResolver}: projects the <strong>wire</strong> property names a
 * request or message body is keyed by onto the <strong>Java</strong> property names both
 * input-processing execution paths key their metadata on.
 *
 * <p>The projection is read from the mapper that actually materializes the body, through
 * {@link DeserializationConfig#introspect(com.fasterxml.jackson.databind.JavaType)}: every
 * {@link BeanPropertyDefinition}'s {@link BeanPropertyDefinition#getName() name} (wire) maps to its
 * {@link BeanPropertyDefinition#getInternalName() internal name} (Java), and every
 * {@link BeanPropertyDefinition#findAliases() alias} is added on top. Asking the mapper is the only
 * correct source: a name can be changed by {@code @JsonProperty}, by a property naming strategy, by
 * {@code @JsonNaming}, by a mix-in, or by an {@code AnnotationIntrospector} a registered module
 * installed — "nothing in this configuration renames anything" is not an enumerable set.
 *
 * <p>This type lives here rather than in a transport module because the projection is a
 * <em>mapper-derived</em> concern: it is a pure function of the {@link ObjectMapper} that binds the
 * body, and this module already owns mapper profiles and {@code jackson-databind}. Every transport
 * that materializes bodies through a Jackson mapper — REST, WebSocket — shares this one
 * implementation instead of duplicating or re-deriving it.
 *
 * <p><strong>Precedence.</strong> A primary name always claims its key; an alias populates a key only
 * when no primary name claims it. That is what Jackson itself binds: for
 * {@code class Dto { String alpha; @JsonAlias("alpha") String beta; }} a body {@code {"alpha":"V"}}
 * sets {@code alpha} and leaves {@code beta} null. Refusing such a configuration would reject an
 * application Jackson runs correctly, so it is accepted here too. Two colliding <em>primary</em> names
 * are a configuration Jackson rejects itself and fail startup here. Two <em>different</em> properties
 * claiming one alias that no primary name claims likewise fails startup: Jackson resolves that
 * collision in hash order while a projection built from {@code findProperties()} would resolve it in
 * declaration order, so the property whose policies are applied and the property Jackson binds could
 * differ non-deterministically.
 *
 * <p><strong>Caching and the identity short circuit.</strong> One instance is created per body mapper
 * at route or endpoint registration and caches its per-type projection in a {@link ClassValue}, so
 * entries are collected with the classloader that owns the DTO rather than pinned in a static
 * {@code Class}-keyed map. Each boundary composes the projections for every body or message type it
 * knows — and, through {@link #precomputeGraph}, for every type reachable from one by a declared
 * property — at that same registration, so {@link #logicalName} neither introspects nor raises
 * anything on the request path. When a type's computed projection maps every
 * wire name onto itself — the
 * overwhelmingly common DTO — the entry records that and {@link #logicalName} returns the wire name
 * directly, skipping the per-field lookup entirely. The flag follows the <em>computed</em>
 * projection, never an inference about the mapper's configuration.
 *
 * <p>Instances are immutable and safe for concurrent use from several event-loop threads. A mapper
 * selected for a mounted route is treated as immutable after router construction: an application that
 * mutates a process-global mapper afterwards must rebuild the router.
 */
public final class JacksonFieldNameResolver implements InputFieldNameResolver {

    /** One type's wire &rarr; Java projection, plus whether it is the identity map. */
    private record Projection(boolean identity, Map<String, String> names) {}

    private final ObjectMapper mapper;
    private final DeserializationConfig config;
    private final ClassValue<Projection> projections = new ClassValue<>() {
        @Override
        protected Projection computeValue(Class<?> type) {
            return computeProjection(type);
        }
    };

    private JacksonFieldNameResolver(ObjectMapper mapper) {
        this.mapper = mapper;
        this.config = mapper.getDeserializationConfig();
    }

    /**
     * Creates a resolver projecting against the given mapper.
     *
     * @param mapper the mapper that materializes the bodies this resolver serves; must not be
     *               {@code null}
     * @return a resolver for {@code mapper}; never {@code null}
     */
    public static JacksonFieldNameResolver forMapper(ObjectMapper mapper) {
        return new JacksonFieldNameResolver(mapper);
    }

    /**
     * Creates the resolver for a boundary from its resolved body-profile mapper.
     *
     * <p>A {@code null} argument is the reserved {@code vertx} profile — the body is materialized by
     * {@link DatabindCodec#mapper()}, so that is the mapper whose naming decides which declared
     * policies apply.
     *
     * @param resolvedBodyMapper the boundary's resolved profile mapper, or {@code null} for the
     *                           {@code vertx} default
     * @return the resolver for that boundary; never {@code null}
     */
    public static JacksonFieldNameResolver forRoute(@Nullable ObjectMapper resolvedBodyMapper) {
        return forMapper(resolvedBodyMapper != null ? resolvedBodyMapper : DatabindCodec.mapper());
    }

    /**
     * Returns the mapper this resolver projects against.
     *
     * @return the body mapper; never {@code null}
     */
    ObjectMapper mapper() {
        return mapper;
    }

    /**
     * Composes {@code ownerType}'s projection now, so {@link #logicalName} serves it from cache.
     *
     * <p>Every boundary calls this at registration for each body or message type it already knows.
     * That placement is the contract, not an optimization: {@link InputFieldNameResolver} publishes
     * that an implementation never throws and should serve every call from a precomputed projection,
     * and composing a projection means running a full Jackson bean introspection which can also fail.
     * Left to the first request, that work — and any failure it raises — would land on an event-loop
     * thread as a per-request error, and would repeat on every subsequent request, because a
     * {@link ClassValue} does not memoise a {@code computeValue} that threw.
     *
     * <p>Calling this for a type whose projection is already composed is a no-op.
     *
     * @param ownerType the body or message type to compose the projection for; must not be {@code null}
     * @throws ConfigurationException if {@code ownerType}'s projection cannot be composed — two
     *                                properties claiming one primary wire name, or two properties
     *                                claiming one alias
     */
    public void precompute(Class<?> ownerType) {
        projections.get(ownerType);
    }

    /**
     * Composes the projection for {@code declaredType} and for every type reachable from it through
     * Jackson-visible properties, so no type the engine can descend into is left to introspect lazily
     * on the request or message path.
     *
     * <p>This is the shared warm-up walk every Jackson-bound boundary uses: it unwraps arrays and
     * parameterized shapes (a {@code List<Dto>} body warms {@code Dto}, a {@code Dto[]} message warms
     * {@code Dto}), then follows each visited type's declared property types transitively, including
     * collection element types. A visited set makes a cyclic type graph terminate, and a shape
     * carrying no statically known property set — a wildcard, a type variable, a primitive, an enum,
     * a platform type such as {@link String}, or a map's key and value types — is skipped rather than
     * introspected. The map rule applies to the declared shape itself as well as to a property: a
     * {@code Map<String, Dto>} body warms neither {@code String} nor {@code Dto}. Both walks decide
     * "is this a map?" with the engine's own {@code Map.class.isAssignableFrom} test (see
     * {@link #isMapKeyed}), so the walk follows exactly the links the engine descends, no more.
     *
     * <p>Why transitive rather than only the declared body or message type: the engine resolves the
     * projection for the <em>owner of each nested fragment</em>, so a nested DTO's projection is
     * consulted on the request path exactly like the root's. Warming only the root would leave that
     * first consultation to run a bean introspection on an event-loop thread — and, because a
     * {@link ClassValue} does not memoise a {@code computeValue} that threw, would re-run and re-throw
     * it for every subsequent request.
     *
     * @param declaredType the declared body or message type to walk, or {@code null} for nothing
     * @throws ConfigurationException if any reachable type's projection cannot be composed — two
     *                                properties claiming one primary wire name, or two properties
     *                                claiming one alias
     */
    public void precomputeGraph(@Nullable Type declaredType) {
        warmDeclaredType(declaredType, new HashSet<>());
    }

    /**
     * Walks one reflective type shape, unwrapping arrays and parameterized types down to the classes
     * that carry a property set.
     *
     * <p><strong>A map shape's arguments are not walked</strong>, matching
     * {@link #warmPropertyType}: a {@code Map<String, Dto>} body is schema-free exactly like a
     * map-typed property, so neither its key nor its value type is ever consulted as a projection
     * owner and warming one would let an ambiguity the request path can never reach fail
     * registration. Both walks read "map shape" from {@link #isMapKeyed}, so a module-contributed
     * map-like type is walked here as the ordinary parameterized shape the engine descends.
     *
     * @param declaredType the shape to walk, or {@code null}
     * @param visited      the classes already warmed on this walk
     */
    private void warmDeclaredType(@Nullable Type declaredType, Set<Class<?>> visited) {
        if (declaredType instanceof Class<?> rawClass) {
            warmClass(rawClass, visited);
        } else if (declaredType instanceof ParameterizedType parameterized) {
            Type rawType = parameterized.getRawType();
            warmDeclaredType(rawType, visited);
            if (rawType instanceof Class<?> rawClass && isMapKeyed(rawClass)) {
                return;
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                warmDeclaredType(argument, visited);
            }
        } else if (declaredType instanceof GenericArrayType genericArray) {
            warmDeclaredType(genericArray.getGenericComponentType(), visited);
        }
        // A wildcard or type variable carries no statically known property set — nothing to project.
    }

    /**
     * Returns whether a raw class is the arbitrarily-keyed shape whose key and value types neither
     * warm-up walk descends.
     *
     * <p>This is the engine's own test, character for character: {@code InputPolicyMetadataResolver}
     * excludes a field type with {@code !Map.class.isAssignableFrom(type)} in
     * {@code isDescendableObject}, and {@code TypeClassifier} excludes an element type the same way.
     * Keying both walks on it is what makes the parity claim true — a type a registered module
     * classifies as <em>map-like</em> without it implementing {@link Map} (what Jackson's
     * {@code MapLikeType} exists for; Scala, Guava and Kotlin module registrations produce them) is
     * descended by the engine as a plain object, so both walks must warm it as one rather than treat
     * it as a schema-free map. Asking Jackson's {@code TypeFactory} instead would answer a different
     * question than the engine asks, and the two would disagree on exactly those types.
     *
     * @param rawClass the raw class to test
     * @return {@code true} when the shape is keyed by arbitrary map keys
     */
    private static boolean isMapKeyed(Class<?> rawClass) {
        return Map.class.isAssignableFrom(rawClass);
    }

    /**
     * Returns whether a raw class is one the engine iterates element-wise rather than descending into
     * itself — a {@link java.util.Collection} or an array. {@code InputPolicyMetadataResolver} routes
     * exactly these two through its element-type branch, so for them the element schema is warmed and
     * the container class is not; every other shape is a plain descendable object whose own class is
     * warmed. Jackson's {@code JavaType.isContainerType()} is deliberately not used here: it also
     * answers {@code true} for a map-like type the engine descends as a plain object.
     *
     * @param rawClass the raw class to test
     * @return {@code true} when the shape carries an element schema instead of its own property set
     */
    private static boolean isElementWise(Class<?> rawClass) {
        return Collection.class.isAssignableFrom(rawClass) || rawClass.isArray();
    }

    /**
     * Warms one class and every type its Jackson-visible properties expose, at most once per class.
     *
     * @param rawClass the class to warm
     * @param visited  the classes already warmed on this walk
     */
    private void warmClass(Class<?> rawClass, Set<Class<?>> visited) {
        if (rawClass.isArray()) {
            warmClass(rawClass.getComponentType(), visited);
            return;
        }
        if (!carriesProjectableProperties(rawClass) || !visited.add(rawClass)) {
            return;
        }
        precompute(rawClass);
        // The property walk re-reads the same BeanDescription the projection was composed from;
        // Jackson serves it from its own type/description caches.
        BeanDescription description = config.introspect(mapper.getTypeFactory().constructType(rawClass));
        for (BeanPropertyDefinition property : description.findProperties()) {
            warmPropertyType(property.getPrimaryType(), visited);
        }
    }

    /**
     * Warms one property's declared type, descending through array and collection shapes to the
     * element types the engine walks element-wise.
     *
     * <p><strong>A map shape is not descended.</strong> The engine treats a map-typed field as
     * schema-free — it carries no statically known property set, so its fragment's policies are keyed
     * against the map type itself and neither the key nor the value type is ever consulted as a
     * projection owner. Warming them anyway would let an ambiguity the request path can never reach
     * fail registration, which is an availability change with no behavioural payoff. "Map shape"
     * means {@link #isMapKeyed} — the engine's own test — so a type a registered module classifies as
     * map-like without it implementing {@link Map} is warmed here as the plain descendable object the
     * engine descends, not skipped and not descended over its module-declared content type.
     *
     * @param propertyType the property's resolved Jackson type, or {@code null}
     * @param visited      the classes already warmed on this walk
     */
    private void warmPropertyType(@Nullable JavaType propertyType, Set<Class<?>> visited) {
        if (propertyType == null) {
            return;
        }
        Class<?> rawClass = propertyType.getRawClass();
        if (isMapKeyed(rawClass)) {
            return;
        }
        if (isElementWise(rawClass)) {
            // The engine walks a collection or array element-wise, so the element schema is what a
            // fragment's policies are keyed against — never the container class itself.
            warmPropertyType(propertyType.getContentType(), visited);
        } else {
            warmClass(rawClass, visited);
        }
        // Type arguments of any other generic shape are still walked, so an Optional<Dto> — which the
        // engine treats as transparent — reaches Dto.
        for (int i = 0; i < propertyType.containedTypeCount(); i++) {
            warmPropertyType(propertyType.containedType(i), visited);
        }
    }

    /**
     * Returns whether a class can carry a wire &rarr; Java projection worth composing. Primitives,
     * enums, and platform types have no application-declared property set the engine keys policies
     * against, so introspecting them would cost a full bean introspection for an empty answer.
     *
     * @param rawClass the class to test
     * @return {@code true} when the class is an application type worth introspecting
     */
    private static boolean carriesProjectableProperties(Class<?> rawClass) {
        if (rawClass.isPrimitive() || rawClass.isEnum()) {
            return false;
        }
        String name = rawClass.getName();
        return !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("jakarta.");
    }

    /**
     * Returns whether the computed projection for {@code ownerType} is the identity map, in which case
     * {@link #logicalName} short-circuits.
     *
     * @param ownerType the type whose projection to test; must not be {@code null}
     * @return {@code true} when every wire name of {@code ownerType} maps onto itself
     */
    boolean isIdentityProjection(Class<?> ownerType) {
        return projections.get(ownerType).identity();
    }

    @Override
    public String logicalName(Class<?> ownerType, String wireName) {
        Projection projection = projections.get(ownerType);
        if (projection.identity()) {
            return wireName;
        }
        String logicalName = projection.names().get(wireName);
        return logicalName != null ? logicalName : wireName;
    }

    /**
     * Introspects one type and builds its wire &rarr; Java projection.
     *
     * @param type the type to introspect
     * @return the projection for {@code type}; never {@code null}
     * @throws ConfigurationException if two properties claim the same primary wire name, or two
     *                                different properties claim the same unclaimed alias
     */
    private Projection computeProjection(Class<?> type) {
        BeanDescription description = config.introspect(mapper.getTypeFactory().constructType(type));
        List<BeanPropertyDefinition> properties = description.findProperties();

        // Pass 1 — primary names, which run before any alias and so claim their key first. A second
        // primary on the same key is a configuration error unless it names the same Java property.
        Map<String, String> names = new LinkedHashMap<>(Math.max(4, properties.size() * 2));
        for (BeanPropertyDefinition property : properties) {
            String wireName = property.getName();
            String javaName = property.getInternalName();
            String previousOwner = names.putIfAbsent(wireName, javaName);
            if (previousOwner != null && !previousOwner.equals(javaName)) {
                throw new ConfigurationException("Type " + type.getName() + " publishes the wire property name '"
                        + wireName + "' for both Java properties '" + previousOwner + "' and '" + javaName
                        + "'. The declared input policies of the two cannot be told apart on the wire; give one of "
                        + "them a distinct @JsonProperty name.");
            }
        }

        // Pass 2 — aliases, which fill only the keys no primary name claimed. Jackson binds the primary
        // on such a collision (it does not reject the configuration), so the projection must agree with
        // it rather than refuse to boot; those alias entries are skipped here, and skipping them also
        // keeps them out of the duplicate check below — a key the primary owns is never contested.
        //
        // Two DIFFERENT properties claiming the same remaining key is a configuration error. Resolving
        // it in findProperties() declaration order would not agree with Jackson, which resolves the same
        // collision in hash order (BeanDeserializerBuilder._collectAliases builds a HashMap and
        // BeanPropertyMap._buildAliasMapping iterates its entrySet), so when the two properties carry
        // different policies, which one is policy-selected and which one Jackson binds can differ
        // non-deterministically and untestably. One property repeating its own alias is not a collision.
        Set<String> primaryClaimed = Set.copyOf(names.keySet());
        Map<String, String> aliasOwners = new HashMap<>();
        for (BeanPropertyDefinition property : properties) {
            String javaName = property.getInternalName();
            for (PropertyName alias : property.findAliases()) {
                String aliasName = alias.getSimpleName();
                if (primaryClaimed.contains(aliasName)) {
                    continue;
                }
                String previousOwner = aliasOwners.putIfAbsent(aliasName, javaName);
                if (previousOwner != null && !previousOwner.equals(javaName)) {
                    throw new ConfigurationException("Type " + type.getName() + " lets both Java properties '"
                            + previousOwner + "' and '" + javaName + "' claim the alias '" + aliasName
                            + "'. Jackson resolves that collision in an unspecified order, so the declared "
                            + "input policies applied to the key would not reliably be those of the property "
                            + "Jackson binds it to; give one of them a distinct @JsonAlias.");
                }
                names.putIfAbsent(aliasName, javaName);
            }
        }

        boolean identity =
                names.entrySet().stream().allMatch(entry -> entry.getKey().equals(entry.getValue()));
        return new Projection(identity, identity ? Map.of() : Map.copyOf(names));
    }
}
