// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver;
import com.fasterxml.jackson.databind.annotation.JsonTypeResolver;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileConfigurationException;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Proves one selected profile mapper safe for remote input before any schema or Router mount exists.
 *
 * <p>The validator walks every canonical type reachable from a tool's input carrier and declared
 * structured output exactly once, through the selected mapper's own introspection, so mix-ins and
 * module effects are seen exactly as the mapper sees them. Array, collection, reference and map
 * contents are enqueued; every other non-primitive, non-enum type contributes the union of its
 * effective serialization and deserialization property types. A primitive, an enum, a type with no
 * effective properties, and an already visited canonical type are leaves, so cyclic graphs terminate.
 *
 * <p>Every visited type must satisfy three closed rules: no default typer on either side, no
 * effective type information other than {@code @JsonTypeInfo(use = Id.NAME)} with a finite explicit
 * {@code @JsonSubTypes} allowlist, and Jackson's resolved serialization- and deserialization-facing
 * subtype mappings equal to that allowlist. Custom serializers and deserializers are trusted
 * application code and are accepted; they are not claimed to be statically proven safe.
 *
 * <p>A failure names the bounded profile id, the reachable type path, and the violated rule; it never
 * carries a payload or serialized value, and the message is capped at 1,024 UTF-16 code units.
 */
@Singleton
final class McpJsonProfileSafetyValidator {

    /** The bounded failure-message cap in UTF-16 code units. */
    private static final int MAX_MESSAGE_CODE_UNITS = 1_024;

    /** Creates the stateless composition-time validator. */
    @Inject
    McpJsonProfileSafetyValidator() {}

    /**
     * Validates every type reachable from one tool's input carrier and structured output.
     *
     * @param profile the effective profile whose mapper the tool will use
     * @param inputCarrierType the generated input-carrier type
     * @param structuredOutputType the declared structured-output type, or {@code null} when the tool
     *     returns text only
     * @throws JsonProfileConfigurationException if a root type is unresolved, a reachable type carries
     *     an unsafe polymorphism mechanism, or introspection fails
     */
    void validate(JsonMapperProfile profile, Type inputCarrierType, @Nullable Type structuredOutputType) {
        ObjectMapper mapper = profile.mapper();
        Traversal traversal = new Traversal(profile, mapper);
        traversal.enqueueRoot(inputCarrierType);
        if (structuredOutputType != null) {
            traversal.enqueueRoot(structuredOutputType);
        }
        traversal.run();
    }

    // --- Traversal ---

    /** One bounded walk of the reachable type graph of a single tool under one profile. */
    private static final class Traversal {

        private final JsonMapperProfile profile;
        private final TypeFactory typeFactory;
        private final SerializationConfig serializationConfig;
        private final DeserializationConfig deserializationConfig;
        private final AnnotationIntrospector introspector;
        private final Deque<ReachableType> pending = new ArrayDeque<>();
        private final Set<JavaType> visited = new HashSet<>();

        private Traversal(JsonMapperProfile profile, ObjectMapper mapper) {
            this.profile = profile;
            this.typeFactory = mapper.getTypeFactory();
            this.serializationConfig = mapper.getSerializationConfig();
            this.deserializationConfig = mapper.getDeserializationConfig();
            this.introspector = serializationConfig.getAnnotationIntrospector();
        }

        /** Converts one declared root through the mapper's type factory after rejecting unresolved shapes. */
        void enqueueRoot(Type root) {
            requireResolved(root, root.getTypeName());
            JavaType canonical = typeFactory.constructType(root);
            pending.addLast(new ReachableType(canonical, canonical.toCanonical()));
        }

        /** Visits every enqueued type once. */
        void run() {
            while (!pending.isEmpty()) {
                ReachableType reachable = pending.removeFirst();
                if (visited.add(reachable.type())) {
                    visit(reachable);
                }
            }
        }

        private void visit(ReachableType reachable) {
            JavaType type = reachable.type();
            rejectDefaultTyping(reachable);
            if (type.isPrimitive() || type.isEnumType()) {
                return;
            }
            if (enqueueContainerContent(reachable)) {
                return;
            }
            visitBean(reachable);
        }

        // --- Rule 2: no default typer on either side ---

        private void rejectDefaultTyping(ReachableType reachable) {
            if (serializationConfig.getDefaultTyper(reachable.type()) != null
                    || deserializationConfig.getDefaultTyper(reachable.type()) != null) {
                throw failure(reachable.path(), "Jackson default typing is enabled for this reachable type");
            }
        }

        // --- Rule 1: container handling ---

        /**
         * Enqueues the contents of an array, collection, reference or map type.
         *
         * @return {@code true} when the type was a container and its contents were enqueued
         */
        private boolean enqueueContainerContent(ReachableType reachable) {
            JavaType type = reachable.type();
            if (type.isArrayType() || type.isCollectionLikeType()) {
                enqueue(type.getContentType(), reachable.path(), "[]");
                return true;
            }
            if (type.isMapLikeType()) {
                enqueue(type.getKeyType(), reachable.path(), "{key}");
                enqueue(type.getContentType(), reachable.path(), "{value}");
                return true;
            }
            if (type.isReferenceType()
                    || Optional.class.equals(type.getRawClass())
                    || AtomicReference.class.equals(type.getRawClass())) {
                enqueue(referencedTypeOf(type), reachable.path(), "<referenced>");
                return true;
            }
            return false;
        }

        private JavaType referencedTypeOf(JavaType type) {
            if (type.isReferenceType()) {
                return type.getReferencedType();
            }
            return type.containedTypeCount() > 0 ? type.containedType(0) : typeFactory.constructType(Object.class);
        }

        // --- Rules 3 and 4, plus the effective property union ---

        private void visitBean(ReachableType reachable) {
            BeanDescription serializationDescription;
            BeanDescription deserializationDescription;
            try {
                serializationDescription = serializationConfig.introspect(reachable.type());
                deserializationDescription = deserializationConfig.introspect(reachable.type());
            } catch (RuntimeException failure) {
                throw failure(
                        reachable.path(),
                        "mapper introspection failed: " + failure.getClass().getSimpleName());
            }

            rejectUnsafePolymorphism(reachable, serializationDescription.getClassInfo());
            enqueuePropertyTypes(reachable, serializationDescription, deserializationDescription);
        }

        private void rejectUnsafePolymorphism(ReachableType reachable, AnnotatedClass classInfo) {
            JsonTypeInfo.Value typeInfo = introspector.findPolymorphicTypeInfo(serializationConfig, classInfo);
            if (typeInfo == null || typeInfo.getIdType() == JsonTypeInfo.Id.NONE) {
                return;
            }
            if (classInfo.getAnnotation(JsonTypeResolver.class) != null) {
                throw failure(reachable.path(), "a custom @JsonTypeResolver resolver is not an accepted mechanism");
            }
            if (classInfo.getAnnotation(JsonTypeIdResolver.class) != null) {
                throw failure(reachable.path(), "a custom @JsonTypeIdResolver resolver is not an accepted mechanism");
            }
            if (typeInfo.getIdType() != JsonTypeInfo.Id.NAME) {
                throw failure(
                        reachable.path(),
                        "type id mechanism 'Id." + typeInfo.getIdType()
                                + "' is not an accepted mechanism; only Id.NAME with an explicit @JsonSubTypes allowlist is");
            }
            Map<String, Class<?>> allowlist = declaredAllowlist(reachable, classInfo);
            requireResolvedSubtypesMatch(
                    reachable, allowlist, subtypesByClass(classInfo), "serialization-facing subtype mapping");
            requireResolvedSubtypesMatch(
                    reachable, allowlist, subtypesByTypeId(classInfo), "deserialization-facing subtype mapping");
            allowlist.forEach((logicalName, subtype) ->
                    enqueue(typeFactory.constructType(subtype), reachable.path(), "@" + logicalName));
        }

        /** Reads the explicit {@code @JsonSubTypes} allowlist and rejects an unbounded or ambiguous one. */
        private Map<String, Class<?>> declaredAllowlist(ReachableType reachable, AnnotatedClass classInfo) {
            List<NamedType> declared = introspector.findSubtypes(classInfo);
            if (declared == null || declared.isEmpty()) {
                throw failure(
                        reachable.path(),
                        "polymorphic base declares no explicit @JsonSubTypes allowlist, so its subtype graph is unbounded");
            }
            Map<String, Class<?>> allowlist = new LinkedHashMap<>();
            Set<Class<?>> allowlistedClasses = new LinkedHashSet<>();
            for (NamedType subtype : declared) {
                if (!subtype.hasName() || subtype.getName().isBlank()) {
                    throw failure(
                            reachable.path(), "subtype " + subtype.getType().getName() + " declares no logical name");
                }
                if (allowlist.putIfAbsent(subtype.getName(), subtype.getType()) != null) {
                    throw failure(reachable.path(), "duplicate logical subtype name '" + subtype.getName() + "'");
                }
                if (!allowlistedClasses.add(subtype.getType())) {
                    throw failure(
                            reachable.path(),
                            "duplicate subtype class " + subtype.getType().getName() + " in the allowlist");
                }
            }
            return allowlist;
        }

        private Collection<NamedType> subtypesByClass(AnnotatedClass classInfo) {
            return serializationConfig
                    .getSubtypeResolver()
                    .collectAndResolveSubtypesByClass(serializationConfig, classInfo);
        }

        private Collection<NamedType> subtypesByTypeId(AnnotatedClass classInfo) {
            return deserializationConfig
                    .getSubtypeResolver()
                    .collectAndResolveSubtypesByTypeId(deserializationConfig, classInfo);
        }

        /** Requires one resolved Jackson mapping to equal the explicit allowlist, name and class alike. */
        private void requireResolvedSubtypesMatch(
                ReachableType reachable,
                Map<String, Class<?>> allowlist,
                Collection<NamedType> resolved,
                String mappingName) {
            Map<String, Class<?>> normalized = new LinkedHashMap<>();
            for (NamedType subtype : resolved) {
                // Jackson returns the base itself as an unnamed entry; a named entry is a real subtype,
                // including when the visited type is one of the allowlisted subtypes of its own base.
                if (subtype.getType().equals(reachable.type().getRawClass()) && !subtype.hasName()) {
                    continue;
                }
                if (!subtype.hasName() || subtype.getName().isBlank()) {
                    throw failure(
                            reachable.path(),
                            mappingName + " resolves unnamed subtype "
                                    + subtype.getType().getName());
                }
                Class<?> previous = normalized.putIfAbsent(subtype.getName(), subtype.getType());
                if (previous != null && !previous.equals(subtype.getType())) {
                    throw failure(
                            reachable.path(),
                            mappingName + " resolves logical name '" + subtype.getName() + "' to conflicting types");
                }
            }
            if (!normalized.equals(allowlist)) {
                throw failure(
                        reachable.path(),
                        mappingName + " " + describe(normalized)
                                + " disagrees with the explicit @JsonSubTypes allowlist " + describe(allowlist));
            }
        }

        private static String describe(Map<String, Class<?>> mapping) {
            Set<String> rendered = new TreeSet<>();
            mapping.forEach((logicalName, subtype) -> rendered.add(logicalName + "=" + subtype.getName()));
            return rendered.toString();
        }

        /** Enqueues the union of effective serialization and deserialization property types. */
        private void enqueuePropertyTypes(
                ReachableType reachable, BeanDescription serialization, BeanDescription deserialization) {
            Map<String, JavaType> serializationProperties = propertyTypes(serialization);
            Map<String, JavaType> deserializationProperties = propertyTypes(deserialization);
            Set<String> propertyNames = new LinkedHashSet<>(serializationProperties.keySet());
            propertyNames.addAll(deserializationProperties.keySet());

            for (String propertyName : propertyNames) {
                JavaType serializationType = serializationProperties.get(propertyName);
                JavaType deserializationType = deserializationProperties.get(propertyName);
                if (serializationType != null
                        && deserializationType != null
                        && !serializationType.equals(deserializationType)) {
                    throw failure(
                            reachable.path(),
                            "property '" + propertyName + "' has inconsistent serialization and deserialization types");
                }
                enqueue(
                        serializationType != null ? serializationType : deserializationType,
                        reachable.path(),
                        propertyName);
            }
            rejectPropertyLevelResolvers(reachable, serialization, serializationConfig);
            rejectPropertyLevelResolvers(reachable, deserialization, deserializationConfig);
        }

        private static Map<String, JavaType> propertyTypes(BeanDescription description) {
            Map<String, JavaType> byName = new LinkedHashMap<>();
            for (BeanPropertyDefinition property : description.findProperties()) {
                byName.put(property.getName(), property.getPrimaryType());
            }
            return byName;
        }

        /** Rejects member-level type information that is anything but closed {@code Id.NAME}. */
        private void rejectPropertyLevelResolvers(
                ReachableType reachable, BeanDescription description, MapperConfig<?> config) {
            for (BeanPropertyDefinition property : description.findProperties()) {
                AnnotatedMember member = property.getPrimaryMember();
                if (member == null) {
                    continue;
                }
                JavaType propertyType = property.getPrimaryType();
                boolean declaresResolver = introspector.findPropertyTypeResolver(config, member, propertyType) != null
                        || (hasContent(propertyType)
                                && introspector.findPropertyContentTypeResolver(config, member, propertyType) != null);
                if (!declaresResolver) {
                    continue;
                }
                JsonTypeInfo.Value memberTypeInfo = introspector.findPolymorphicTypeInfo(config, member);
                if (memberTypeInfo == null || memberTypeInfo.getIdType() != JsonTypeInfo.Id.NAME) {
                    throw failure(
                            reachable.path() + " -> " + property.getName(),
                            "property-level type resolver is not an accepted mechanism");
                }
            }
        }

        /** Jackson only resolves a content type resolver for container and reference types. */
        private static boolean hasContent(JavaType type) {
            return type.isContainerType() || type.isReferenceType();
        }

        // --- Queueing and failures ---

        private void enqueue(@Nullable JavaType type, String parentPath, String step) {
            if (type == null) {
                return;
            }
            pending.addLast(new ReachableType(type, parentPath + " -> " + step + ": " + type.toCanonical()));
        }

        /** Rejects raw, wildcard, variable and otherwise unresolved declared root types. */
        private void requireResolved(Type type, String rootPath) {
            switch (type) {
                case Class<?> rawCandidate -> {
                    if (rawCandidate.getTypeParameters().length > 0) {
                        throw failure(rootPath, "raw type " + rawCandidate.getName() + " is not resolvable");
                    }
                }
                case ParameterizedType parameterized -> {
                    for (Type argument : parameterized.getActualTypeArguments()) {
                        requireResolved(argument, rootPath);
                    }
                }
                case GenericArrayType arrayType -> requireResolved(arrayType.getGenericComponentType(), rootPath);
                case WildcardType ignored -> throw failure(rootPath, "wildcard type is not resolvable");
                case TypeVariable<?> variable ->
                    throw failure(rootPath, "type variable " + variable.getName() + " is not resolvable");
                default -> throw failure(rootPath, "unknown type shape " + type.getTypeName());
            }
        }

        /** Builds the bounded, payload-free composition failure. */
        private JsonProfileConfigurationException failure(String typePath, String reason) {
            String message = "MCP JSON profile '" + profile.id().value() + "' rejects reachable type " + typePath + ": "
                    + reason;
            return new JsonProfileConfigurationException(
                    message.length() <= MAX_MESSAGE_CODE_UNITS
                            ? message
                            : message.substring(0, MAX_MESSAGE_CODE_UNITS));
        }
    }

    /** One canonical type reached during the walk, with the path that reached it. */
    private record ReachableType(JavaType type, String path) {}
}
