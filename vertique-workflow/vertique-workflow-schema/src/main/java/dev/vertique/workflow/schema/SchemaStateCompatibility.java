// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.migration.WorkflowStateCompatibility;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link WorkflowStateCompatibility} that compares the canonical JSON Schemas of two state classes under the
 * process JSON profile.
 *
 * <p>The target extends the source when every property of the source exists in the target with the same type:
 * properties the target adds are allowed. Nested objects compare property by property, collection items compare
 * by the same rule, map values compare by the same rule, and an enum target must contain every constant of the
 * source; a source property without constants is not compatible with an enum target. A property whose type the target widens to also admit {@code null} is compatible. A source schema that
 * composes alternatives ({@code allOf}, {@code anyOf}, {@code oneOf}) is compatible only with a target that
 * declares the same composition.
 *
 * <p>The first incompatible path is reported as property names joined by {@code .}, {@code []} after a collection
 * and {@code {}} after a map; only schema paths are reported, never values.
 */
@Singleton
final class SchemaStateCompatibility implements WorkflowStateCompatibility {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaStateCompatibility.class);
    private static final String ROOT_PATH = "$";
    private static final String PROPERTIES = "properties";
    private static final String ITEMS = "items";
    private static final String ADDITIONAL = "additionalProperties";
    private static final String ENUM = "enum";
    private static final String TYPE = "type";
    private static final String FORMAT = "format";
    private static final String REF = "$ref";
    private static final String DEFS = "$defs";
    private static final String DEFS_PREFIX = "#/$defs/";
    private static final String[] COMPOSITIONS = {"allOf", "anyOf", "oneOf"};

    private final PayloadSchemaGenerator generator;
    private final ConcurrentHashMap<Class<?>, JsonObject> schemas = new ConcurrentHashMap<>();

    @Inject
    SchemaStateCompatibility(JsonMapperProfileRegistry profileRegistry, JsonConfig jsonConfig) {
        // The comparison reads types, not constraints, so no bean validator supplements the schemas.
        this(new PayloadSchemaGenerator(profileRegistry, jsonConfig, Optional.empty()));
    }

    SchemaStateCompatibility(PayloadSchemaGenerator generator) {
        this.generator = generator;
    }

    @Override
    public Optional<String> firstIncompatibility(Class<?> sourceState, Class<?> targetState) {
        JsonObject source = schemaOf(sourceState);
        JsonObject target = schemaOf(targetState);
        Walk walk = new Walk(source, target);
        return Optional.ofNullable(walk.compare(source, target, ""));
    }

    private JsonObject schemaOf(Class<?> state) {
        JsonObject cached = schemas.get(state);
        if (cached != null) {
            return cached;
        }
        JsonObject described;
        try {
            described = generator.describe(state);
        } catch (RuntimeException e) {
            LOG.debug("The state type {} cannot be described as a JSON Schema", state.getName(), e);
            throw new WorkflowDefinitionException(
                    "a state type cannot be described as a JSON Schema, so it cannot be compared with another state"
                            + " type: " + e.getClass().getSimpleName(),
                    e);
        }
        schemas.putIfAbsent(state, described);
        return described;
    }

    /**
     * One comparison of two documents; the visited pairs end the walk of a recursive type. A pair is identified by
     * the maps behind the two nodes, because reading a nested object out of a map-backed document wraps the same
     * map in a new {@link JsonObject} each time, so the wrappers of one node differ.
     */
    private static final class Walk {
        private final JsonObject sourceRoot;
        private final JsonObject targetRoot;
        private final Map<Map<String, Object>, Set<Map<String, Object>>> visited = new IdentityHashMap<>();

        Walk(JsonObject sourceRoot, JsonObject targetRoot) {
            this.sourceRoot = sourceRoot;
            this.targetRoot = targetRoot;
        }

        /** Returns the first incompatible path under {@code path}, or {@code null} when the target extends. */
        String compare(JsonObject sourceNode, JsonObject targetNode, String path) {
            JsonObject source = resolve(sourceNode, sourceRoot);
            JsonObject target = resolve(targetNode, targetRoot);
            if (source == null || target == null) {
                return at(path);
            }
            if (!visited.computeIfAbsent(
                            source.getMap(), k -> java.util.Collections.newSetFromMap(new IdentityHashMap<>()))
                    .add(target.getMap())) {
                return null;
            }
            if (!sameComposition(source, target) || !formatKept(source, target) || !typesKept(source, target)) {
                return at(path);
            }
            if (!source.containsKey(ENUM) && target.containsKey(ENUM)) {
                return at(path);
            }
            if (source.containsKey(ENUM)) {
                return enumKept(source, target) ? null : at(path);
            }
            String inside = properties(source, target, path);
            if (inside != null) {
                return inside;
            }
            inside = collectionItems(source, target, path);
            if (inside != null) {
                return inside;
            }
            return mapValues(source, target, path);
        }

        private String properties(JsonObject source, JsonObject target, String path) {
            JsonObject sourceProps = source.getJsonObject(PROPERTIES);
            if (sourceProps == null) {
                return null;
            }
            JsonObject targetProps = target.getJsonObject(PROPERTIES);
            for (String name : sourceProps.fieldNames()) {
                String child = path.isEmpty() ? name : path + "." + name;
                JsonObject targetProp = targetProps == null ? null : targetProps.getJsonObject(name);
                JsonObject sourceProp = sourceProps.getJsonObject(name);
                if (targetProp == null || sourceProp == null) {
                    return child;
                }
                String found = compare(sourceProp, targetProp, child);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        private String collectionItems(JsonObject source, JsonObject target, String path) {
            JsonObject sourceItems = source.getJsonObject(ITEMS);
            if (sourceItems == null) {
                return null;
            }
            JsonObject targetItems = target.getJsonObject(ITEMS);
            if (targetItems == null) {
                return at(path);
            }
            return compare(sourceItems, targetItems, path + "[]");
        }

        private String mapValues(JsonObject source, JsonObject target, String path) {
            Object sourceValues = source.getValue(ADDITIONAL);
            if (!(sourceValues instanceof JsonObject sourceSchema)) {
                return null;
            }
            if (!(target.getValue(ADDITIONAL) instanceof JsonObject targetSchema)) {
                return at(path);
            }
            return compare(sourceSchema, targetSchema, path + "{}");
        }

        private static boolean sameComposition(JsonObject source, JsonObject target) {
            for (String keyword : COMPOSITIONS) {
                if (source.containsKey(keyword)
                        && !java.util.Objects.equals(source.getValue(keyword), target.getValue(keyword))) {
                    return false;
                }
            }
            return true;
        }

        private static boolean formatKept(JsonObject source, JsonObject target) {
            String format = source.getString(FORMAT);
            return format == null || format.equals(target.getString(FORMAT));
        }

        /** The target admits every type the source admits; a source without a type restriction needs none. */
        private static boolean typesKept(JsonObject source, JsonObject target) {
            Set<String> sourceTypes = typesOf(source);
            Set<String> targetTypes = typesOf(target);
            if (sourceTypes.isEmpty()) {
                return targetTypes.isEmpty();
            }
            if (targetTypes.isEmpty()) {
                return true;
            }
            return targetTypes.containsAll(sourceTypes);
        }

        private static boolean enumKept(JsonObject source, JsonObject target) {
            JsonArray targetConstants = target.getJsonArray(ENUM);
            if (targetConstants == null) {
                return false;
            }
            for (Object constant : source.getJsonArray(ENUM)) {
                if (!targetConstants.contains(constant)) {
                    return false;
                }
            }
            return true;
        }

        private static Set<String> typesOf(JsonObject node) {
            Object type = node.getValue(TYPE);
            Set<String> types = new LinkedHashSet<>();
            if (type instanceof String single) {
                types.add(single);
            } else if (type instanceof JsonArray many) {
                many.forEach(t -> types.add(String.valueOf(t)));
            }
            return types;
        }

        /** Follows local references to the node they name; {@code null} when a reference does not resolve. */
        private static JsonObject resolve(JsonObject node, JsonObject root) {
            JsonObject current = node;
            for (int hops = 0; hops < 32 && current != null; hops++) {
                String ref = current.getString(REF);
                if (ref == null) {
                    return current;
                }
                if ("#".equals(ref)) {
                    current = root;
                } else if (ref.startsWith(DEFS_PREFIX)) {
                    JsonObject defs = root.getJsonObject(DEFS);
                    current = defs == null ? null : defs.getJsonObject(ref.substring(DEFS_PREFIX.length()));
                } else {
                    return null;
                }
            }
            return null;
        }

        private static String at(String path) {
            return path.isEmpty() ? ROOT_PATH : path;
        }
    }
}
