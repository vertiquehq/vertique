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
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeIdResolver;
import com.fasterxml.jackson.databind.jsontype.TypeResolverBuilder;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.jsontype.impl.TypeNameIdResolver;
import com.fasterxml.jackson.databind.type.TypeFactory;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileConfigurationException;
import jakarta.annotation.Nullable;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
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
import java.util.function.Function;
import java.util.function.Supplier;

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
 * <p>Every visited type must satisfy these closed rules: no default typer on either side, no
 * effective type information other than {@code @JsonTypeInfo(use = Id.NAME)} with a finite explicit
 * {@code @JsonSubTypes} allowlist, Jackson's resolved serialization- and deserialization-facing
 * subtype mappings equal to that allowlist, and — the terminal rule — the live handler the mapper
 * actually builds resolving remote input only to that allowlist: the deserialization-side {@code
 * TypeNameIdResolver}'s own {@code id → class} map and the type deserializer's {@code defaultImpl} are
 * each a subset of the allowlist the walk enqueues. Custom serializers and deserializers are trusted
 * application code and are accepted; they are not claimed to be statically proven safe.
 *
 * <p>Type information is read the way Jackson installs it, not only the way it is annotated. Every
 * scope is gated on the resolver the mapper would actually install — {@code findTypeResolver} for
 * the class scope, {@code findPropertyTypeResolver} and {@code findPropertyContentTypeResolver} for
 * the member scopes — so an introspector or module that installs a resolver without reporting
 * {@code @JsonTypeInfo} metadata is rejected rather than seen as an unannotated type. Both the class
 * and member scopes then read the type deserializer the mapper actually builds down to its
 * <em>terminal binding</em>. That a resolver is Jackson's own {@code TypeNameIdResolver} is a
 * necessary precondition — a custom resolver reporting {@code Id.NAME} from {@code getMechanism()} is
 * still rejected on identity — but it is not the sufficient proof: a builder whose
 * {@code _customIdResolver} is a genuine {@code TypeNameIdResolver} constructed from a poisoned
 * subtype collection passes the identity check while binding a wire-chosen id to a base-subtype the
 * finite {@code @JsonSubTypes} allowlist never listed, and a {@code defaultImpl} lets an id-less
 * payload instantiate a class no id maps to. The sufficient proof is therefore terminal: the live
 * resolver's own {@code id → class} map ({@code TypeNameIdResolver._idToType}, the map
 * {@code typeFromId} consults) and the type deserializer's {@code defaultImpl} are each required to be
 * a subset of the allowlist the walk enqueues, so what the mapper will actually instantiate from
 * remote input is proven inside the validated graph. The terminal binding is read on the
 * deserialization side, where a wire id becomes a class; the serialization side, which never turns a
 * wire id into a class, is gated on resolver identity alone. Reported metadata, the self-reported
 * mechanism, the resolved subtype mapping, and the live terminal binding are independent signals, and
 * only the last is the acceptance criterion.
 *
 * <p>The class scope is entered for every non-primitive, non-enum reachable type, containers
 * included. Jackson resolves a container's own type handling by asking {@code findTypeResolver} for
 * the container raw class — {@code java.util.List}, {@code java.util.Map}, the array class, the
 * reference class — so validating only a container's contents would leave a resolver installed on
 * the container itself live. The class-level resolver gate introspects the annotated class Jackson's
 * factories install from — {@code introspectClassAnnotations(baseType.getRawClass())}, an erased
 * class carrying no generic bindings — not the resolved {@code JavaType}, so an introspector keying
 * its resolver on the annotated class's own bindings cannot show the validator a safe view while the
 * mapper installs an unsafe resolver from the erased one. Bean property discovery stays on the full
 * resolved type.
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
 * <p>The same closed rules — including the terminal live-map and {@code defaultImpl} checks — apply to
 * class, property and container-content scopes alike. Jackson
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
            if (isContainerLike(type)) {
                rejectUnsafeContainerPolymorphism(reachable);
                enqueueContainerContent(reachable);
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
         * Reports whether Jackson resolves this type through its contents rather than its properties.
         *
         * @param type the reachable type
         * @return {@code true} for an array, collection, map or reference type
         */
        private static boolean isContainerLike(JavaType type) {
            return type.isArrayType()
                    || type.isCollectionLikeType()
                    || type.isMapLikeType()
                    || type.isReferenceType()
                    || Optional.class.equals(type.getRawClass())
                    || AtomicReference.class.equals(type.getRawClass());
        }

        /**
         * Enqueues the contents of an array, collection, reference or map type.
         *
         * @param reachable a container type, as reported by {@link #isContainerLike(JavaType)}
         */
        private void enqueueContainerContent(ReachableType reachable) {
            JavaType type = reachable.type();
            if (type.isArrayType() || type.isCollectionLikeType()) {
                enqueue(type.getContentType(), reachable.path(), "[]");
                return;
            }
            if (type.isMapLikeType()) {
                enqueue(type.getKeyType(), reachable.path(), "{key}");
                enqueue(type.getContentType(), reachable.path(), "{value}");
                return;
            }
            enqueue(referencedTypeOf(type), reachable.path(), "<referenced>");
        }

        /**
         * Applies the class-level polymorphism rules to a container before its contents are enqueued.
         *
         * <p>A container is a polymorphic base in its own right: {@code BasicSerializerFactory} and
         * {@code BasicDeserializerFactory} resolve type handling for a root value or a bean property by
         * asking {@code findTypeResolver} for the container's own raw class and installing whatever it
         * returns, so an introspector that keys a resolver to {@code java.util.List} reopens the
         * subtype space of every list in the graph while the contents themselves stay safe.
         *
         * <p>Only the annotations of the container class are needed, so the class is introspected the
         * way Jackson's own type-serializer construction introspects it — through
         * {@code introspectClassAnnotations} of the container's <em>raw class</em> — rather than
         * through a full property introspection that has no meaning for a container. Passing the raw
         * class, not the resolved {@link JavaType}, mirrors {@code Basic{Serializer,Deserializer}Factory},
         * which introspect {@code baseType.getRawClass()} before asking {@code findTypeResolver}; an
         * introspector that keys its resolver on the annotated class's own generic bindings therefore
         * cannot show the validator a different {@link AnnotatedClass} than the mapper installs from.
         *
         * @param reachable the container type being visited
         */
        private void rejectUnsafeContainerPolymorphism(ReachableType reachable) {
            AnnotatedClass serializationClassInfo;
            AnnotatedClass deserializationClassInfo;
            try {
                serializationClassInfo = serializationConfig
                        .introspectClassAnnotations(reachable.type().getRawClass())
                        .getClassInfo();
                deserializationClassInfo = deserializationConfig
                        .introspectClassAnnotations(reachable.type().getRawClass())
                        .getClassInfo();
            } catch (RuntimeException failure) {
                throw failure(
                        reachable.path(),
                        "mapper introspection failed: " + failure.getClass().getSimpleName());
            }
            rejectUnsafePolymorphism(reachable, serializationClassInfo, deserializationClassInfo);
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
            AnnotatedClass serializationResolverClassInfo;
            AnnotatedClass deserializationResolverClassInfo;
            try {
                serializationDescription = serializationConfig.introspect(reachable.type());
                deserializationDescription = deserializationConfig.introspect(reachable.type());
                // The class-level resolver gate must read the annotated class Jackson's own factories
                // install from — introspectClassAnnotations(baseType.getRawClass()) — not the
                // full-type BeanDescription's class info, so a bindings-discriminating introspector
                // cannot show the validator a safe AnnotatedClass while the mapper installs from the
                // erased one. Property discovery below stays on the full resolved type.
                serializationResolverClassInfo = serializationConfig
                        .introspectClassAnnotations(reachable.type().getRawClass())
                        .getClassInfo();
                deserializationResolverClassInfo = deserializationConfig
                        .introspectClassAnnotations(reachable.type().getRawClass())
                        .getClassInfo();
            } catch (RuntimeException failure) {
                throw failure(
                        reachable.path(),
                        "mapper introspection failed: " + failure.getClass().getSimpleName());
            }

            rejectUnsafePolymorphism(reachable, serializationResolverClassInfo, deserializationResolverClassInfo);
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
         * @param serializationClassInfo the serialization-facing annotated class of that type
         * @param deserializationClassInfo the deserialization-facing annotated class of that type
         */
        private void rejectUnsafePolymorphism(
                ReachableType reachable,
                AnnotatedClass serializationClassInfo,
                AnnotatedClass deserializationClassInfo) {
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
         * <p>The gate is the resolver Jackson would actually install, not only the
         * {@code @JsonTypeInfo}-shaped metadata: {@code Basic{Serializer,Deserializer}Factory} resolve
         * class-level polymorphism by asking {@code findTypeResolver} and installing whatever it
         * returns, so an introspector — or a module carrying one — that overrides that method alone
         * reopens the subtype space while every metadata-shaped check sees nothing. Consulting both
         * signals also subsumes the {@code @JsonTypeResolver}/{@code @JsonTypeIdResolver} annotation
         * rejections, which are otherwise only reachable once the metadata is already reported.
         *
         * <p>An installed resolver is accepted only when the same side also reports the sanctioned
         * {@code Id.NAME} shape <em>and</em> the handler that side actually builds carries Jackson's
         * own {@link TypeNameIdResolver} — the exact type the legitimate {@code @JsonTypeInfo(use =
         * NAME)} path builds on both the serialization and deserialization sides. Reading the live
         * resolver's identity rather than its self-reported mechanism is what closes the spoof: a
         * custom {@link TypeIdResolver} can report {@code Id.NAME} from {@code getMechanism()} while
         * its {@code typeFromId} maps a wire-chosen id to any subtype of the base — including one the
         * finite {@code @JsonSubTypes} allowlist never listed and the walk never proved — so a
         * mechanism-only check would accept an impostor that reopens the subtype space. Any resolver
         * that is not a {@code TypeNameIdResolver} — a custom impostor, an {@code Id.CLASS} or
         * {@code Id.CUSTOM} builder — is rejected. {@code Id.NONE} keeps meaning <em>absent</em>: it
         * is Jackson's own way of disabling polymorphism, and the marker builder it installs builds no
         * handler at all.
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
            boolean declaresTypeInfo = typeInfo != null && typeInfo.getIdType() != JsonTypeInfo.Id.NONE;
            InstalledResolver installed = installedClassResolver(reachable, classInfo, config, introspector);
            if (!declaresTypeInfo && installed == null) {
                return null;
            }
            if (classInfo.getAnnotation(JsonTypeResolver.class) != null) {
                throw failure(reachable.path(), "a custom @JsonTypeResolver resolver is not an accepted mechanism");
            }
            if (classInfo.getAnnotation(JsonTypeIdResolver.class) != null) {
                throw failure(reachable.path(), "a custom @JsonTypeIdResolver resolver is not an accepted mechanism");
            }
            if (!declaresTypeInfo) {
                throw failure(
                        reachable.path(),
                        "the mapper installs a class-level type resolver reporting no @JsonTypeInfo metadata;"
                                + " only Id.NAME with an explicit @JsonSubTypes allowlist is an accepted mechanism");
            }
            if (typeInfo.getIdType() != JsonTypeInfo.Id.NAME) {
                throw failure(
                        reachable.path(),
                        "type id mechanism 'Id." + typeInfo.getIdType()
                                + "' is not an accepted mechanism; only Id.NAME with an explicit @JsonSubTypes allowlist is");
            }
            if (installed != null && !installed.sanctioned()) {
                throw failure(reachable.path(), unsafeInstalledResolverReason("class", installed));
            }
            Map<String, Class<?>> allowlist = declaredAllowlist(reachable.path(), introspector.findSubtypes(classInfo));
            if (installed != null) {
                requireTerminalDeserBinding(
                        reachable.path(),
                        "class",
                        installed,
                        allowlist,
                        reachable.type().getRawClass());
            }
            return allowlist;
        }

        /**
         * Builds the rejection reason for a live type id resolver that is not Jackson's sanctioned
         * {@link TypeNameIdResolver}.
         *
         * <p>The self-reported mechanism is named too, so an {@code Id.CLASS} or {@code Id.CUSTOM}
         * builder masked behind {@code Id.NAME} metadata still surfaces its mechanism, while an
         * impostor that reports {@code Id.NAME} from {@code getMechanism()} is caught by the resolver
         * type it actually is.
         *
         * @param scope {@code "class"} or {@code "member"}, naming where the resolver is installed
         * @param installed the live resolver that failed the identity check
         * @return the bounded, payload-free reason
         */
        private static String unsafeInstalledResolverReason(String scope, InstalledResolver installed) {
            return "the mapper reports Id.NAME metadata but installs " + scope
                    + "-level type handling that is not Jackson's sanctioned name resolver (resolver "
                    + installed.resolverType() + ", mechanism Id." + installed.mechanism()
                    + "); only Id.NAME through Jackson's TypeNameIdResolver with an explicit @JsonSubTypes"
                    + " allowlist is an accepted mechanism";
        }

        /**
         * Reports the identity of the class-level type handler one mapper side actually installs.
         *
         * <p>The builder alone is not the resolver: Jackson's serializer and deserializer factories
         * ask {@code findTypeResolver} and then <em>build</em> a type serializer or type deserializer
         * from what comes back, and it is that handler's own {@link TypeIdResolver} that decides how a
         * type id is read and mapped to a class. Reading the live resolver's identity rather than its
         * self-reported mechanism is what closes the spoof: an impostor resolver can report
         * {@code Id.NAME} from {@code getMechanism()} while its {@code typeFromId} escapes the
         * allowlist, so only Jackson's own {@link TypeNameIdResolver} — the type the legitimate
         * {@code @JsonTypeInfo(use = NAME)} path builds — is accepted.
         *
         * <p>A {@code null} handler is Jackson's own signal that no type handling is installed at all —
         * the {@code Id.NONE} marker builder returns one — so it is reported as absent rather than as a
         * resolver. The build runs against the same side's config and resolved subtype mapping Jackson
         * would use, and each step that can fail converts to its own bounded composition failure so the
         * message names the step that actually failed.
         *
         * @param reachable the type being visited
         * @param classInfo that side's annotated class
         * @param config that side's mapper config
         * @param introspector the introspector that side actually uses
         * @return the live resolver identity, or {@code null} when that side installs no class-level
         *     type handling
         */
        @Nullable
        private InstalledResolver installedClassResolver(
                ReachableType reachable,
                AnnotatedClass classInfo,
                MapperConfig<?> config,
                AnnotationIntrospector introspector) {
            TypeResolverBuilder<?> resolver;
            try {
                resolver = introspector.findTypeResolver(config, classInfo, reachable.type());
            } catch (RuntimeException failure) {
                throw failure(
                        reachable.path(),
                        "class-level type resolver introspection failed: "
                                + failure.getClass().getSimpleName());
            }
            if (resolver == null) {
                return null;
            }
            if (config instanceof SerializationConfig serialization) {
                Collection<NamedType> subtypes = resolvedSubtypesByClass(reachable, classInfo);
                return liveResolver(
                        reachable.path(),
                        "class",
                        () -> resolver.buildTypeSerializer(serialization, reachable.type(), subtypes),
                        TypeSerializer::getTypeIdResolver);
            }
            if (config instanceof DeserializationConfig deserialization) {
                Collection<NamedType> subtypes = resolvedSubtypesByTypeId(reachable, classInfo);
                return liveDeserResolver(
                        reachable.path(),
                        "class",
                        () -> resolver.buildTypeDeserializer(deserialization, reachable.type(), subtypes));
            }
            throw failure(
                    reachable.path(),
                    "the mapper installs a class-level type resolver for an unrecognised mapper side, so its"
                            + " identity cannot be proven");
        }

        /**
         * Builds one mapper side's type handler and reports the identity of its live type id resolver.
         *
         * @param path the bounded reachable path of the scope being validated
         * @param scope {@code "class"} or {@code "member"}, naming the failure message's scope
         * @param build builds that side's handler from the installed resolver
         * @param idResolverOf reads the built handler's type id resolver
         * @param <H> the handler type of that mapper side
         * @return the live resolver identity, or {@code null} when the resolver builds no handler
         */
        @Nullable
        private <H> InstalledResolver liveResolver(
                String path, String scope, Supplier<H> build, Function<H, TypeIdResolver> idResolverOf) {
            boolean sanctioned;
            String resolverType;
            JsonTypeInfo.Id mechanism;
            try {
                H handler = build.get();
                if (handler == null) {
                    return null;
                }
                TypeIdResolver idResolver = idResolverOf.apply(handler);
                if (idResolver == null) {
                    throw new NoLiveIdResolverException();
                }
                // The diagnostic mechanism and class reads run inside the guard too, so an impostor
                // resolver whose getMechanism() or getClass() throws yields a bounded composition
                // failure rather than a raw runtime exception escaping the walk.
                sanctioned = idResolver instanceof TypeNameIdResolver;
                resolverType = idResolver.getClass().getName();
                mechanism = idResolver.getMechanism();
            } catch (NoLiveIdResolverException noResolver) {
                throw failure(
                        path,
                        "the mapper installs " + scope + "-level type handling reporting no type id resolver, so it"
                                + " cannot be proven to be Jackson's sanctioned name resolver");
            } catch (RuntimeException failure) {
                throw failure(
                        path,
                        scope + "-level type resolver construction failed: "
                                + failure.getClass().getSimpleName());
            }
            return new InstalledResolver(sanctioned, resolverType, mechanism, null, null);
        }

        /**
         * Builds one mapper's deserialization-side type handler and reports both the identity of its
         * live type id resolver and its terminal binding — the exact {@code id → class} map the
         * resolver consults and the default implementation an id-less payload instantiates.
         *
         * <p>This is the deserialization counterpart of {@link #liveResolver}. Where {@code
         * liveResolver} proves only identity — sufficient on the serialization side, which never turns
         * a wire id into a class — the deserialization side additionally reads the resolver's actual
         * {@code _idToType} map and the handler's {@code getDefaultImpl()}, because a builder carrying a
         * custom {@code TypeIdResolver} or a {@code defaultImpl} makes the live binding diverge from
         * both the resolver's self-reported mechanism and the {@code SubtypeResolver}-collected mapping
         * the class-level comparison checks.
         *
         * @param path the bounded reachable path of the scope being validated
         * @param scope {@code "class"} or {@code "member"}, naming the failure message's scope
         * @param build builds the deserialization-side type deserializer from the installed resolver
         * @return the live resolver identity and terminal binding, or {@code null} when the resolver
         *     builds no handler (Jackson's {@code Id.NONE} suppression signal)
         */
        @Nullable
        private InstalledResolver liveDeserResolver(String path, String scope, Supplier<TypeDeserializer> build) {
            boolean sanctioned;
            String resolverType;
            JsonTypeInfo.Id mechanism;
            Map<String, Class<?>> liveIdToClass;
            Class<?> defaultImpl;
            try {
                TypeDeserializer handler = build.get();
                if (handler == null) {
                    return null;
                }
                TypeIdResolver idResolver = handler.getTypeIdResolver();
                if (idResolver == null) {
                    throw new NoLiveIdResolverException();
                }
                sanctioned = idResolver instanceof TypeNameIdResolver;
                resolverType = idResolver.getClass().getName();
                mechanism = idResolver.getMechanism();
                liveIdToClass = sanctioned ? liveIdToClass(path, scope, (TypeNameIdResolver) idResolver) : null;
                defaultImpl = handler.getDefaultImpl();
            } catch (NoLiveIdResolverException noResolver) {
                throw failure(
                        path,
                        "the mapper installs " + scope + "-level type handling reporting no type id resolver, so it"
                                + " cannot be proven to be Jackson's sanctioned name resolver");
            } catch (RuntimeException failure) {
                throw failure(
                        path,
                        scope + "-level type resolver construction failed: "
                                + failure.getClass().getSimpleName());
            }
            return new InstalledResolver(sanctioned, resolverType, mechanism, liveIdToClass, defaultImpl);
        }

        /**
         * Reads the live {@code id → concrete class} map Jackson's {@code TypeNameIdResolver} consults
         * at deserialization time.
         *
         * <p>The resolver's public surface exposes only {@code getDescForKnownTypeIds()} — a formatted
         * {@code String} of ids that omits the class each id binds to and reflects only the
         * deserialization-side {@code _idToType} field — and {@code typeFromId(DatabindContext, id)},
         * which needs a live runtime context this composition-time walk does not have. Neither yields
         * the {@code id → class} pairs the terminal proof requires, so the map is read from the
         * resolver's own {@code _idToType} field: the exact map {@code _typeFromId} consults, and thus
         * the ground truth of which class each wire id becomes. The read is fail-closed — a missing
         * field or a {@code null} map (a Jackson internal change, or a serialization-side resolver
         * reached where a deserialization one was expected) rejects rather than silently trusting an
         * unreadable binding.
         *
         * @param path the bounded reachable path of the scope being validated
         * @param scope {@code "class"} or {@code "member"}, naming the failure message's scope
         * @param resolver the live sanctioned name resolver whose terminal map is read
         * @return an immutable snapshot of the live {@code id → raw class} map
         */
        private Map<String, Class<?>> liveIdToClass(String path, String scope, TypeNameIdResolver resolver) {
            Object rawMap;
            try {
                Field field = TypeNameIdResolver.class.getDeclaredField("_idToType");
                field.setAccessible(true);
                rawMap = field.get(resolver);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                throw failure(
                        path,
                        "the mapper's " + scope + "-level name resolver does not expose its live id-to-class map, so"
                                + " its terminal binding cannot be proven safe");
            }
            if (!(rawMap instanceof Map<?, ?> idToType)) {
                throw failure(
                        path,
                        "the mapper's " + scope + "-level name resolver exposes no live id-to-class map, so its"
                                + " terminal binding cannot be proven safe");
            }
            Map<String, Class<?>> live = new LinkedHashMap<>();
            idToType.forEach((id, javaType) -> live.put(String.valueOf(id), ((JavaType) javaType).getRawClass()));
            return live;
        }

        /**
         * Requires one built deserialization handler's terminal binding to stay inside the validated
         * allowlist: every live {@code id → class} pair, and the default implementation an id-less
         * payload instantiates.
         *
         * <p>This is the sufficient proof the round replaces the identity/mechanism check with. Two
         * escapes it closes both survive an {@code instanceof TypeNameIdResolver} identity check and a
         * matching {@code SubtypeResolver} mapping: a builder whose {@code _customIdResolver} is a real
         * {@code TypeNameIdResolver} constructed from a poisoned subtype collection binds a wire id to a
         * base-subtype the finite {@code @JsonSubTypes} allowlist never listed; and a {@code defaultImpl}
         * lets an id-less payload instantiate a class no id maps to. The allowlist entries are exactly
         * the subtypes the walk enqueues and proves, so requiring the live binding to be a subset of it
         * makes the acceptance criterion terminal — what Jackson will actually instantiate is proven ⊆
         * the validated graph.
         *
         * <p>A {@code defaultImpl} equal to the polymorphic base itself is accepted: an id-less payload
         * then instantiates the base, which is the very reachable type being visited and already proven,
         * and an abstract base fails at runtime rather than escaping. Jackson's {@code Void} "no default"
         * sentinel is likewise treated as absent.
         *
         * @param path the bounded reachable path of the scope being validated
         * @param scope {@code "class"} or {@code "member"}, naming the failure message's scope
         * @param installed the built deserialization handler's identity and terminal binding
         * @param allowlist the finite explicit {@code @JsonSubTypes} allowlist the walk enqueues
         * @param baseClass the polymorphic base's raw class, an accepted {@code defaultImpl} target
         */
        private void requireTerminalDeserBinding(
                String path,
                String scope,
                InstalledResolver installed,
                Map<String, Class<?>> allowlist,
                Class<?> baseClass) {
            Map<String, Class<?>> liveIdToClass = installed.liveIdToClass();
            if (liveIdToClass != null) {
                liveIdToClass.forEach((id, liveClass) -> {
                    Class<?> allowed = allowlist.get(id);
                    if (allowed == null) {
                        throw failure(
                                path,
                                "the mapper installs " + scope + "-level name type handling whose live type id '" + id
                                        + "' resolves to " + liveClass.getName()
                                        + ", which the finite @JsonSubTypes allowlist never lists and the walk never"
                                        + " proved; only ids bound to an allowlisted subtype are an accepted mechanism");
                    }
                    if (!allowed.equals(liveClass)) {
                        throw failure(
                                path,
                                "the mapper installs " + scope + "-level name type handling whose live type id '" + id
                                        + "' resolves to " + liveClass.getName()
                                        + " but the @JsonSubTypes allowlist binds that id to " + allowed.getName());
                    }
                });
            }
            Class<?> defaultImpl = installed.defaultImpl();
            if (defaultImpl != null
                    && !Void.class.equals(defaultImpl)
                    && !defaultImpl.equals(baseClass)
                    && !allowlist.containsValue(defaultImpl)) {
                throw failure(
                        path,
                        scope + "-level @JsonTypeInfo declares defaultImpl " + defaultImpl.getName()
                                + ", which the finite @JsonSubTypes allowlist never lists, so an id-less payload would"
                                + " instantiate a type the walk never proved");
            }
        }

        /** Resolves the serialization-facing subtype mapping the handler build feeds on. */
        private Collection<NamedType> resolvedSubtypesByClass(ReachableType reachable, AnnotatedClass classInfo) {
            try {
                return subtypesByClass(classInfo);
            } catch (RuntimeException failure) {
                throw failure(
                        reachable.path(),
                        "class-level subtype resolution failed: "
                                + failure.getClass().getSimpleName());
            }
        }

        /** Resolves the deserialization-facing subtype mapping the handler build feeds on. */
        private Collection<NamedType> resolvedSubtypesByTypeId(ReachableType reachable, AnnotatedClass classInfo) {
            try {
                return subtypesByTypeId(classInfo);
            } catch (RuntimeException failure) {
                throw failure(
                        reachable.path(),
                        "class-level subtype resolution failed: "
                                + failure.getClass().getSimpleName());
            }
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
                TypeResolverBuilder<?> propertyResolver =
                        introspector.findPropertyTypeResolver(config, member, propertyType);
                if (propertyResolver != null) {
                    requireClosedMemberPolymorphism(
                            propertyPath, member, propertyType, propertyResolver, config, introspector);
                }
                if (hasContent(propertyType)) {
                    TypeResolverBuilder<?> contentResolver =
                            introspector.findPropertyContentTypeResolver(config, member, propertyType);
                    if (contentResolver != null) {
                        requireClosedMemberPolymorphism(
                                propertyPath + " -> <content>",
                                member,
                                propertyType.getContentType(),
                                contentResolver,
                                config,
                                introspector);
                    }
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
         * <p>The gate reads the resolver the mapper actually builds from the member-declared builder,
         * not only the reported {@code Id.NAME} metadata: the identical {@code getMechanism()} spoof
         * available at class scope reopens here on a bean property, so only a live
         * {@link TypeNameIdResolver} — the type the legitimate {@code @JsonTypeInfo(use = NAME)} member
         * path builds on both sides — is accepted, and an impostor reporting {@code Id.NAME} while its
         * {@code typeFromId} escapes the allowlist is rejected.
         *
         * @param path the bounded reachable path of this member scope
         * @param member the annotated member declaring the type information
         * @param baseType the type the member-declared resolver is installed for
         * @param memberResolver the resolver builder Jackson installs for this member scope
         * @param config the mapper side whose introspection declared the resolver
         * @param introspector the introspector that side actually uses
         */
        private void requireClosedMemberPolymorphism(
                String path,
                AnnotatedMember member,
                JavaType baseType,
                TypeResolverBuilder<?> memberResolver,
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
            InstalledResolver installed =
                    requireSanctionedMemberResolver(path, member, baseType, memberResolver, config);
            Map<String, Class<?>> allowlist =
                    declaredAllowlist(path, memberSubtypes(member, baseType, config, introspector));
            Class<?> baseClass = baseType.getRawClass();
            requireTerminalDeserBinding(path, "member", installed, allowlist, baseClass);
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

        /**
         * Requires the live resolver a member-declared builder installs to be Jackson's sanctioned
         * {@link TypeNameIdResolver}, per the mapper side being validated.
         *
         * <p>The handler is built exactly as Jackson's serializer or deserializer factory builds it —
         * a type serializer on the serialization side, a type deserializer on the deserialization side
         * — from the member's own resolver builder and its resolved subtype mapping, so an impostor
         * reporting {@code Id.NAME} from {@code getMechanism()} is caught by the resolver type it
         * actually is rather than the mechanism it claims.
         *
         * @param path the bounded reachable path of this member scope
         * @param member the annotated member declaring the type information
         * @param baseType the type the member-declared resolver is installed for
         * @param memberResolver the resolver builder Jackson installs for this member scope
         * @param config the mapper side whose introspection declared the resolver
         * @return the built handler's identity and terminal binding, for the caller's terminal check
         */
        private InstalledResolver requireSanctionedMemberResolver(
                String path,
                AnnotatedMember member,
                JavaType baseType,
                TypeResolverBuilder<?> memberResolver,
                MapperConfig<?> config) {
            InstalledResolver installed;
            if (config instanceof SerializationConfig serialization) {
                Collection<NamedType> subtypes = subtypesByClass(member, baseType);
                installed = liveResolver(
                        path,
                        "member",
                        () -> memberResolver.buildTypeSerializer(serialization, baseType, subtypes),
                        TypeSerializer::getTypeIdResolver);
            } else if (config instanceof DeserializationConfig deserialization) {
                Collection<NamedType> subtypes = subtypesByTypeId(member, baseType);
                installed = liveDeserResolver(
                        path,
                        "member",
                        () -> memberResolver.buildTypeDeserializer(deserialization, baseType, subtypes));
            } else {
                throw failure(
                        path,
                        "the mapper installs a member-level type resolver for an unrecognised mapper side, so its"
                                + " identity cannot be proven");
            }
            if (installed == null) {
                throw failure(
                        path,
                        "the mapper installs member-level type handling that builds no live resolver, so it cannot"
                                + " be proven to be Jackson's sanctioned name resolver");
            }
            if (!installed.sanctioned()) {
                throw failure(path, unsafeInstalledResolverReason("member", installed));
            }
            return installed;
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
            Type declared = declaredGenericType(propertyPath, member);
            if (declared != null) {
                rejectRawUsage(propertyPath, declared);
            }
        }

        /**
         * Walks a declared member type, rejecting a raw use of a parameterizable class at any depth.
         *
         * <p>An array class is descended into rather than inspected directly: a {@code List[]}
         * declaration is a plain array {@code Class} — only {@code List<String>[]}, whose component is
         * itself parameterized, reaches reflection as a {@code GenericArrayType} — so the erased
         * element declaration is invisible to anything that does not read the component type.
         */
        private void rejectRawUsage(String propertyPath, Type declared) {
            switch (declared) {
                case Class<?> rawCandidate -> {
                    if (rawCandidate.isArray()) {
                        rejectRawUsage(propertyPath, rawCandidate.getComponentType());
                    } else if (rawCandidate.getTypeParameters().length > 0) {
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
         * @param propertyPath the bounded reachable path of this member
         * @param member the member Jackson chose as the property's primary one
         * @return the declared {@code Type}, or {@code null} when the member exposes no unambiguous one
         */
        @Nullable
        private Type declaredGenericType(String propertyPath, AnnotatedMember member) {
            return switch (member) {
                case AnnotatedField field -> field.getAnnotated().getGenericType();
                case AnnotatedMethod method -> declaredMethodType(method.getAnnotated());
                case AnnotatedParameter parameter -> declaredParameterType(propertyPath, parameter);
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
         * and enum constructors, which misaligns the index against {@code getParameterCount()}. An
         * ambiguous index cannot be read as a declaration, and skipping the member silently would make
         * the whole resolved-declaration rule fail open for that property — so the ambiguity is a
         * bounded composition failure naming the creator, per the closed algorithm's rule 1.
         *
         * <p>No Jackson-reachable property reaches this: records, static nested types and top-level
         * types all align, while Jackson drops the implicit-parameter creators of inner, local and
         * enum types entirely, and an enum is a traversal leaf before bean introspection runs. The
         * rejection is therefore a fail-closed guard for a shape Jackson's own creator detection does
         * not currently produce, not a rejection of a supported DTO.
         *
         * @param propertyPath the bounded reachable path of this member
         * @param parameter the annotated creator parameter
         * @return its declared {@code Type}
         */
        private Type declaredParameterType(String propertyPath, AnnotatedParameter parameter) {
            if (!(parameter.getOwner().getMember() instanceof Executable executable)) {
                throw failure(
                        propertyPath,
                        "creator parameter " + parameter.getIndex()
                                + " exposes no reflective declaration, so its declared type cannot be resolved");
            }
            Type[] declared = executable.getGenericParameterTypes();
            int index = parameter.getIndex();
            if (declared.length != executable.getParameterCount() || index >= declared.length) {
                throw failure(
                        propertyPath,
                        "creator of " + executable.getDeclaringClass().getName() + " declares "
                                + executable.getParameterCount() + " parameters but " + declared.length
                                + " generic ones, so parameter " + index + " has no unambiguous declared type");
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

        /**
         * Rejects raw, wildcard, variable and otherwise unresolved declared root types.
         *
         * <p>An array class is descended into for the same reason the member walk descends into one:
         * a raw {@code List[]} root is a plain array {@code Class}, so its erased element declaration
         * is only visible through the component type.
         */
        private void requireResolved(Type type, String rootPath) {
            switch (type) {
                case Class<?> rawCandidate -> {
                    if (rawCandidate.isArray()) {
                        requireResolved(rawCandidate.getComponentType(), rootPath);
                    } else if (rawCandidate.getTypeParameters().length > 0) {
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

    /**
     * Signals that a built type handler exposed no live type id resolver, so its identity cannot be
     * proven. It is distinguished from a resolver-construction failure so each yields its own bounded
     * composition message, and it is raised and caught only within a single handler build.
     */
    private static final class NoLiveIdResolverException extends RuntimeException {
        private NoLiveIdResolverException() {
            super(null, null, false, false);
        }
    }

    /**
     * The identity and terminal binding of a live type id resolver one mapper side actually installs.
     *
     * <p>The first three components prove the resolver's <em>identity</em> — a non-{@code
     * TypeNameIdResolver} is rejected outright. The last two prove the resolver's <em>terminal
     * behavior</em> on the deserialization side, where a wire-chosen id becomes a class: {@code
     * liveIdToClass} is the exact {@code id → class} map Jackson's {@code TypeNameIdResolver} consults
     * ({@code _idToType}), and {@code defaultImpl} is the class an id-less payload instantiates. Both
     * are {@code null} on the serialization side and whenever the resolver is not the sanctioned name
     * resolver, because neither terminal fact is meaningful there.
     *
     * @param sanctioned whether the resolver is Jackson's own {@code TypeNameIdResolver}
     * @param resolverType the resolver's concrete class name, for the rejection message
     * @param mechanism the mechanism the resolver reports, which an impostor may spoof as {@code Id.NAME}
     * @param liveIdToClass the deserialization-side live {@code id → concrete class} map Jackson uses,
     *     or {@code null} on the serialization side or for an unsanctioned resolver
     * @param defaultImpl the deserialization-side default implementation an id-less payload
     *     instantiates, or {@code null} when none is installed
     */
    private record InstalledResolver(
            boolean sanctioned,
            String resolverType,
            JsonTypeInfo.Id mechanism,
            @Nullable Map<String, Class<?>> liveIdToClass,
            @Nullable Class<?> defaultImpl) {}
}
