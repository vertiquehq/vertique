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
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileConfigurationException;
import jakarta.annotation.Nullable;
import java.lang.reflect.Executable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
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
 * <p>Effective metadata is read per mapper side. A mapper configured through
 * {@code ObjectMapper.setAnnotationIntrospectors} holds one introspector for serialization and
 * another for deserialization, so every introspector-driven check runs twice, each time with the
 * matching config and introspector, and a violation seen by either side rejects.
 *
 * <p>Reachability is only meaningful over resolved declarations, so a raw generic declaration is
 * rejected wherever it appears: on a declared root and on any reachable member alike. Jackson erases
 * a raw container to {@code Object} contents, which would otherwise let an unconstrained value graph
 * pass as a silent leaf. An explicitly declared {@code Object} content is the application's own
 * choice and stays accepted.
 *
 * <p>The same three rules apply to class, property and container-content scopes alike. Jackson
 * installs a member-declared resolver ahead of the declared base type's class-level one, so a
 * property or container content that declares its own type information is validated as its own
 * polymorphic base — including its effective allowlist, which is the member's own
 * {@code @JsonSubTypes} when present and the declared base type's class-level allowlist otherwise —
 * and every subtype it allows is enqueued into the same walk.
 *
 * <p>A failure names the bounded profile id, the reachable type path, and the violated rule; it never
 * carries a payload or serialized value, and the message is capped at 1,024 UTF-16 code units.
 */
final class McpJsonProfileSafetyValidator {

    /** The bounded failure-message cap in UTF-16 code units. */
    private static final int MAX_MESSAGE_CODE_UNITS = 1_024;

    /** Creates the stateless composition-time validator. */
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
        private final AnnotationIntrospector serializationIntrospector;
        private final AnnotationIntrospector deserializationIntrospector;
        private final Deque<ReachableType> pending = new ArrayDeque<>();
        private final Set<JavaType> visited = new HashSet<>();

        private Traversal(JsonMapperProfile profile, ObjectMapper mapper) {
            this.profile = profile;
            this.typeFactory = mapper.getTypeFactory();
            this.serializationConfig = mapper.getSerializationConfig();
            this.deserializationConfig = mapper.getDeserializationConfig();
            this.serializationIntrospector = serializationConfig.getAnnotationIntrospector();
            this.deserializationIntrospector = deserializationConfig.getAnnotationIntrospector();
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

            rejectUnsafePolymorphism(reachable, serializationDescription, deserializationDescription);
            enqueuePropertyTypes(reachable, serializationDescription, deserializationDescription);
        }

        /**
         * Applies the closed class-level polymorphism rules once per mapper side.
         *
         * <p>Each side is gated and validated with its own config, introspector and annotated class,
         * so effective type information that only one introspector reports is still rejected. When
         * both sides resolve the same allowlist — the ordinary single-introspector case — the resolved
         * mapping comparison and the subtype enqueue run once, so nothing is reported twice.
         *
         * @param reachable the type being visited
         * @param serialization the serialization-facing description of that type
         * @param deserialization the deserialization-facing description of that type
         */
        private void rejectUnsafePolymorphism(
                ReachableType reachable, BeanDescription serialization, BeanDescription deserialization) {
            AnnotatedClass serializationClassInfo = serialization.getClassInfo();
            AnnotatedClass deserializationClassInfo = deserialization.getClassInfo();
            Map<String, Class<?>> serializationAllowlist = effectiveClassAllowlist(
                    reachable, serializationClassInfo, serializationConfig, serializationIntrospector);
            Map<String, Class<?>> deserializationAllowlist = effectiveClassAllowlist(
                    reachable, deserializationClassInfo, deserializationConfig, deserializationIntrospector);
            if (serializationAllowlist != null) {
                requireResolvedMappingsMatch(
                        reachable, serializationAllowlist, serializationClassInfo, deserializationClassInfo);
            }
            if (deserializationAllowlist != null && !deserializationAllowlist.equals(serializationAllowlist)) {
                requireResolvedMappingsMatch(
                        reachable, deserializationAllowlist, serializationClassInfo, deserializationClassInfo);
            }
        }

        /**
         * Validates one mapper side's effective class-level type information.
         *
         * @param reachable the type being visited
         * @param classInfo that side's annotated class
         * @param config that side's mapper config
         * @param introspector the introspector that side actually uses
         * @return that side's explicit {@code @JsonSubTypes} allowlist, or {@code null} when the side
         *     reports no effective type information for this type
         */
        @Nullable
        private Map<String, Class<?>> effectiveClassAllowlist(
                ReachableType reachable,
                AnnotatedClass classInfo,
                MapperConfig<?> config,
                AnnotationIntrospector introspector) {
            JsonTypeInfo.Value typeInfo = introspector.findPolymorphicTypeInfo(config, classInfo);
            if (typeInfo == null || typeInfo.getIdType() == JsonTypeInfo.Id.NONE) {
                return null;
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
            return declaredAllowlist(reachable.path(), introspector.findSubtypes(classInfo));
        }

        /**
         * Requires both resolved subtype mappings to equal one side's allowlist, then enqueues it.
         *
         * @param reachable the polymorphic base being validated
         * @param allowlist the explicit allowlist both mappings must equal
         * @param serializationClassInfo the annotated class the serialization-facing mapping resolves from
         * @param deserializationClassInfo the annotated class the deserialization-facing mapping resolves from
         */
        private void requireResolvedMappingsMatch(
                ReachableType reachable,
                Map<String, Class<?>> allowlist,
                AnnotatedClass serializationClassInfo,
                AnnotatedClass deserializationClassInfo) {
            Class<?> baseClass = reachable.type().getRawClass();
            requireResolvedSubtypesMatch(
                    reachable.path(),
                    baseClass,
                    allowlist,
                    subtypesByClass(serializationClassInfo),
                    "serialization-facing subtype mapping");
            requireResolvedSubtypesMatch(
                    reachable.path(),
                    baseClass,
                    allowlist,
                    subtypesByTypeId(deserializationClassInfo),
                    "deserialization-facing subtype mapping");
            enqueueAllowlist(reachable.path(), allowlist);
        }

        /**
         * Validates the explicit {@code @JsonSubTypes} allowlist and rejects an unbounded or ambiguous one.
         *
         * @param path the bounded reachable path of the polymorphic base being validated
         * @param declared the subtypes Jackson's introspection declared for that base, possibly {@code null}
         * @return the allowlist as a logical-name to concrete-class mapping
         */
        private Map<String, Class<?>> declaredAllowlist(String path, @Nullable List<NamedType> declared) {
            if (declared == null || declared.isEmpty()) {
                throw failure(
                        path,
                        "polymorphic base declares no explicit @JsonSubTypes allowlist, so its subtype graph is unbounded");
            }
            Map<String, Class<?>> allowlist = new LinkedHashMap<>();
            Set<Class<?>> allowlistedClasses = new LinkedHashSet<>();
            for (NamedType subtype : declared) {
                if (!subtype.hasName() || subtype.getName().isBlank()) {
                    throw failure(path, "subtype " + subtype.getType().getName() + " declares no logical name");
                }
                if (allowlist.putIfAbsent(subtype.getName(), subtype.getType()) != null) {
                    throw failure(path, "duplicate logical subtype name '" + subtype.getName() + "'");
                }
                if (!allowlistedClasses.add(subtype.getType())) {
                    throw failure(
                            path, "duplicate subtype class " + subtype.getType().getName() + " in the allowlist");
                }
            }
            return allowlist;
        }

        /** Enqueues every allowlisted subtype so no branch of the subtype graph escapes the walk. */
        private void enqueueAllowlist(String path, Map<String, Class<?>> allowlist) {
            allowlist.forEach(
                    (logicalName, subtype) -> enqueue(typeFactory.constructType(subtype), path, "@" + logicalName));
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

        private Collection<NamedType> subtypesByClass(AnnotatedMember member, JavaType baseType) {
            return serializationConfig
                    .getSubtypeResolver()
                    .collectAndResolveSubtypesByClass(serializationConfig, member, baseType);
        }

        private Collection<NamedType> subtypesByTypeId(AnnotatedMember member, JavaType baseType) {
            return deserializationConfig
                    .getSubtypeResolver()
                    .collectAndResolveSubtypesByTypeId(deserializationConfig, member, baseType);
        }

        /**
         * Requires one resolved Jackson mapping to equal the explicit allowlist, name and class alike.
         *
         * @param path the bounded reachable path of the polymorphic base being validated
         * @param baseClass the raw class the mapping was resolved for, whose unnamed entry is the base
         * @param allowlist the explicit allowlist the mapping must equal
         * @param resolved the mapping Jackson resolved
         * @param mappingName the side of the mapper the mapping came from, for the failure message
         */
        private void requireResolvedSubtypesMatch(
                String path,
                Class<?> baseClass,
                Map<String, Class<?>> allowlist,
                Collection<NamedType> resolved,
                String mappingName) {
            Map<String, Class<?>> normalized = new LinkedHashMap<>();
            for (NamedType subtype : resolved) {
                // Jackson returns the base itself as an unnamed entry; a named entry is a real subtype,
                // including when the visited type is one of the allowlisted subtypes of its own base.
                if (subtype.getType().equals(baseClass) && !subtype.hasName()) {
                    continue;
                }
                if (!subtype.hasName() || subtype.getName().isBlank()) {
                    throw failure(
                            path,
                            mappingName + " resolves unnamed subtype "
                                    + subtype.getType().getName());
                }
                Class<?> previous = normalized.putIfAbsent(subtype.getName(), subtype.getType());
                if (previous != null && !previous.equals(subtype.getType())) {
                    throw failure(
                            path,
                            mappingName + " resolves logical name '" + subtype.getName() + "' to conflicting types");
                }
            }
            if (!normalized.equals(allowlist)) {
                throw failure(
                        path,
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
            rejectUnsafeMemberDeclarations(reachable, serialization, serializationConfig, serializationIntrospector);
            rejectUnsafeMemberDeclarations(
                    reachable, deserialization, deserializationConfig, deserializationIntrospector);
        }

        private static Map<String, JavaType> propertyTypes(BeanDescription description) {
            Map<String, JavaType> byName = new LinkedHashMap<>();
            for (BeanPropertyDefinition property : description.findProperties()) {
                byName.put(property.getName(), property.getPrimaryType());
            }
            return byName;
        }

        /**
         * Applies the member-scope rules to every property and container-content scope of one side.
         *
         * <p>The closed polymorphism rules are read through the introspector the given side actually
         * uses, so member-declared metadata visible to only one of a split introspector pair is still
         * seen. Each member's own declaration is additionally required to be resolved.
         *
         * @param reachable the type whose members are being validated
         * @param description that side's description of the type
         * @param config that side's mapper config
         * @param introspector the introspector that side actually uses
         */
        private void rejectUnsafeMemberDeclarations(
                ReachableType reachable,
                BeanDescription description,
                MapperConfig<?> config,
                AnnotationIntrospector introspector) {
            for (BeanPropertyDefinition property : description.findProperties()) {
                AnnotatedMember member = property.getPrimaryMember();
                if (member == null) {
                    continue;
                }
                JavaType propertyType = property.getPrimaryType();
                String propertyPath = reachable.path() + " -> " + property.getName();
                rejectRawMemberDeclaration(propertyPath, member);
                if (introspector.findPropertyTypeResolver(config, member, propertyType) != null) {
                    requireClosedMemberPolymorphism(propertyPath, member, propertyType, config, introspector);
                }
                if (hasContent(propertyType)
                        && introspector.findPropertyContentTypeResolver(config, member, propertyType) != null) {
                    requireClosedMemberPolymorphism(
                            propertyPath + " -> <content>",
                            member,
                            propertyType.getContentType(),
                            config,
                            introspector);
                }
            }
        }

        /**
         * Requires one member-declared polymorphism scope to be the closed {@code Id.NAME} shape.
         *
         * <p>Jackson installs a member-declared resolver ahead of the declared base type's class-level
         * one, so a safe class-level base is no defense: the member scope repeats every class-level rule.
         * Its effective allowlist is the member's own {@code @JsonSubTypes} when it declares one and the
         * declared base type's class-level allowlist otherwise, exactly as Jackson's member-scoped
         * subtype resolution falls back.
         *
         * @param path the bounded reachable path of this member scope
         * @param member the annotated member declaring the type information
         * @param baseType the type the member-declared resolver is installed for
         * @param config the mapper side whose introspection declared the resolver
         * @param introspector the introspector that side actually uses
         */
        private void requireClosedMemberPolymorphism(
                String path,
                AnnotatedMember member,
                JavaType baseType,
                MapperConfig<?> config,
                AnnotationIntrospector introspector) {
            if (member.getAnnotation(JsonTypeResolver.class) != null) {
                throw failure(path, "a custom @JsonTypeResolver resolver is not an accepted mechanism");
            }
            if (member.getAnnotation(JsonTypeIdResolver.class) != null) {
                throw failure(path, "a custom @JsonTypeIdResolver resolver is not an accepted mechanism");
            }
            JsonTypeInfo.Value memberTypeInfo = introspector.findPolymorphicTypeInfo(config, member);
            if (memberTypeInfo == null || memberTypeInfo.getIdType() != JsonTypeInfo.Id.NAME) {
                throw failure(
                        path,
                        "type id mechanism '"
                                + (memberTypeInfo == null ? "unknown" : "Id." + memberTypeInfo.getIdType())
                                + "' is not an accepted mechanism; only Id.NAME with an explicit @JsonSubTypes allowlist is");
            }
            Map<String, Class<?>> allowlist =
                    declaredAllowlist(path, memberSubtypes(member, baseType, config, introspector));
            Class<?> baseClass = baseType.getRawClass();
            requireResolvedSubtypesMatch(
                    path,
                    baseClass,
                    allowlist,
                    subtypesByClass(member, baseType),
                    "serialization-facing subtype mapping");
            requireResolvedSubtypesMatch(
                    path,
                    baseClass,
                    allowlist,
                    subtypesByTypeId(member, baseType),
                    "deserialization-facing subtype mapping");
            enqueueAllowlist(path, allowlist);
        }

        /** Reads the member's own allowlist, falling back to the declared base type's as Jackson does. */
        @Nullable
        private List<NamedType> memberSubtypes(
                AnnotatedMember member,
                JavaType baseType,
                MapperConfig<?> config,
                AnnotationIntrospector introspector) {
            List<NamedType> declared = introspector.findSubtypes(member);
            if (declared != null && !declared.isEmpty()) {
                return declared;
            }
            return introspector.findSubtypes(
                    config.introspectClassAnnotations(baseType).getClassInfo());
        }

        /** Jackson only resolves a content type resolver for container and reference types. */
        private static boolean hasContent(JavaType type) {
            return type.isContainerType() || type.isReferenceType();
        }

        // --- Rule 1: resolved member declarations ---

        /**
         * Rejects a raw generic declaration on one reachable member.
         *
         * <p>Jackson resolves a raw {@code List} to a collection of {@code Object} and a raw
         * {@code Map} to a map of {@code Object} to {@code Object}, which the walk would then visit as
         * a property-less leaf — an unconstrained remote-input value graph that passed no rule. The
         * member's underlying {@code java.lang.reflect.Type} is what separates that erased declaration
         * from an explicitly declared {@code List<Object>}: the raw one is a {@code Class} with type
         * parameters, the explicit one a {@code ParameterizedType}. Only the raw one is rejected.
         *
         * <p>A wildcard or type variable in a member declaration is not a raw declaration: Jackson
         * resolves it against the declaring type's bindings, so a generic DTO reached with concrete
         * arguments keeps working and is left to the walk.
         *
         * @param propertyPath the bounded reachable path of this member
         * @param member the annotated member whose declaration is checked
         */
        private void rejectRawMemberDeclaration(String propertyPath, AnnotatedMember member) {
            Type declared = declaredGenericType(member);
            if (declared != null) {
                rejectRawUsage(propertyPath, declared);
            }
        }

        /** Walks a declared member type, rejecting a raw use of a parameterizable class at any depth. */
        private void rejectRawUsage(String propertyPath, Type declared) {
            switch (declared) {
                case Class<?> rawCandidate -> {
                    if (rawCandidate.getTypeParameters().length > 0) {
                        throw failure(
                                propertyPath,
                                "property declares raw type " + rawCandidate.getName()
                                        + ", whose contents erase to Object and are not resolvable");
                    }
                }
                case ParameterizedType parameterized -> {
                    for (Type argument : parameterized.getActualTypeArguments()) {
                        rejectRawUsage(propertyPath, argument);
                    }
                }
                case GenericArrayType arrayType -> rejectRawUsage(propertyPath, arrayType.getGenericComponentType());
                default -> {
                    // A wildcard or type variable is resolved by Jackson, not erased; nothing to reject.
                }
            }
        }

        /**
         * Reads the underlying generic declaration of one annotated member.
         *
         * @param member the member Jackson chose as the property's primary one
         * @return the declared {@code Type}, or {@code null} when the member exposes no unambiguous one
         */
        @Nullable
        private static Type declaredGenericType(AnnotatedMember member) {
            return switch (member) {
                case AnnotatedField field -> field.getAnnotated().getGenericType();
                case AnnotatedMethod method -> declaredMethodType(method.getAnnotated());
                case AnnotatedParameter parameter -> declaredParameterType(parameter);
                default -> null;
            };
        }

        /** A getter contributes its generic return type, a setter its single generic parameter type. */
        @Nullable
        private static Type declaredMethodType(Method method) {
            return switch (method.getParameterCount()) {
                case 0 -> method.getGenericReturnType();
                case 1 -> method.getGenericParameterTypes()[0];
                default -> null;
            };
        }

        /**
         * Reads a creator parameter's generic declaration by index.
         *
         * <p>{@code Executable.getGenericParameterTypes()} omits synthetic parameters for inner-class
         * and enum constructors, which would misalign the index; that ambiguous case reports no
         * declaration rather than an arbitrary one.
         *
         * @param parameter the annotated creator parameter
         * @return its declared {@code Type}, or {@code null} when the index cannot be trusted
         */
        @Nullable
        private static Type declaredParameterType(AnnotatedParameter parameter) {
            if (!(parameter.getOwner().getMember() instanceof Executable executable)) {
                return null;
            }
            Type[] declared = executable.getGenericParameterTypes();
            int index = parameter.getIndex();
            if (declared.length != executable.getParameterCount() || index >= declared.length) {
                return null;
            }
            return declared[index];
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
