// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.input.processing.InputFieldNameResolver;
import io.vertx.core.json.jackson.DatabindCodec;
import jakarta.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jackson-backed {@link InputFieldNameResolver}: projects the <strong>wire</strong> property names a
 * request body is keyed by onto the <strong>Java</strong> property names both input-processing
 * execution paths key their metadata on.
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
 * <p><strong>Precedence.</strong> A primary name always claims its key; an alias populates a key only
 * when no primary name claims it. That is what Jackson itself binds: for
 * {@code class Dto { String alpha; @JsonAlias("alpha") String beta; }} a body {@code {"alpha":"V"}}
 * sets {@code alpha} and leaves {@code beta} null. Refusing such a configuration would reject an
 * application Jackson runs correctly, so it is accepted here too. Two colliding <em>primary</em> names
 * are a configuration Jackson rejects itself and fail startup here.
 *
 * <p><strong>Caching and the identity short circuit.</strong> One instance is created per body mapper
 * at route registration and caches its per-type projection in a {@link ClassValue}, so entries are
 * collected with the classloader that owns the DTO rather than pinned in a static {@code Class}-keyed
 * map. When a type's computed projection maps every wire name onto itself — the overwhelmingly common
 * DTO — the entry records that and {@link #logicalName} returns the wire name directly, skipping the
 * per-field lookup entirely. The flag follows the <em>computed</em> projection, never an inference
 * about the mapper's configuration.
 *
 * <p>Instances are immutable and safe for concurrent use from several event-loop threads. A mapper
 * selected for a mounted route is treated as immutable after router construction: an application that
 * mutates a process-global mapper afterwards must rebuild the router.
 */
final class JacksonFieldNameResolver implements InputFieldNameResolver {

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
    static JacksonFieldNameResolver forMapper(ObjectMapper mapper) {
        return new JacksonFieldNameResolver(mapper);
    }

    /**
     * Creates the resolver for a route from its resolved request-body profile mapper.
     *
     * <p>A {@code null} argument is the reserved {@code vertx} profile — the route's body is
     * materialized by {@link DatabindCodec#mapper()}, so that is the mapper whose naming decides which
     * declared policies apply.
     *
     * @param resolvedBodyMapper the route's resolved profile mapper, or {@code null} for the
     *                           {@code vertx} default
     * @return the resolver for that route; never {@code null}
     */
    static JacksonFieldNameResolver forRoute(@Nullable ObjectMapper resolvedBodyMapper) {
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
     * @throws ConfigurationException if two properties claim the same primary wire name
     */
    private Projection computeProjection(Class<?> type) {
        BeanDescription description = config.introspect(mapper.getTypeFactory().constructType(type));
        List<BeanPropertyDefinition> properties = description.findProperties();

        // Pass 1 — primary names. Every primary claims its key unconditionally.
        Map<String, String> names = new LinkedHashMap<>(Math.max(4, properties.size() * 2));
        Map<String, String> claimedByPrimary = new HashMap<>(Math.max(4, properties.size() * 2));
        for (BeanPropertyDefinition property : properties) {
            String wireName = property.getName();
            String javaName = property.getInternalName();
            String previousOwner = claimedByPrimary.putIfAbsent(wireName, javaName);
            if (previousOwner != null && !previousOwner.equals(javaName)) {
                throw new ConfigurationException("Type " + type.getName() + " publishes the wire property name '"
                        + wireName + "' for both Java properties '" + previousOwner + "' and '" + javaName
                        + "'. The declared input policies of the two cannot be told apart on the wire; give one of "
                        + "them a distinct @JsonProperty name.");
            }
            names.put(wireName, javaName);
        }

        // Pass 2 — aliases, which fill only the keys no primary name claimed. Jackson binds the primary
        // on such a collision (it does not reject the configuration), so the projection must agree with
        // it rather than refuse to boot.
        for (BeanPropertyDefinition property : properties) {
            for (PropertyName alias : property.findAliases()) {
                String aliasName = alias.getSimpleName();
                if (claimedByPrimary.containsKey(aliasName)) {
                    continue;
                }
                names.putIfAbsent(aliasName, property.getInternalName());
            }
        }

        boolean identity =
                names.entrySet().stream().allMatch(entry -> entry.getKey().equals(entry.getValue()));
        return new Projection(identity, identity ? Map.of() : Map.copyOf(names));
    }
}
