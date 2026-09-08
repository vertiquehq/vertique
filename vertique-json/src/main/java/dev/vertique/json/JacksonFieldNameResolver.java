// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.util.NameTransformer;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputFieldNameResolver.PromotedField;
import jakarta.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * <p><strong>Two Java fields Jackson merged.</strong> For
 * {@code class Dto { String a; @JsonProperty("a") String b; }} Jackson produces a single
 * {@link BeanPropertyDefinition} that publishes the implicit name of {@code a} while binding writes
 * the field {@code b}; only one property reaches the collision check above, so nothing there fires.
 * Projecting the published name would select {@code a}'s declared policies for a key Jackson writes
 * into {@code b}, and {@code a} is never bound at all. Such a type fails startup naming both Java
 * properties.
 *
 * <p><strong>Case-insensitive mappers.</strong> A mapper with
 * {@code MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES} enabled binds {@code SECRET} to a
 * property named {@code secret}, so an exact-match projection would resolve the differently-cased
 * key to no property and skip its declared policies. Against such a mapper the projection is keyed
 * by the {@link java.util.Locale#ROOT} case-folded wire name and folds its lookups the same way, and
 * the identity short circuit below is never taken.
 *
 * <p><strong>Caching and the identity short circuit.</strong> One instance is created per body mapper
 * at route or endpoint registration and caches its per-type projection in a {@link ClassValue}, so
 * entries are collected with the classloader that owns the DTO rather than pinned in a static
 * {@code Class}-keyed map. This type composes and caches the projection of <em>one</em> class at a
 * time: {@link #precompute(Class)} is the {@link InputFieldNameResolver} SPI hook the
 * input-processing engine calls at registration, once for every owner type it may later pass to
 * {@link #logicalName}. Deciding <em>which</em> types those are is the engine's job, not this
 * module's — this resolver never walks a type graph. Because the engine hands over its own owner
 * set at registration, {@link #logicalName} neither introspects nor raises anything on the request
 * path. When a type's computed projection maps every wire name onto itself — the overwhelmingly
 * common DTO — the entry records that and {@link #logicalName} returns the wire name directly,
 * skipping the per-field lookup entirely. The flag follows the <em>computed</em> projection, never
 * an inference about the mapper's configuration.
 *
 * <p>Instances are immutable and safe for concurrent use from several event-loop threads. A mapper
 * selected for a mounted route is treated as immutable after router construction: an application that
 * mutates a process-global mapper afterwards must rebuild the router.
 */
public final class JacksonFieldNameResolver implements InputFieldNameResolver {

    /**
     * One type's wire &rarr; Java projection, whether it is the identity map, and the keys that are
     * bound into a field of another type entirely.
     *
     * @param identity whether every wire name maps onto itself, letting lookups short-circuit
     * @param names    the wire &rarr; Java projection
     * @param promoted the {@code @JsonUnwrapped} members' keys, by the logical name {@code names}
     *                 resolves them to
     */
    private record Projection(boolean identity, Map<String, String> names, Map<String, PromotedField> promoted) {}

    private final ObjectMapper mapper;
    private final DeserializationConfig config;

    /**
     * Whether the body mapper matches wire keys case-insensitively. When it does, the projection is
     * keyed by the case-folded wire name and {@link #logicalName} folds its argument before the
     * lookup, so a differently-cased key resolves to the property Jackson binds it to rather than to
     * no property at all.
     */
    private final boolean foldsCase;

    private final ClassValue<Projection> projections = new ClassValue<>() {
        @Override
        protected Projection computeValue(Class<?> type) {
            return computeProjection(type);
        }
    };

    private JacksonFieldNameResolver(ObjectMapper mapper) {
        this.mapper = mapper;
        this.config = mapper.getDeserializationConfig();
        this.foldsCase = config.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
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
     * <p>A {@code null} argument means the boundary resolved no mapper of its own — the body is
     * materialized by the process JSON codec, so {@link VertiqueJson#mapper()} is the mapper whose
     * naming decides which declared policies apply. It is read here, at composition time, and the
     * caller composes boundary resolvers during or after the {@code CONFIGURE} startup phase.
     *
     * @param resolvedBodyMapper the boundary's resolved profile mapper, or {@code null} when the
     *                           boundary binds through the process codec
     * @return the resolver for that boundary; never {@code null}
     */
    public static JacksonFieldNameResolver forRoute(@Nullable ObjectMapper resolvedBodyMapper) {
        return forMapper(resolvedBodyMapper != null ? resolvedBodyMapper : VertiqueJson.mapper());
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
     * <p>This is the {@link InputFieldNameResolver} SPI hook the input-processing engine calls at
     * registration — once for every owner type it may later pass to {@link #logicalName} while
     * processing a declared body or message type. That placement is the contract, not an
     * optimization: {@link InputFieldNameResolver} publishes that an implementation never throws and
     * should serve every call from a precomputed projection, and composing a projection means running
     * a full Jackson bean introspection which can also fail. Left to the first request, that work —
     * and any failure it raises — would land on an event-loop thread as a per-request error, and
     * would repeat on every subsequent request, because a {@link ClassValue} does not memoise a
     * {@code computeValue} that threw.
     *
     * <p>Which classes make up that owner set is decided by the engine, which knows what it descends;
     * this resolver composes exactly the class it is handed and walks no type graph of its own.
     * Calling this for a type whose projection is already composed is a no-op.
     *
     * @param ownerType the owner type to compose the projection for; must not be {@code null}
     * @throws ConfigurationException if {@code ownerType}'s projection cannot be composed — two
     *                                properties claiming one primary wire name, two properties
     *                                claiming one alias, or two Java fields Jackson merged into one
     *                                property
     */
    @Override
    public void precompute(Class<?> ownerType) {
        projections.get(ownerType);
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
        String logicalName = projection.names().get(foldsCase ? fold(wireName) : wireName);
        return logicalName != null ? logicalName : wireName;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Served from the same per-type projection {@link #logicalName} reads, composed at
     * {@link #precompute}, so the request path never introspects. Empty for the overwhelmingly common
     * type with no {@code @JsonUnwrapped} member.
     *
     * @param ownerType the type the intermediate is keyed against; must not be {@code null}
     * @return the promoted keys of {@code ownerType}, keyed by logical name; never {@code null}
     */
    @Override
    public Map<String, PromotedField> promotedFields(Class<?> ownerType) {
        return projections.get(ownerType).promoted();
    }

    /**
     * Case-folds a wire name for a mapper that matches keys case-insensitively.
     *
     * @param wireName the wire name to fold
     * @return the folded name, in {@link Locale#ROOT} so the folding never varies with the default
     *     locale
     */
    private static String fold(String wireName) {
        return wireName.toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the projection key for a wire name: the name itself, or its case-folded form when the
     * body mapper matches keys case-insensitively.
     *
     * @param wireName the wire name a property or alias publishes
     * @return the key this projection stores that name under
     */
    private String key(String wireName) {
        return foldsCase ? fold(wireName) : wireName;
    }

    /**
     * Introspects one type and builds its wire &rarr; Java projection.
     *
     * @param type the type to introspect
     * @return the projection for {@code type}; never {@code null}
     * @throws ConfigurationException if two properties claim the same primary wire name, if two
     *                                different properties claim the same unclaimed alias, or if
     *                                Jackson merged two Java fields into one property
     */
    private Projection computeProjection(Class<?> type) {
        BeanDescription description = config.introspect(mapper.getTypeFactory().constructType(type));
        List<BeanPropertyDefinition> properties = description.findProperties();

        // Pass 1 — primary names, which run before any alias and so claim their key first. A second
        // primary on the same key is a configuration error unless it names the same Java property.
        Map<String, String> names = new LinkedHashMap<>(Math.max(4, properties.size() * 2));
        for (BeanPropertyDefinition property : properties) {
            String wireName = key(property.getName());
            String javaName = property.getInternalName();
            // A property Jackson MERGED across two Java fields publishes the implicit name of one and
            // writes the field of the other: for `class Dto { String a; @JsonProperty("a") String b; }`
            // the single definition reports internal name `a` while binding writes field `b`, and the
            // collision check below never sees two properties to compare. Projecting the published
            // name would select `a`'s declared policies for a key Jackson writes into `b`, and `a`
            // itself is never bound at all — so the configuration is refused rather than guessed at.
            if (property.hasField() && !property.getField().getName().equals(javaName)) {
                throw new ConfigurationException("Type " + type.getName() + " merges the Java properties '"
                        + javaName + "' and '" + property.getField().getName() + "' into the single wire property"
                        + " name '" + property.getName() + "'. Jackson binds that key into '"
                        + property.getField().getName() + "' while it publishes the name of '" + javaName
                        + "', so the declared input policies applied to the key would not be those of the field"
                        + " written, and '" + javaName + "' is never bound at all; give one of them a distinct"
                        + " @JsonProperty name.");
            }
            String previousOwner = names.putIfAbsent(wireName, javaName);
            if (previousOwner != null && !previousOwner.equals(javaName)) {
                throw new ConfigurationException("Type " + type.getName() + " publishes the wire property name '"
                        + property.getName() + "' for both Java properties '" + previousOwner + "' and '" + javaName
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
                String aliasName = key(alias.getSimpleName());
                if (primaryClaimed.contains(aliasName)) {
                    continue;
                }
                String previousOwner = aliasOwners.putIfAbsent(aliasName, javaName);
                if (previousOwner != null && !previousOwner.equals(javaName)) {
                    throw new ConfigurationException("Type " + type.getName() + " lets both Java properties '"
                            + previousOwner + "' and '" + javaName + "' claim the alias '" + alias.getSimpleName()
                            + "'. Jackson resolves that collision in an unspecified order, so the declared "
                            + "input policies applied to the key would not reliably be those of the property "
                            + "Jackson binds it to; give one of them a distinct @JsonAlias.");
                }
                names.putIfAbsent(aliasName, javaName);
            }
        }

        // Pass 3 — members the codec PROMOTES into this object. Jackson's declaration view lists an
        // @JsonUnwrapped property itself, never its expanded members, because unwrapping is applied a
        // layer later when a codec is built from that view. So the inner type's fields arrive as keys
        // of THIS object while their declared policies live on the inner type, and the engine — which
        // keys metadata on the declaring type — would find nothing. findUnwrappingNameTransformer is
        // the introspection-layer hook that reports the member and hands back the NameTransformer
        // carrying the annotation's prefix and suffix.
        Map<String, PromotedField> promoted = new LinkedHashMap<>();
        collectPromoted(type, properties, NameTransformer.NOP, names, promoted, new HashSet<>());

        // A case-folding mapper never short-circuits: the wire key it binds may differ in case from
        // the Java name, so the folded lookup has to run even when every name maps onto itself. Nor
        // does a type with promoted keys, whose logical names must be resolvable for the engine to
        // find their declaring type.
        boolean identity = !foldsCase
                && promoted.isEmpty()
                && names.entrySet().stream().allMatch(entry -> entry.getKey().equals(entry.getValue()));
        return new Projection(identity, identity ? Map.of() : Map.copyOf(names), Map.copyOf(promoted));
    }

    /**
     * Walks {@code type}'s unwrapped members, adding each promoted key to {@code names} and
     * {@code promoted}. Recurses through nested unwrapping with the transformers chained, which is
     * what Jackson itself does to the names it binds.
     *
     * @param type       the type being introspected at this level
     * @param properties {@code type}'s declaration-view properties
     * @param outer      the transformer accumulated from the levels above; {@link NameTransformer#NOP}
     *                   at the top
     * @param names      the projection being built, which each promoted key is added to
     * @param promoted   the promoted-key map being built
     * @param seen       types already descended at this level, so a cyclic unwrapping terminates
     * @throws ConfigurationException if two promoted keys resolve to one logical name
     */
    private void collectPromoted(
            Class<?> type,
            List<BeanPropertyDefinition> properties,
            NameTransformer outer,
            Map<String, String> names,
            Map<String, PromotedField> promoted,
            Set<Class<?>> seen) {
        if (!seen.add(type)) {
            return;
        }
        AnnotationIntrospector introspector = config.getAnnotationIntrospector();
        for (BeanPropertyDefinition property : properties) {
            AnnotatedMember member = property.getPrimaryMember();
            if (member == null) {
                continue;
            }
            NameTransformer unwrapper = introspector.findUnwrappingNameTransformer(member);
            if (unwrapper == null) {
                continue;
            }
            NameTransformer chained =
                    outer == NameTransformer.NOP ? unwrapper : NameTransformer.chainedTransformer(outer, unwrapper);
            JavaType innerType = member.getType();
            Class<?> innerClass = innerType.getRawClass();
            BeanDescription innerDescription = config.introspect(innerType);
            List<BeanPropertyDefinition> innerProperties = innerDescription.findProperties();

            for (BeanPropertyDefinition innerProperty : innerProperties) {
                AnnotatedMember innerMember = innerProperty.getPrimaryMember();
                if (innerMember != null && introspector.findUnwrappingNameTransformer(innerMember) != null) {
                    // Handled by the recursion below, which chains this level's transformer onto it.
                    continue;
                }
                String wireName = key(chained.transform(innerProperty.getName()));
                String javaName = innerProperty.getInternalName();
                if (names.containsKey(wireName) && !promoted.containsKey(javaName)) {
                    // This object's own property claims the key. Jackson binds that property, so the
                    // projection must agree with it rather than steal the key for the inner type.
                    continue;
                }
                PromotedField previous = promoted.putIfAbsent(javaName, new PromotedField(innerClass, javaName));
                if (previous != null && !previous.declaringType().equals(innerClass)) {
                    throw new ConfigurationException("Type " + type.getName() + " promotes the key '"
                            + wireName + "' from both '"
                            + previous.declaringType().getName() + "' and '"
                            + innerClass.getName() + "' onto the single logical name '" + javaName
                            + "'. The declared input policies of the two cannot be told apart, so give one"
                            + " of the unwrapped members a @JsonUnwrapped prefix or suffix.");
                }
                names.putIfAbsent(wireName, javaName);
            }

            collectPromoted(innerClass, innerProperties, chained, names, promoted, seen);
        }
        seen.remove(type);
    }
}
