// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.MemberScope;
import com.github.victools.jsonschema.generator.MethodScope;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import com.github.victools.jsonschema.module.swagger2.Swagger2Module;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonSchemaTypeOverride.Direction;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generates deterministic, canonical Draft 2020-12 JSON Schema documents from a resolved Java
 * {@link Type} using Victools, configured with the Jackson, Jakarta Validation, and Swagger 2
 * annotation modules.
 *
 * <p>An instance is constructed through exactly one of three static factories, each selecting how
 * Victools discovers Jackson properties and which profile-declared schema-type overrides apply:
 *
 * <ul>
 *   <li>{@link #withVictoolsDefaults()} — the current REST-compatible mode. Victools is
 *       constructed without a supplied {@code ObjectMapper}, using its own default mapper. No
 *       profile schema-type override is applied.
 *   <li>{@link #forInputProfile(JsonMapperProfile)} — Victools property discovery is driven by
 *       {@code profile.mapper()}, and only the profile's {@code INPUT}- and {@code BOTH}-direction
 *       {@code JsonSchemaTypeOverride} declarations apply.
 *   <li>{@link #forOutputProfile(JsonMapperProfile)} — the output-direction counterpart: Victools
 *       property discovery is driven by {@code profile.mapper()}, and only the profile's {@code
 *       OUTPUT}- and {@code BOTH}-direction overrides apply.
 * </ul>
 *
 * <p>All three modes install the same Victools modules and options: the Jackson module, the
 * Jakarta Validation module with {@code NOT_NULLABLE_FIELD_IS_REQUIRED} and {@code
 * INCLUDE_PATTERN_EXPRESSIONS}, and the Swagger 2 module. Only the mapper source and the selected
 * profile overrides differ between modes.
 *
 * <p><strong>Which properties the input direction describes.</strong>
 * {@link #forInputProfile(JsonMapperProfile)} describes a walked property when Jackson reports it
 * deserializable or it has a backing field, and its access is not {@code READ_ONLY}. A backing field
 * counts because Jackson populates a private field through reflection wherever the mapper infers
 * property mutators — its default, and the setting every built-in profile leaves alone — so a
 * getter-only property with a field behind it is bound and is described. A builder type is filled
 * through its builder rather than through the field, so it is described only when its properties are
 * also visible to introspection: a Lombok {@code @Builder @Jacksonized} type needs {@code @Getter}.
 * The backing storage of an any-setter or an any-getter is never described as a named property, and
 * it is identified by member alone — a field annotated {@code @JsonAnySetter}, the record component
 * whose field that is, a field annotated {@code @JsonAnyGetter}, and the field a method
 * {@code @JsonAnyGetter} returns — so a real property is never hidden because its name matches one an
 * accessor method implies. For a type Jackson deserializes as map-like or collection-like the
 * any-setter is ignored, as Jackson ignores it. A property marked {@code @JsonIgnore} or read-only
 * stays absent, and a write-only property is described with {@code writeOnly}. The output direction
 * is unaffected by this rule: {@link #forOutputProfile(JsonMapperProfile)} describes a property
 * Jackson reports serializable whose access is not {@code WRITE_ONLY}.
 *
 * <p>A profile's declared {@code JsonSchemaTypeOverride}s narrow how the generator represents an
 * exact Java class: the override fragment defines the baseline wire contract for that class, and
 * property-level Swagger schema metadata or applicable Jakarta constraints may further narrow —
 * never replace or broaden — that baseline. Where both the profile and a property contribute the
 * same single-valued keyword, generation represents their conjunction explicitly rather than
 * relying on registration or keyword-merge order; a detectable structural conflict fails
 * generation with {@link JsonSchemaGenerationException} instead of producing an unsatisfiable
 * contract. A non-default {@code implementation = ...} redirect on a property whose declared type
 * graph carries an effective override also fails generation, because the pinned Victools version
 * silently drops the override fragment once {@code implementation} redirects the member's
 * resolved type. Every form the Swagger module reads that redirect from is covered — a direct
 * {@code @Schema}, {@code @ArraySchema(schema = ...)}, and {@code @ArraySchema(arraySchema = ...)} —
 * and the declared type is resolved against its declaring context, so a member inherited from a
 * generic supertype is checked against the class the subtype binds it to.
 *
 * <p>A Jakarta constraint still targets the <em>materialized Java value</em>: a numeric-domain
 * keyword ({@code minimum}, {@code maximum}, {@code exclusiveMinimum}, {@code exclusiveMaximum}) that
 * an override's effective wire type can no longer satisfy — for example {@code @DecimalMin} on a
 * {@code BigDecimal} an override republishes as a string — is suppressed from the published document
 * by {@link NumericDomainKeywordFilter} rather than advertised against a type it cannot apply to; Bean
 * Validation still enforces it against the Java value.
 *
 * <p>{@code forInputProfile(null)} and {@code forOutputProfile(null)} throw {@link
 * NullPointerException} naming {@code "profile"}. A null profile id, mapper, override list,
 * override, or override member fails construction with a bounded {@link
 * JsonSchemaGenerationException} that names only the profile id when one is available. Validation
 * of the profile's override declarations is whole-declaration: the full list is expanded to
 * {@code (class, INPUT)} / {@code (class, OUTPUT)} keys and checked for duplicates before
 * direction filtering, so a profile carrying any duplicate effective mapping is rejected by both
 * factories regardless of which direction the conflict affects.
 *
 * <p>The generator never mutates the supplied profile, mapper, override list, or fragments; the
 * caller must finish profile and mapper construction before generator construction and must not
 * mutate the mapper afterward.
 *
 * <p>Calls on one instance are safe from multiple threads: the complete generation and
 * canonicalization operation is serialized per instance through an instance-local lock. Different
 * instances share no lock and may generate concurrently. This class exposes no Victools type in
 * its public signature.
 *
 * <p>A failed call leaves the instance fully reusable. Any abnormal exit from the underlying
 * generator restores the per-generation provider state that generator resets on its own success
 * path, so a rejected type never changes what a later call on the same instance publishes — see
 * {@link #restoreProviderStateAfterAbortedGeneration(Throwable)}.
 */
public final class AnnotationJsonSchemaGenerator {

    /**
     * The one dialect this class generates for. Both public factories pin it, and the package-private
     * injection seam enforces it: the post-generation walks' subschema-position allowlist names this
     * dialect's keywords, and Victools renames several of them under an older dialect.
     */
    private static final SchemaVersion REQUIRED_SCHEMA_VERSION = SchemaVersion.DRAFT_2020_12;

    /** The configured Victools generator; its configuration is fixed at construction. */
    private final SchemaGenerator generator;

    /**
     * Whether {@link NumericDomainKeywordFilter} runs on every generated document from this instance.
     *
     * <p>{@code true} only for a profile-aware generator ({@link #forInputProfile(JsonMapperProfile)}
     * / {@link #forOutputProfile(JsonMapperProfile)}) whose profile declares at least one applicable
     * schema-type override — the only case in which a member's declared numeric Java type can end up
     * with a non-numeric effective wire type (PRD §6.2 wire-honesty). {@code
     * withVictoolsDefaults()} never substitutes a wire type for a Java type, so this is always {@code
     * false} for that mode.
     */
    private final boolean suppressInapplicableNumericKeywords;

    /**
     * The instance-local lock serializing the complete generate-and-canonicalize operation. It is a
     * plain private object so no caller can participate in — or deadlock against — this instance's
     * lock, and it is never shared between instances.
     */
    private final Object lock = new Object();

    /**
     * Wraps an already-configured Victools generator, applying no numeric-domain keyword suppression.
     *
     * <p>Package-private on purpose: it is the seam a same-package test uses to inject an
     * instrumented {@link SchemaGenerator} subclass — for instance one that blocks inside {@code
     * generateSchema} — so the per-instance serialization contract can be proven without exposing a
     * Victools type on the public surface. Application code constructs generators exclusively
     * through {@link #withVictoolsDefaults()}, {@link #forInputProfile(JsonMapperProfile)}, and
     * {@link #forOutputProfile(JsonMapperProfile)}, all of which delegate here or to the two-argument
     * constructor.
     *
     * @param generator the configured Victools generator this instance owns for its lifetime; must be
     *                  configured for {@code DRAFT_2020_12}
     * @throws NullPointerException          if {@code generator} is {@code null}
     * @throws JsonSchemaGenerationException if {@code generator} is configured for any dialect other
     *                                        than {@code DRAFT_2020_12}
     */
    AnnotationJsonSchemaGenerator(SchemaGenerator generator) {
        this(generator, false);
    }

    /**
     * Wraps an already-configured Victools generator, optionally applying
     * {@link NumericDomainKeywordFilter} to every document this instance generates.
     *
     * <p>The supplied generator must be configured for {@code DRAFT_2020_12}. This is the seam where
     * that premise is enforced: the public factories pin the dialect themselves, but an injected
     * generator is arbitrary, and the post-generation walks are only correct for the pinned dialect.
     *
     * @param generator                           the configured Victools generator this instance owns
     *                                             for its lifetime
     * @param suppressInapplicableNumericKeywords  whether to run {@link NumericDomainKeywordFilter}
     *                                             after generation and before canonicalization
     * @throws NullPointerException          if {@code generator} is {@code null}
     * @throws JsonSchemaGenerationException if {@code generator} is configured for any dialect other
     *                                        than {@code DRAFT_2020_12}
     */
    AnnotationJsonSchemaGenerator(SchemaGenerator generator, boolean suppressInapplicableNumericKeywords) {
        this.generator = requirePinnedDialect(generator);
        this.suppressInapplicableNumericKeywords = suppressInapplicableNumericKeywords;
    }

    /**
     * Rejects a Victools generator configured for any dialect but {@link #REQUIRED_SCHEMA_VERSION}.
     *
     * <p>The module's two post-generation walks descend a closed allowlist of Draft 2020-12 subschema
     * positions. Victools spells two of those positions differently under an older dialect — {@code
     * $defs} becomes {@code definitions} and {@code dependentSchemas} becomes {@code dependencies},
     * neither of which the allowlist names — so under {@code DRAFT_6} or {@code DRAFT_7} the whole
     * definition graph would silently escape both walks: an unsatisfiable definition would publish,
     * and an inapplicable numeric keyword would survive. Failing construction states that constraint
     * where it can still be acted on, rather than degrading generation invisibly.
     *
     * @param generator the generator to check; must not be {@code null}
     * @return the same generator, when it is configured for the pinned dialect
     * @throws NullPointerException          if {@code generator} is {@code null}
     * @throws JsonSchemaGenerationException if the generator is configured for another dialect
     */
    private static SchemaGenerator requirePinnedDialect(SchemaGenerator generator) {
        Objects.requireNonNull(generator, "generator");
        SchemaGeneratorConfig config = generator.getConfig();
        SchemaVersion version = config == null ? null : config.getSchemaVersion();
        if (version != REQUIRED_SCHEMA_VERSION) {
            throw Diagnostics.failure(
                    "the supplied JSON Schema generator must be configured for " + REQUIRED_SCHEMA_VERSION.name()
                            + ": the post-generation subschema-position allowlist names that dialect's keywords,"
                            + " and an older dialect renames them ($defs, dependentSchemas); the supplied generator"
                            + " is configured for " + version,
                    null);
        }
        return generator;
    }

    /**
     * Constructs a generator that reproduces the current REST-compatible Victools configuration:
     * {@code SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)}
     * without a supplied {@code ObjectMapper}. No profile schema-type override is applied in this
     * mode.
     *
     * @return a generator configured with Victools' own default mapper
     */
    public static AnnotationJsonSchemaGenerator withVictoolsDefaults() {
        return new AnnotationJsonSchemaGenerator(
                build(new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)));
    }

    /**
     * Constructs a generator whose Victools property discovery is driven by {@code
     * profile.mapper()} and whose applied schema-type overrides are the profile's {@code INPUT}-
     * and {@code BOTH}-direction declarations.
     *
     * @param profile the resolved JSON mapper profile whose mapper and input-applicable overrides
     *                drive generation; must not be {@code null}
     * @return a generator configured for the profile's input direction
     * @throws NullPointerException         if {@code profile} is {@code null}
     * @throws JsonSchemaGenerationException if the profile's id, mapper, override list, or an
     *                                        override declaration is invalid, or if the profile
     *                                        declares a duplicate effective override mapping for
     *                                        this direction
     */
    public static AnnotationJsonSchemaGenerator forInputProfile(JsonMapperProfile profile) {
        return forProfile(profile, Direction.INPUT);
    }

    /**
     * Constructs a generator whose Victools property discovery is driven by {@code
     * profile.mapper()} and whose applied schema-type overrides are the profile's {@code OUTPUT}-
     * and {@code BOTH}-direction declarations.
     *
     * @param profile the resolved JSON mapper profile whose mapper and output-applicable overrides
     *                drive generation; must not be {@code null}
     * @return a generator configured for the profile's output direction
     * @throws NullPointerException         if {@code profile} is {@code null}
     * @throws JsonSchemaGenerationException if the profile's id, mapper, override list, or an
     *                                        override declaration is invalid, or if the profile
     *                                        declares a duplicate effective override mapping for
     *                                        this direction
     */
    public static AnnotationJsonSchemaGenerator forOutputProfile(JsonMapperProfile profile) {
        return forProfile(profile, Direction.OUTPUT);
    }

    /**
     * Builds a profile-aware generator for one direction: Victools property discovery is driven by
     * the profile's mapper, and the profile's declarations applying in that direction are installed
     * as an internal custom definition provider.
     *
     * @param profile   the caller-supplied profile; must not be {@code null}
     * @param direction the direction being constructed
     * @return the configured generator
     * @throws NullPointerException          if {@code profile} is {@code null}
     * @throws JsonSchemaGenerationException if the profile's declarations are invalid
     */
    private static AnnotationJsonSchemaGenerator forProfile(JsonMapperProfile profile, Direction direction) {
        Objects.requireNonNull(profile, "profile");
        ValidatedProfile validated = ValidatedProfile.forDirection(profile, direction);

        SchemaGeneratorConfigBuilder builder = new SchemaGeneratorConfigBuilder(
                validated.mapper(), SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON);
        boolean hasOverrides = validated.hasOverrides();
        if (hasOverrides) {
            builder.forTypesInGeneral().withCustomDefinitionProvider(new ProfileOverrideDefinitionProvider(validated));
            // Registered before the annotation modules, so the guard is consulted for every member
            // regardless of what a module's own member-scope provider decides to supply.
            builder.forFields().withCustomDefinitionProvider(new SchemaImplementationGuard<FieldScope>(validated));
            builder.forMethods().withCustomDefinitionProvider(new SchemaImplementationGuard<MethodScope>(validated));
        }
        return new AnnotationJsonSchemaGenerator(
                build(builder, new ProfilePropertyNameResolver(validated.mapper(), direction)), hasOverrides);
    }

    /**
     * Installs the modules and options every construction mode shares — the Jackson module, the
     * Jakarta Validation module with {@code NOT_NULLABLE_FIELD_IS_REQUIRED} and
     * {@code INCLUDE_PATTERN_EXPRESSIONS}, and the Swagger 2 module — and builds the generator.
     *
     * <p>{@code OptionPreset.PLAIN_JSON}'s own options, including {@code ALLOF_CLEANUP_AT_THE_END},
     * are deliberately left untouched in all three modes: only the mapper source and the selected
     * profile overrides differ between modes.
     *
     * @param builder the mode-specific config builder
     * @return the configured Victools generator
     */
    private static SchemaGenerator build(SchemaGeneratorConfigBuilder builder) {
        return build(builder, null);
    }

    /**
     * Installs the shared annotation modules and, for profile-aware generation, the selected mapper's
     * actual input- or output-direction property names.
     *
     * <p>Victools' Jackson module reads Jackson annotations but does not project an
     * {@link ObjectMapper}-level property naming strategy. Registering the mapper-derived resolver
     * after the modules makes the generated schema use the same names that the selected mapper
     * materializes or serializes.
     */
    private static SchemaGenerator build(
            SchemaGeneratorConfigBuilder builder, ProfilePropertyNameResolver propertyNames) {
        builder.with(new JacksonModule())
                .with(new JakartaValidationModule(
                        JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                        JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS))
                .with(new Swagger2Module());
        if (propertyNames != null) {
            builder.forFields()
                    .withIgnoreCheck(propertyNames::isIgnored)
                    .withPropertyNameOverrideResolver(propertyNames::resolve);
            builder.forMethods()
                    .withIgnoreCheck(propertyNames::isIgnored)
                    .withPropertyNameOverrideResolver(propertyNames::resolve);
        }
        return new SchemaGenerator(builder.build());
    }

    /** Mapper-introspected wire names keyed by the exact member Victools is publishing. */
    private static final class ProfilePropertyNameResolver {
        private final ObjectMapper mapper;
        private final Direction direction;

        private record PropertyMetadata(String wireName, boolean visible) {}

        /**
         * @param byMember                  wire names keyed by the exact member Victools publishes
         * @param byInternalName            the same, keyed by internal and wire name, as a fallback
         * @param anyAccessorMembers        the any-setter and any-getter members themselves
         * @param anyAccessorBackingMembers the members that store what those accessors collect
         */
        private record PropertyNames(
                Map<Member, PropertyMetadata> byMember,
                Map<String, PropertyMetadata> byInternalName,
                Set<Member> anyAccessorMembers,
                Set<Member> anyAccessorBackingMembers) {}

        /** The verdict for an any-accessor or its backing storage: never a named property. */
        private static final PropertyMetadata HIDDEN_ANY_ACCESSOR_BACKING = new PropertyMetadata(null, false);

        private final ClassValue<PropertyNames> namesByType = new ClassValue<>() {
            @Override
            protected PropertyNames computeValue(Class<?> type) {
                return introspect(type);
            }
        };

        private ProfilePropertyNameResolver(ObjectMapper mapper, Direction direction) {
            this.mapper = mapper;
            this.direction = direction;
        }

        private String resolve(MemberScope<?, ?> scope) {
            PropertyMetadata property = metadata(scope);
            return property == null ? null : property.wireName();
        }

        private boolean isIgnored(MemberScope<?, ?> scope) {
            PropertyMetadata property = metadata(scope);
            return property != null && !property.visible();
        }

        private PropertyMetadata metadata(MemberScope<?, ?> scope) {
            if (scope.isFakeContainerItemScope()) {
                return null;
            }
            PropertyNames names = namesByType.get(scope.getDeclaringType().getErasedType());
            if (names.anyAccessorMembers().contains(scope.getRawMember())
                    || names.anyAccessorBackingMembers().contains(scope.getRawMember())) {
                // An any-accessor and the storage it fills are never named properties: the keys they
                // collect are extra keys, not a member of the object's property set. Matched by
                // member and never by a name an accessor merely implies, so a real property that
                // happens to share an accessor's implied name stays described.
                return HIDDEN_ANY_ACCESSOR_BACKING;
            }
            PropertyMetadata byMember = names.byMember().get(scope.getRawMember());
            return byMember != null ? byMember : names.byInternalName().get(scope.getName());
        }

        private PropertyNames introspect(Class<?> type) {
            JavaType javaType = mapper.getTypeFactory().constructType(type);
            BeanDescription description = direction == Direction.INPUT
                    ? mapper.getDeserializationConfig().introspect(javaType)
                    : mapper.getSerializationConfig().introspect(javaType);
            Map<Member, PropertyMetadata> members = new HashMap<>();
            Map<String, PropertyMetadata> internalNames = new HashMap<>();
            Set<Member> anyAccessors = new HashSet<>();
            Set<Member> anyBacking = new HashSet<>();
            if (direction == Direction.INPUT) {
                collectAnyAccessorMembers(type, javaType, description, anyAccessors, anyBacking);
            }
            for (BeanPropertyDefinition property : description.findProperties()) {
                JsonProperty.Access access = propertyAccess(property);
                boolean visible = direction == Direction.INPUT
                        // A private field with no setter is still bound: Jackson populates it through
                        // reflection wherever the mapper infers property mutators, which is its
                        // default and what every built-in profile leaves alone. So a backing field
                        // makes a walked property bound, whether or not Jackson reports a mutator.
                        ? (property.couldDeserialize() || property.hasField())
                                && access != JsonProperty.Access.READ_ONLY
                        : property.couldSerialize() && access != JsonProperty.Access.WRITE_ONLY;
                PropertyMetadata metadata = new PropertyMetadata(property.getName(), visible);
                internalNames.put(property.getInternalName(), metadata);
                internalNames.put(property.getName(), metadata);
                if (property.getField() != null) {
                    members.put(property.getField().getMember(), metadata);
                }
                if (property.getGetter() != null) {
                    members.put(property.getGetter().getMember(), metadata);
                }
                if (property.getSetter() != null) {
                    members.put(property.getSetter().getMember(), metadata);
                }
            }
            return new PropertyNames(
                    Map.copyOf(members), Map.copyOf(internalNames), Set.copyOf(anyAccessors), Set.copyOf(anyBacking));
        }

        /**
         * Collects the any-setter and any-getter members of a type and the members that store what
         * they collect, so neither is ever described as a named property.
         *
         * <p>Storage is identified by member and never by a name derived from an accessor: a field
         * annotated {@code @JsonAnySetter}, the record component whose field that is, a field
         * annotated {@code @JsonAnyGetter}, and the field Jackson links to a method
         * {@code @JsonAnyGetter}, where the field's type can be that getter's return value. A
         * property whose name an accessor merely implies — {@code attribute} beside an any-setter
         * {@code setAttribute(String, Object)} — is a real property and stays described.
         *
         * <p>Jackson binds a map-like or collection-like type as a container and never routes input
         * to its any-setter, so for such a type the any-setter is ignored here as Jackson ignores it.
         *
         * @param type        the erased type being introspected
         * @param javaType    its resolved Jackson type
         * @param description the type's deserialization introspection
         * @param accessors   collects the any-accessor members themselves
         * @param backing     collects the members storing what those accessors collect
         */
        private static void collectAnyAccessorMembers(
                Class<?> type,
                JavaType javaType,
                BeanDescription description,
                Set<Member> accessors,
                Set<Member> backing) {
            boolean boundAsContainer = javaType.isMapLikeType() || javaType.isCollectionLikeType();
            AnnotatedMember anySetter = boundAsContainer ? null : description.findAnySetterAccessor();
            AnnotatedMember anyGetter = description.findAnyGetter();
            for (AnnotatedMember any : new AnnotatedMember[] {anySetter, anyGetter}) {
                if (any != null && any.getMember() != null) {
                    accessors.add(any.getMember());
                }
            }
            if (anySetter instanceof AnnotatedField field) {
                backing.add(field.getMember());
                if (type.isRecord() && field.getDeclaringClass() == type) {
                    for (RecordComponent component : type.getRecordComponents()) {
                        if (component.getName().equals(field.getName())) {
                            backing.add(component.getAccessor());
                        }
                    }
                }
            }
            if (anyGetter instanceof AnnotatedField field) {
                backing.add(field.getMember());
            } else if (anyGetter instanceof AnnotatedMethod getter) {
                for (BeanPropertyDefinition property : description.findProperties()) {
                    AnnotatedMember linkedGetter = property.getGetter();
                    AnnotatedField linkedField = property.getField();
                    if (linkedGetter != null
                            && linkedField != null
                            && linkedGetter.getMember().equals(getter.getMember())
                            && getter.getRawType().isAssignableFrom(linkedField.getRawType())) {
                        backing.add(linkedField.getMember());
                    }
                }
            }
        }

        private JsonProperty.Access propertyAccess(BeanPropertyDefinition property) {
            AnnotationIntrospector introspector = direction == Direction.INPUT
                    ? mapper.getDeserializationConfig().getAnnotationIntrospector()
                    : mapper.getSerializationConfig().getAnnotationIntrospector();
            AnnotatedMember[] members = {
                property.getGetter(), property.getSetter(), property.getField(), property.getConstructorParameter()
            };
            for (AnnotatedMember member : members) {
                if (member == null) {
                    continue;
                }
                JsonProperty.Access access = introspector.findPropertyAccess(member);
                if (access != null && access != JsonProperty.Access.AUTO) {
                    return access;
                }
            }
            return JsonProperty.Access.AUTO;
        }
    }

    /**
     * Generates a fresh, canonical Draft 2020-12 JSON Schema document for the given resolved
     * {@link Type}.
     *
     * <p>The accepted {@code Type} grammar is a non-null {@link Class}, including primitive and
     * array classes and a raw generic {@code Class}; a {@link ParameterizedType} whose optional
     * owner, raw type, and arguments are recursively resolved; and a {@link GenericArrayType}
     * whose component type is recursively resolved. {@code null}, a {@link
     * java.lang.reflect.TypeVariable}, a {@link java.lang.reflect.WildcardType}, any nested
     * occurrence of either unresolved form, and an unknown custom {@code Type} implementation are
     * rejected.
     *
     * <p>The returned document has every object member whose key is {@code default} and whose
     * value is exactly the string {@code ##default} removed, recursively, and every object's keys
     * recursively ordered by {@link String#compareTo(String)} UTF-16 code-unit order; arrays are
     * never reordered. The document is emitted as compact JSON through a generator-owned neutral
     * writer, never through the profile mapper, so the valid-document guarantee is independent of
     * payload-mapper serialization features.
     *
     * <p>Equal resolved types, annotations, construction mode, mapper configuration, selected
     * profile direction, and canonical override fragments produce byte-identical canonical
     * documents across independent generator instances and repeated calls. That holds across a
     * failure too: a call that fails restores the underlying generator's per-generation provider
     * state before propagating, so it leaves no trace in what later calls on this instance publish.
     *
     * <p>Stack exhaustion inside the underlying generator's recursive descent — the way a
     * pathologically deep type graph fails — is normalized like any other generation failure, so a
     * deep type does not escape the bounded failure contract merely because the JVM reports it as an
     * {@link Error}. A VM-level {@code Error} such as {@link OutOfMemoryError} or a {@link
     * LinkageError} is deliberately not normalized: it reports a condition of the runtime rather than
     * of the requested type, and propagates unchanged.
     *
     * @param type the resolved Java type to generate a schema for; must not be {@code null}
     * @return the canonical, compact Draft 2020-12 JSON Schema document as a {@code String}
     * @throws JsonSchemaGenerationException if {@code type} is outside the accepted grammar, if
     *                                        generation exhausts the stack, or if generation,
     *                                        override application, conflict detection, or
     *                                        canonicalization fails
     */
    public String generateCanonical(Type type) {
        TypeGrammar.requireGeneratable(type);
        // The whole operation — generation and canonicalization — is serialized per instance. Victools'
        // own thread-safety is deliberately not relied upon, and the custom definition provider is
        // shared by every call on this instance.
        synchronized (lock) {
            ObjectNode generated;
            try {
                generated = generator.generateSchema(type);
            } catch (RuntimeException | StackOverflowError aborted) {
                // StackOverflowError is caught with the runtime failures on purpose: exhausting the
                // stack is how a pathologically deep type graph fails inside the generator's own
                // recursive descent, which makes it a generation failure like any other. It is
                // unrelated to the VM-level conditions the next clause deliberately leaves alone.
                restoreProviderStateAfterAbortedGeneration(aborted);
                if (aborted instanceof JsonSchemaGenerationException alreadyBounded) {
                    throw alreadyBounded;
                }
                throw Diagnostics.failure(
                        "JSON Schema generation failed for " + Diagnostics.typeIdentity(type), aborted);
            } catch (Throwable aborted) {
                // Any other Error — OutOfMemoryError, a LinkageError — reports a VM-level condition
                // this module can neither describe nor recover from, and building a diagnostic for it
                // may well fail in turn, so it propagates unchanged. The provider state it aborted is
                // still restored on the way out.
                restoreProviderStateAfterAbortedGeneration(aborted);
                throw aborted;
            }
            try {
                // Structural safety net: a document that conjoins disjoint explicit types is
                // unsatisfiable, and is refused before it can be canonicalized and handed to a consumer.
                DisjointTypeDetector.requireNoDisjointTypes(generated);
                if (suppressInapplicableNumericKeywords) {
                    // PRD §6.2 wire-honesty: a Jakarta numeric-domain constraint (e.g. @DecimalMin)
                    // must not be advertised against a wire type an override replaced with a non-number.
                    NumericDomainKeywordFilter.suppressInapplicableNumericKeywords(generated);
                }
            } catch (JsonSchemaGenerationException alreadyBounded) {
                throw alreadyBounded;
            } catch (RuntimeException failed) {
                // A walk failure is still a generation failure: it normalizes to the module's bounded
                // type rather than escaping as raw, unbounded third-party text (FR-JSON-075/076).
                throw Diagnostics.failure(
                        "post-generation validation of the generated JSON Schema failed for "
                                + Diagnostics.typeIdentity(type),
                        failed);
            }
            try {
                return SchemaCanonicalizer.canonicalize(generated);
            } catch (JsonProcessingException | RuntimeException failed) {
                throw Diagnostics.failure(
                        "canonicalization of the generated JSON Schema failed for " + Diagnostics.typeIdentity(type),
                        failed);
            }
        }
    }

    /**
     * Restores the per-generation state the underlying generator's stateful providers hold, after a
     * generation call that exited abnormally.
     *
     * <p>At the pinned Victools version the configuration's {@code
     * resetAfterSchemaGenerationFinished()} is invoked as straight-line code immediately before the
     * finished document is returned, and the builder performing that generation carries no exception
     * handler anywhere — so the reset runs on the <em>success path only</em>. Some of the providers
     * the Swagger 2 module registers are stateful across exactly that boundary: the external-reference
     * provider latches the generation's <em>main</em> type on first use and clears it in that reset
     * and nowhere else. An aborted generation would therefore leave the main type pinned to the type
     * it failed on, and a later generation of a <em>different</em> root type carrying a type-level
     * {@code @Schema(ref = ...)} would publish a bare external {@code $ref} in place of that type's
     * schema — silently, with no exception, and for every later call until one succeeds. Since this
     * package's own {@link SchemaImplementationGuard} aborts generation by design, that is a
     * reachable outcome, not a theoretical one.
     *
     * <p>Calling the reset here restores exactly the invariant the success path maintains, through
     * the same public entry point and driving the same cascade. It cannot double-reset: the success
     * path never reaches this method.
     *
     * <p>The restoration must never displace the failure that triggered it — replacing a precise
     * generation failure with an unrelated one would cost far more than the state it recovers — so
     * anything the reset itself raises is recorded as a suppressed exception on the propagating
     * failure and otherwise ignored. The identity check guards the one input on which {@code
     * addSuppressed} throws by contract, and the bookkeeping is nested inside its own handler
     * because {@code addSuppressed} <em>allocates</em>: on a heap-exhausted JVM it can raise a
     * further {@link OutOfMemoryError} of its own, which would otherwise propagate in place of the
     * failure this method exists to preserve — on exactly the path where that failure matters most.
     *
     * <p>One limitation is stated rather than claimed away: an {@link Error} that lands mid-mutation
     * inside a JDK collection the generator maintains is outside what any reset can restore.
     *
     * @param aborted the failure that ended the generation and is about to propagate
     */
    private void restoreProviderStateAfterAbortedGeneration(Throwable aborted) {
        try {
            generator.getConfig().resetAfterSchemaGenerationFinished();
        } catch (Throwable resetFailed) {
            try {
                if (resetFailed != aborted) {
                    aborted.addSuppressed(resetFailed);
                }
            } catch (Throwable unrecordable) {
                // The failure that triggered the restoration must win unconditionally: recording the
                // restoration failure allocates, so it can fail in turn on the very JVM state that
                // makes this path matter. Losing the record is strictly better than losing the failure.
            }
        }
    }
}
