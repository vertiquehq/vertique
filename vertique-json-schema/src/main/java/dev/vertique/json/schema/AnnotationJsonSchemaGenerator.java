// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.MemberScope;
import com.github.victools.jsonschema.generator.MethodScope;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerationContext;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.generator.TypeScope;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import com.github.victools.jsonschema.module.swagger2.Swagger2Module;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonSchemaTypeOverride.Direction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p><strong>How the input direction describes an any-setter's extra keys.</strong> A type with an
 * any-setter — other than one Jackson deserializes as map-like or collection-like, where Jackson
 * ignores the any-setter and so does this generator — describes its extra keys through {@code
 * additionalProperties}: the map value type of a field-level any-setter, or the second parameter of
 * a method-level one, published as this generator's own definition of that type, so profile
 * overrides, formats, and shared definitions apply to an extra value exactly as they do to a named
 * property. An unconstrained value type — {@code Object}, {@code JsonNode}, {@code TreeNode}, or a
 * wildcard or raw form resolving to one — is the empty schema, which accepts every JSON value. A
 * class-level {@code @Schema(additionalProperties = FALSE)}, declared or inherited, keeps the object
 * closed; a class-level {@code @Schema(additionalProperties = TRUE)} says less than the typed
 * description, which therefore wins.
 *
 * <p>Where extra keys are described, {@code propertyNames: {"not": {"enum": [...]}}} carries one
 * reserved set, computed as a difference rather than as a list of categories: every name Jackson
 * binds on input for that type, minus every name the document publishes under {@code properties},
 * minus every name whose Jackson property definition carries no member at all. Without it, a name
 * the document never published — a name marked {@code @JsonIgnore} or read-only, a class-level
 * ignoral, a method {@code @JsonAnyGetter}'s storage field, or a name bound only through a setter,
 * an accessor pair, a {@code @Schema(hidden = true)} field, or a {@code transient} field — would be
 * accepted as an ordinary extra key and bound straight into the member it names. The subtraction of
 * published names is by member, never by spelling, so a property published under some other name is
 * not reserved; the subtraction of memberless definitions fails open for the one shape whose member
 * identity cannot be recovered, a creator parameter renamed away from its field, which therefore
 * keeps accepting the traffic it already accepted. A field that is both {@code @JsonAnyGetter} and
 * {@code @JsonAnySetter} reserves no storage name, because Jackson stores a key with that name as an
 * ordinary entry of the map. An existing {@code propertyNames} is combined with the reserved set
 * under {@code allOf} rather than replaced.
 *
 * <p><strong>How the input direction describes an alias spelling.</strong> Every {@code @JsonAlias}
 * spelling Jackson reports for a visible input property that does not back an any-accessor is listed
 * under {@code properties} with a deep copy of the schema that property is published with, so the
 * same constraints apply under either spelling instead of the alias passing a gate undescribed. A
 * spelling that is already another property's name — the type's own name for it, whether or not the
 * document publishes that property — is not listed, and neither is a spelling more than one property
 * of the type claims: the generator cannot predict which claimant Jackson binds such a key to, so it
 * is published nowhere, named in no rule and, where extras are described, reserved.
 * Listing runs over the finished document, after generation, so every reference a copied schema
 * carries is already resolved; it copies the owning property's own entry in the finished {@code
 * properties} object and does nothing when that entry is absent, so a spelling is listed exactly
 * where its property's own wire name is published and stays reserved everywhere else.
 *
 * <p>A required aliased property leaves the top-level {@code required} list, because one rule per
 * aliased property states which spellings may appear instead. Under a lenient profile a required
 * property needs at least one spelling ({@code anyOf} of {@code {"required": [spelling]}}) and an
 * optional one needs no rule; under a strict profile a required property needs exactly one ({@code
 * oneOf} of the same branches) and an optional one at most one (the same branches plus a {@code
 * {"not": {"anyOf": [...]}}} branch for none present). The rules are appended to one {@code allOf},
 * or stand alone when there is one rule and its keyword is free, and their branches carry only {@code
 * required} or {@code not}, never {@code properties}. A profile is strict exactly when its own
 * mapper — the one the binder parses with — enables {@code
 * JsonParser.Feature.STRICT_DUPLICATE_DETECTION}; no flag, SPI member, or configuration key decides
 * it. The binder itself accepts several spellings under every profile, so the one-spelling rule is
 * the schema's alone.
 *
 * <p>The expansion plan is carried in the document under one generator-private keyword, {@link
 * AliasExpansion#MARKER}, which expansion removes; generation is therefore refused, with a bounded
 * diagnostic naming the type and that name, for a type publishing a property whose wire name equals
 * it — including a property expansion would publish under it for an alias spelling — in every
 * construction mode.
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
     *
     * <p>The input direction's any-setter extras resolver is registered <em>before</em> the modules,
     * because Victools takes the first non-null answer: the Swagger 2 module answers {@code true} for
     * a class-level {@code @Schema(additionalProperties = TRUE)}, which says nothing about the extras'
     * type and would otherwise hide the any-setter's value type. The resolver stays silent for a type
     * carrying {@code FALSE}, so that module still publishes the application's own restriction. The
     * alias marking and the reserved-name publication are a type-attribute override instead, registered
     * after the modules so that they see each definition's finished {@code properties} and {@code
     * additionalProperties}.
     */
    private static SchemaGenerator build(
            SchemaGeneratorConfigBuilder builder, ProfilePropertyNameResolver propertyNames) {
        if (propertyNames != null && propertyNames.direction == Direction.INPUT) {
            builder.forTypesInGeneral().withAdditionalPropertiesResolver(propertyNames::anySetterExtras);
        }
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
            if (propertyNames.direction == Direction.INPUT) {
                builder.forTypesInGeneral().withTypeAttributeOverride(propertyNames::markAliasesAndReservedNames);
            }
        }
        return new SchemaGenerator(builder.build());
    }

    /** Mapper-introspected wire names keyed by the exact member Victools is publishing. */
    private static final class ProfilePropertyNameResolver {
        private final ObjectMapper mapper;
        private final Direction direction;

        /**
         * Whether the selected profile forbids several spellings of one property in one object, read
         * from the profile's own mapper — the instance the binder parses a body with — and from
         * nothing else: a mapper enabling {@code JsonParser.Feature.STRICT_DUPLICATE_DETECTION} rejects
         * a repeated key, and the alias rules published for it are the exclusive form.
         */
        private final boolean strictSpellings;

        private record PropertyMetadata(String wireName, boolean visible) {}

        /**
         * @param byMember                  wire names keyed by the exact member Victools publishes
         * @param byInternalName            the same, keyed by internal and wire name, as a fallback
         * @param anyAccessorMembers        the any-setter and any-getter members themselves
         * @param anyAccessorBackingMembers the members that store what those accessors collect
         * @param anySetterValueType        the declared value type of the type's any-setter, or
         *                                  {@code null} when the type has none Jackson would use
         * @param aliasesByWireName         the alias spellings of each visible input property that
         *                                  does not back an any-accessor, keyed by the property's own
         *                                  wire name, in declaration order, with every contested
         *                                  spelling and every spelling that is already a property name
         *                                  of the type already dropped
         * @param reservedInputNames        the reserved-name candidates: every name Jackson binds on
         *                                  input that no member of this type could publish, before the
         *                                  published names are subtracted against the finished
         *                                  document; empty unless extras are described
         * @param inputBoundMembers         the members behind each name Jackson binds on input, so a
         *                                  candidate is matched against what was published by member
         *                                  rather than by spelling; an empty member set is a property
         *                                  Jackson binds through a creator parameter alone
         */
        private record PropertyNames(
                Map<Member, PropertyMetadata> byMember,
                Map<String, PropertyMetadata> byInternalName,
                Set<Member> anyAccessorMembers,
                Set<Member> anyAccessorBackingMembers,
                Type anySetterValueType,
                Map<String, List<String>> aliasesByWireName,
                Set<String> reservedInputNames,
                Map<String, Set<Member>> inputBoundMembers) {}

        /** The verdict for an any-accessor or its backing storage: never a named property. */
        private static final PropertyMetadata HIDDEN_ANY_ACCESSOR_BACKING = new PropertyMetadata(null, false);

        /** Value types that accept every JSON value, and are therefore published as the empty schema. */
        private static final Set<Class<?>> UNCONSTRAINED_VALUE_TYPES =
                Set.of(Object.class, JsonNode.class, TreeNode.class);

        private final ClassValue<PropertyNames> namesByType = new ClassValue<>() {
            @Override
            protected PropertyNames computeValue(Class<?> type) {
                return introspect(type);
            }
        };

        /**
         * The members Victools carried into a definition, per declaring type, with the wire name each
         * was carried under.
         *
         * <p>Recorded from {@link #resolve(MemberScope)} because that is consulted for exactly the
         * members Victools is about to publish, and consulted before the type-attribute override that
         * reads the finished {@code properties} object. Which of the recorded members actually reached
         * the document is then decided against that object, so a member a later module drops — a
         * {@code @Schema(hidden = true)} field, say — is not mistaken for a published one.
         */
        private final Map<Class<?>, Map<Member, String>> resolvedMembersByType = new ConcurrentHashMap<>();

        private ProfilePropertyNameResolver(ObjectMapper mapper, Direction direction) {
            this.mapper = mapper;
            this.direction = direction;
            // Read once, from the profile's own mapper instance: the same one that parses a body at the
            // REST gate, so the published rule and the binder's parse decision cannot disagree.
            this.strictSpellings = mapper.getFactory().isEnabled(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        }

        private String resolve(MemberScope<?, ?> scope) {
            PropertyMetadata property = metadata(scope);
            String wireName = property == null ? null : property.wireName();
            if (direction == Direction.INPUT && !scope.isFakeContainerItemScope()) {
                Member raw = scope.getRawMember();
                // A null wire name here means "no override": Victools then publishes the member under
                // its own name, which is what the reserved-name subtraction must match against.
                String published = wireName != null ? wireName : scope.getName();
                if (raw != null && published != null) {
                    resolvedMembersByType
                            .computeIfAbsent(scope.getDeclaringType().getErasedType(), key -> new ConcurrentHashMap<>())
                            .put(raw, published);
                }
            }
            return wireName;
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
            AnyAccessors anyAccessors =
                    direction == Direction.INPUT ? collectAnyAccessors(type, javaType, description) : AnyAccessors.NONE;
            // The names Jackson binds on input that no member of this type could ever publish, and the
            // names it binds through a member, with the members behind each. Both feed the reserved-name
            // difference, which is completed against the finished document.
            Set<String> invisibleOnInput = new TreeSet<>();
            Map<String, Set<Member>> inputBoundMembers = new TreeMap<>();
            // The alias spellings of each property that keeps them, and every spelling any property
            // claims, which is reserved by default and released only where its property is published.
            Map<String, List<String>> aliases = new TreeMap<>();
            Set<String> aliasSpellings = new TreeSet<>();
            // A spelling more than one property of this type claims binds to whichever claimant Jackson
            // resolves it to, which this generator cannot predict: publishing it would attach one
            // claimant's schema to another claimant's value, so it is published nowhere, named in no
            // rule, and — where extras are described — reserved rather than accepted as an extra.
            Set<String> contestedSpellings =
                    direction == Direction.INPUT ? contestedAliasSpellings(description) : Set.of();
            // The type's own property names, read from this same introspection rather than from the
            // finished document. A spelling that is already one of them is withheld whether or not the
            // document publishes that property: a property it never publishes still binds its own name,
            // so publishing the spelling would attach the aliasing property's schema to a member the
            // document deliberately does not describe, and would release that name from the reserved set
            // along with the aliasing property's other spellings.
            Set<String> ownPropertyNames = direction == Direction.INPUT ? declaredPropertyNames(description) : Set.of();
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
                if (direction == Direction.INPUT) {
                    if (!visible) {
                        // Bound to no member on input — ignored, or read-only — so nothing can publish
                        // it and nothing can subtract it later.
                        invisibleOnInput.add(property.getName());
                    } else if (!backsAnAnyAccessor(property, anyAccessors)) {
                        inputBoundMembers.put(property.getName(), ownMembers(type, property));
                        List<String> spellings = keptAliasSpellings(property, contestedSpellings, ownPropertyNames);
                        if (!spellings.isEmpty()) {
                            aliases.put(property.getName(), spellings);
                            aliasSpellings.addAll(spellings);
                        }
                    }
                }
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
            Set<String> reserved = new TreeSet<>();
            if (anyAccessors.anySetterValueType() != null) {
                // Only a type whose extras are described reserves anything: on every other type the
                // object is closed or has no extras to tell a reserved name apart from.
                reserved.addAll(description.getIgnoredPropertyNames());
                reserved.addAll(mapper.getDeserializationConfig()
                        .getDefaultPropertyIgnorals(type, description.getClassInfo())
                        .findIgnoredForDeserialization());
                reserved.addAll(invisibleOnInput);
                // Jackson fills a method any-getter's storage through the getter, under the storage
                // field's own name, so that name is bound on input and is never an extra.
                reserved.addAll(anyAccessors.getterStorageNames());
                // Every name Jackson binds as a named property is a candidate; publishReservedNames
                // subtracts the ones the document actually published, leaving exactly the names Jackson
                // binds that no published property carries.
                reserved.addAll(inputBoundMembers.keySet());
                // A contested spelling binds to one of its claimants, so it is not an extra either —
                // and unlike a kept spelling it is never published, so nothing releases it.
                reserved.addAll(contestedSpellings);
                // A spelling Jackson binds is reserved by default and released only where its
                // publication is known. Whether expansion publishes one depends on whether the owning
                // property's own wire name reached the finished properties object, which is not knowable
                // here: markAliasesAndReservedNames releases exactly the spellings of a property that
                // was published and leaves every other spelling reserved. Releasing them unconditionally
                // here would leave the spelling of a bound-but-unpublished property — a
                // @Schema(hidden = true) field, an accessor pair with no same-named field — neither
                // published nor reserved, so it would be accepted as an ordinary extra and bound into
                // the very member this type's own property name already refuses.
                reserved.addAll(aliasSpellings);
            }
            return new PropertyNames(
                    Map.copyOf(members),
                    Map.copyOf(internalNames),
                    anyAccessors.members(),
                    anyAccessors.backingMembers(),
                    anyAccessors.anySetterValueType(),
                    // Not Map.copyOf: the published rule order follows this map's iteration order, and
                    // an immutable copy's order is unspecified. The TreeMap is local to this call and
                    // is never mutated after it returns.
                    Collections.unmodifiableMap(aliases),
                    Set.copyOf(reserved),
                    Map.copyOf(inputBoundMembers));
        }

        /**
         * The alias spellings a property keeps: every spelling Jackson reports for it, less the
         * property's own wire name — which is not an alias — less every spelling that is already a
         * property name of the same type, and less every contested spelling.
         *
         * <p>The collision with another property's name is decided here, against the names this type
         * declares, and never later against the finished document: a property the document does not
         * publish still binds its own name, so a spelling naming it is withheld exactly as a spelling
         * naming a published property is. A withheld spelling is published nowhere and named in no rule,
         * so nothing releases the name it collides with from the reserved set.
         *
         * <p>Declaration order is preserved and a spelling declared twice on one property counts once,
         * so the published rule names each spelling exactly once in a stable order.
         *
         * @param property           the introspected property
         * @param contestedSpellings the spellings more than one property of the type claims
         * @param ownPropertyNames   every property name the type itself declares on input
         * @return the kept spellings, possibly empty
         */
        private static List<String> keptAliasSpellings(
                BeanPropertyDefinition property, Set<String> contestedSpellings, Set<String> ownPropertyNames) {
            Set<String> spellings = new LinkedHashSet<>();
            for (PropertyName alias : property.findAliases()) {
                String simple = alias.getSimpleName();
                if (simple != null
                        && !simple.isEmpty()
                        && !simple.equals(property.getName())
                        && !ownPropertyNames.contains(simple)
                        && !contestedSpellings.contains(simple)) {
                    spellings.add(simple);
                }
            }
            return List.copyOf(spellings);
        }

        /**
         * Every property name the type itself declares, published or not.
         *
         * <p>Read from the type's own introspection, the same pass {@link
         * #contestedAliasSpellings(BeanDescription)} reads, because that is the only view in which a
         * property Jackson binds but the document never publishes is still visible. An any-accessor's
         * storage is included: its name is a property name of the type, and a spelling of it is as
         * unpredictable as any other collision.
         *
         * @param description the type's introspection
         * @return the declared property names
         */
        private static Set<String> declaredPropertyNames(BeanDescription description) {
            Set<String> names = new TreeSet<>();
            for (BeanPropertyDefinition property : description.findProperties()) {
                String name = property.getName();
                if (name != null && !name.isEmpty()) {
                    names.add(name);
                }
            }
            return names;
        }

        /**
         * The alias spellings more than one property of a type claims.
         *
         * <p>A property claiming one spelling twice counts once, and a spelling equal to the claiming
         * property's own wire name is not a claim at all. Every property of the type is counted,
         * including one the document does not publish: Jackson still routes the key to it, so the
         * generator still cannot predict which member such a key reaches.
         *
         * @param description the type's deserialization introspection
         * @return the contested spellings
         */
        private static Set<String> contestedAliasSpellings(BeanDescription description) {
            Map<String, Integer> claimants = new TreeMap<>();
            for (BeanPropertyDefinition property : description.findProperties()) {
                Set<String> own = new TreeSet<>();
                for (PropertyName alias : property.findAliases()) {
                    String simple = alias.getSimpleName();
                    if (simple != null && !simple.isEmpty() && !simple.equals(property.getName())) {
                        own.add(simple);
                    }
                }
                own.forEach(spelling -> claimants.merge(spelling, 1, Integer::sum));
            }
            Set<String> contested = new TreeSet<>();
            claimants.forEach((spelling, count) -> {
                if (count > 1) {
                    contested.add(spelling);
                }
            });
            return contested;
        }

        /**
         * Whether any member of a property is an any-accessor or the storage one fills, in which case
         * the property is not a named input property at all and reserves no name.
         *
         * @param property     the introspected property
         * @param anyAccessors the type's any-accessor members
         * @return {@code true} when the property backs an any-accessor
         */
        private static boolean backsAnAnyAccessor(BeanPropertyDefinition property, AnyAccessors anyAccessors) {
            for (AnnotatedMember member :
                    new AnnotatedMember[] {property.getField(), property.getGetter(), property.getSetter()}) {
                if (member != null
                        && (anyAccessors.members().contains(member.getMember())
                                || anyAccessors.backingMembers().contains(member.getMember()))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The members a property is identified by: its field, getter, and setter, plus the record
         * component when the declaring type is a record.
         *
         * <p>An empty result is the creator-parameter-only shape — Jackson binds the name but links no
         * field, setter, getter, or record component to it — for which member identity is not
         * recoverable and the reserved-name rule fails open.
         *
         * @param type     the erased type being introspected
         * @param property the introspected property
         * @return the property's own members, possibly empty
         */
        private static Set<Member> ownMembers(Class<?> type, BeanPropertyDefinition property) {
            Set<Member> own = new HashSet<>();
            for (AnnotatedMember member :
                    new AnnotatedMember[] {property.getField(), property.getGetter(), property.getSetter()}) {
                if (member != null && member.getMember() != null) {
                    own.add(member.getMember());
                }
            }
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    if (component.getName().equals(property.getInternalName())) {
                        own.add(component.getAccessor());
                    }
                }
            }
            return Set.copyOf(own);
        }

        /**
         * The any-accessor facts of one type.
         *
         * @param members            the any-setter and any-getter members themselves
         * @param backingMembers     the members that store what those accessors collect
         * @param getterStorageNames the names Jackson binds straight into a method any-getter's
         *                           storage, through that getter
         * @param anySetterValueType the declared value type of the any-setter Jackson would use, or
         *                           {@code null} when the type has none
         */
        private record AnyAccessors(
                Set<Member> members,
                Set<Member> backingMembers,
                Set<String> getterStorageNames,
                Type anySetterValueType) {

            /** The verdict for a type with no any-accessor, and for the output direction. */
            static final AnyAccessors NONE = new AnyAccessors(Set.of(), Set.of(), Set.of(), null);
        }

        /**
         * Collects the any-setter and any-getter members of a type, the members that store what they
         * collect — so neither is ever described as a named property — and the any-setter's declared
         * value type, which is how its extra keys are described.
         *
         * <p>Storage is identified by member and never by a name derived from an accessor: a field
         * annotated {@code @JsonAnySetter}, the record component whose field that is, a field
         * annotated {@code @JsonAnyGetter}, and the field Jackson links to a method
         * {@code @JsonAnyGetter}, where the field's type can be that getter's return value. A
         * property whose name an accessor merely implies — {@code attribute} beside an any-setter
         * {@code setAttribute(String, Object)} — is a real property and stays described.
         *
         * <p>Jackson binds a map-like or collection-like type as a container and never routes input
         * to its any-setter, so for such a type the any-setter is ignored here as Jackson ignores it:
         * it yields no value type either, and such a type is therefore described exactly as if it
         * declared no any-setter at all.
         *
         * @param type        the erased type being introspected
         * @param javaType    its resolved Jackson type
         * @param description the type's deserialization introspection
         * @return the type's any-accessor facts
         */
        private static AnyAccessors collectAnyAccessors(Class<?> type, JavaType javaType, BeanDescription description) {
            Set<Member> accessors = new HashSet<>();
            Set<Member> backing = new HashSet<>();
            Set<String> getterStorageNames = new TreeSet<>();
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
                        // Jackson binds this name straight into the any-getter's storage, through the
                        // getter, so the name is bound on input even though nothing publishes it.
                        getterStorageNames.add(property.getName());
                    }
                }
            }
            return new AnyAccessors(
                    Set.copyOf(accessors),
                    Set.copyOf(backing),
                    Set.copyOf(getterStorageNames),
                    anySetter == null ? null : anySetterValueType(anySetter));
        }

        /**
         * The Java value type an any-setter accepts: the map value type of a field-level any-setter,
         * or the second parameter of a method-level one.
         *
         * <p>An any-setter field that is not map-like — an {@code ObjectNode} or {@code JsonNode}
         * field — accepts every JSON value, which {@code Object} stands for here. A value type the
         * generator's own type grammar cannot resolve — a type variable or a wildcard — falls back to
         * the erased type Jackson resolved it to, so a raw or wildcard declaration still yields a
         * describable type rather than failing generation.
         *
         * @param anySetter the any-setter member
         * @return the declared value type, never {@code null}
         */
        private static Type anySetterValueType(AnnotatedMember anySetter) {
            Type declared;
            Class<?> fallback;
            if (anySetter instanceof AnnotatedMethod method) {
                declared = method.getAnnotated().getGenericParameterTypes()[1];
                fallback = method.getParameterType(1).getRawClass();
            } else if (anySetter instanceof AnnotatedField field) {
                JavaType fieldType = field.getType();
                if (!fieldType.isMapLikeType()) {
                    return Object.class;
                }
                Type generic = field.getAnnotated().getGenericType();
                declared = generic instanceof ParameterizedType parameterized
                        ? parameterized.getActualTypeArguments()[1]
                        : Object.class;
                fallback = fieldType.getContentType().getRawClass();
            } else {
                return Object.class;
            }
            return declared instanceof Class<?> || declared instanceof ParameterizedType ? declared : fallback;
        }

        /**
         * The {@code additionalProperties} resolver for the input direction: the any-setter's value
         * schema, the empty schema for an unconstrained value type, or {@code null} for "no opinion".
         *
         * <p>"No opinion" covers a type with no any-setter, a primitive or array, and a class carrying
         * {@code @Schema(additionalProperties = FALSE)} — declared or inherited — whose restriction the
         * Swagger 2 module then publishes as {@code false} unopposed.
         *
         * @param scope   the type being described
         * @param context the generation context, which owns the definition of the value type
         * @return the extras schema, or {@code null} to leave the decision to the later resolvers
         */
        private JsonNode anySetterExtras(TypeScope scope, SchemaGenerationContext context) {
            Class<?> erased = scope.getType().getErasedType();
            if (erased.isPrimitive() || erased.isArray()) {
                return null;
            }
            Type valueType = namesByType.get(erased).anySetterValueType();
            if (valueType == null) {
                return null;
            }
            Schema schema = erased.getAnnotation(Schema.class);
            if (schema != null && schema.additionalProperties() == Schema.AdditionalPropertiesValue.FALSE) {
                return null;
            }
            ResolvedType resolved = context.getTypeContext().resolve(valueType);
            if (UNCONSTRAINED_VALUE_TYPES.contains(resolved.getErasedType())) {
                return context.getGeneratorConfig().createObjectNode();
            }
            // The context's own definition reference, not a hand-built fragment: a profile override, a
            // format, and a shared definition then apply to an extra value exactly as to a property.
            return context.createDefinitionReference(resolved);
        }

        /**
         * The type-attribute override for the input direction: marks a definition whose type carries
         * aliased properties, for expansion once every reference is resolved, and publishes, beside
         * described extras, the names Jackson binds on input that the finished definition does not
         * publish.
         *
         * <p>Registered after the annotation modules, so {@code properties} and {@code
         * additionalProperties} are final by the time this runs. The subtraction is by member and never
         * by spelling, mirroring the any-accessor exclusion: if any member of an input-bound property
         * was carried into the document, that property is published — under whatever name — and its
         * Jackson name is not an extra to reserve. A property with no member at all is the
         * creator-parameter-only shape, whose identity cannot be recovered; the rule fails open for it
         * rather than refusing the valid traffic that binds to it.
         *
         * <p>The alias plan is written into the definition rather than recorded out of band, because
         * the {@code ObjectNode} handed to a type-attribute override is not the node that reaches the
         * finished document — see {@link AliasExpansion#MARKER}.
         *
         * @param definition the finished definition of the type
         * @param scope      the type being described
         * @param context    the generation context, unused
         */
        private void markAliasesAndReservedNames(
                ObjectNode definition, TypeScope scope, SchemaGenerationContext context) {
            Class<?> erased = scope.getType().getErasedType();
            if (erased.isPrimitive() || erased.isArray()) {
                return;
            }
            PropertyNames names = namesByType.get(erased);
            JsonNode properties = definition.get("properties");
            if (!names.aliasesByWireName().isEmpty() && properties != null && properties.isObject()) {
                ObjectNode marker = definition.putObject(AliasExpansion.MARKER);
                marker.put(AliasExpansion.STRICT, strictSpellings);
                ObjectNode byWireName = marker.putObject(AliasExpansion.ALIASES);
                names.aliasesByWireName().forEach((wireName, spellings) -> {
                    ArrayNode array = byWireName.putArray(wireName);
                    spellings.forEach(array::add);
                });
            }
            if (names.reservedInputNames().isEmpty()) {
                return;
            }
            JsonNode additional = definition.get("additionalProperties");
            boolean extrasDescribed = additional != null && !(additional.isBoolean() && !additional.booleanValue());
            if (!extrasDescribed) {
                // The object is closed — by a class-level @Schema(additionalProperties = FALSE), or by
                // a consumer-side hardener later — so every unpublished name is already refused.
                return;
            }
            Set<String> publishedNames = new TreeSet<>();
            if (properties != null && properties.isObject()) {
                properties.fieldNames().forEachRemaining(publishedNames::add);
            }
            Set<String> reservedNames = new TreeSet<>(names.reservedInputNames());
            reservedNames.removeAll(publishedNames);
            Map<Member, String> resolvedMembers = resolvedMembersByType.getOrDefault(erased, Map.of());
            names.inputBoundMembers().forEach((name, members) -> {
                if (members.isEmpty()) {
                    reservedNames.remove(name);
                    return;
                }
                for (Member member : members) {
                    String carriedAs = resolvedMembers.get(member);
                    if (carriedAs != null && publishedNames.contains(carriedAs)) {
                        reservedNames.remove(name);
                        return;
                    }
                }
            });
            // Release a property's alias spellings exactly where expansion will publish them:
            // AliasExpansion copies a spelling's schema from the owning property's own entry in the
            // finished properties object and does nothing when that entry is absent, so a released
            // spelling is always a published one and a spelling that stays reserved is never published.
            // A spelling the collision rule withheld — one that is already a property name of this type —
            // never reaches this map, so releasing the aliasing property's spellings cannot release it.
            names.aliasesByWireName().forEach((wireName, spellings) -> {
                if (publishedNames.contains(wireName)) {
                    reservedNames.removeAll(spellings);
                }
            });
            if (reservedNames.isEmpty()) {
                return;
            }
            ObjectNode reserved = JsonNodeFactory.instance.objectNode();
            ArrayNode values = reserved.putObject("not").putArray("enum");
            reservedNames.forEach(values::add);
            JsonNode existing = definition.get("propertyNames");
            if (existing == null) {
                definition.set("propertyNames", reserved);
            } else {
                // Never displace a declared propertyNames: both constraints must hold.
                ObjectNode combined = JsonNodeFactory.instance.objectNode();
                combined.putArray("allOf").add(existing).add(reserved);
                definition.set("propertyNames", combined);
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
                // Refused before expansion strips the key, and in every construction mode, because
                // expansion strips it whether or not this mode ever writes one.
                AliasExpansion.requireNoMarkerPropertyName(generated, type);
                // Expanded only now, once every reference node is final, so each alias spelling carries
                // an exact copy of the schema its property is published with.
                AliasExpansion.expand(generated);
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

    /**
     * Turns the alias plans the input-direction resolver left in a finished document into published
     * alias spellings and the rules that state which spellings may appear.
     *
     * <p>Expansion runs over the whole document, after generation and before canonicalization, so a
     * copied schema carries resolved references rather than placeholders, and it removes every plan it
     * reads, so no published document carries the plan keyword.
     */
    static final class AliasExpansion {

        /**
         * The temporary, generator-private keyword each alias plan is carried under; it never survives
         * into a published document.
         *
         * <p>Carrying the plan out of band — an {@code IdentityHashMap} from the definition node to its
         * plan, applied after generation — was built and measured, and does not work: the {@code
         * ObjectNode} a type-attribute override is handed is not the node that reaches the finished
         * document. Across 924 generations of the design proof's corpus, 132 recorded plans matched
         * zero document nodes by identity, and 60 matched only by value equality, which cannot be used
         * because two structurally equal definitions are indistinguishable. The pinned Victools version
         * rebuilds definition nodes on the way into the document, so identity cannot carry a plan and
         * the marker must live in the document.
         *
         * <p>The marker is therefore made safe rather than invisible: a document publishing a property
         * under this exact wire name is refused by {@link #requireNoMarkerPropertyName}, because
         * expansion strips this key from every object node and would otherwise hand a consumer that
         * property stripped of its constraints.
         */
        static final String MARKER = "x-vertique-alias-plan";

        /** The plan member carrying whether the profile's mapper forbids several spellings. */
        private static final String STRICT = "strict";

        /** The plan member carrying each aliased property's spellings, keyed by its own wire name. */
        private static final String ALIASES = "aliases";

        private AliasExpansion() {}

        /**
         * Refuses a document in which some object would publish a property whose wire name is exactly
         * {@link #MARKER} — whether it already publishes one, or whether expansion would publish one
         * under that name for an alias spelling.
         *
         * <p>Expansion removes that key from every object node, including a {@code properties} object,
         * so publishing it would hand a consumer a property stripped of its constraints — precisely the
         * shape a gate then accepts a violating value under. A spelling equal to the keyword reaches the
         * same end by a longer road: expansion publishes it and the same descent removes it again,
         * leaving a name the document neither publishes nor reserves. Both are refused here, before
         * expansion runs, in every construction mode. The plan itself is written as a direct child of a
         * definition node and never as a key inside a {@code properties} object, so only the genuine
         * collision is caught.
         *
         * @param node the document, or one of its nodes during the descent
         * @param type the type being generated, named in the diagnostic
         * @throws JsonSchemaGenerationException if any object publishes, or would publish by expansion,
         *                                        a property under {@link #MARKER}
         */
        static void requireNoMarkerPropertyName(JsonNode node, Type type) {
            requireNoMarkerPropertyName(node, type, newVisitedSet());
        }

        /**
         * The descent of {@link #requireNoMarkerPropertyName(JsonNode, Type)}, carrying the nodes it has
         * already inspected.
         *
         * @param node    the document, or one of its nodes during the descent
         * @param type    the type being generated, named in the diagnostic
         * @param visited the nodes already inspected, by identity
         */
        private static void requireNoMarkerPropertyName(JsonNode node, Type type, Set<JsonNode> visited) {
            if (node == null || !node.isContainerNode() || !visited.add(node)) {
                return;
            }
            JsonNode properties = node.get("properties");
            if (properties != null && properties.isObject() && properties.has(MARKER)) {
                throw markerCollision(type, "rename the property on the wire (for example with @JsonProperty)");
            }
            if (planListsMarkerSpelling(node.get(MARKER))) {
                throw markerCollision(
                        type,
                        "rename the alias spelling expansion would publish under it (for example with"
                                + " @JsonAlias)");
            }
            for (JsonNode child : children(node)) {
                requireNoMarkerPropertyName(child, type, visited);
            }
        }

        /**
         * Whether an alias plan lists {@link #MARKER} as one of a property's spellings, which expansion
         * would publish as a property wire name.
         *
         * @param marker the plan carried by a definition node, or {@code null} when it carries none
         * @return {@code true} when some property's spellings name the keyword
         */
        private static boolean planListsMarkerSpelling(JsonNode marker) {
            if (marker == null || !marker.isObject()) {
                return false;
            }
            for (JsonNode spellings : children(marker.path(ALIASES))) {
                for (JsonNode spelling : spellings) {
                    if (MARKER.equals(spelling.asText())) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * The refusal for a property the document publishes, or would publish by expansion, under
         * {@link #MARKER}: one bounded diagnostic naming the type and the reserved name, differing only
         * in the rename it suggests.
         *
         * @param type   the type being generated
         * @param remedy the rename that resolves the collision
         * @return the failure to throw
         */
        private static JsonSchemaGenerationException markerCollision(Type type, String remedy) {
            return Diagnostics.failure(
                    "JSON Schema generation failed for " + Diagnostics.typeIdentity(type)
                            + ": it publishes a property named \"" + MARKER
                            + "\", which is reserved by the generator's alias expansion; " + remedy,
                    null);
        }

        /**
         * Applies and removes every alias plan the document carries, at any depth.
         *
         * @param node the document, or one of its nodes during the descent
         */
        static void expand(JsonNode node) {
            expand(node, newVisitedSet());
        }

        /**
         * The descent of {@link #expand(JsonNode)}, carrying the nodes it has already expanded.
         *
         * <p>A node reached twice is expanded once: the second visit would find its plan already
         * removed, so the guard changes no document and only bounds a descent the pinned schema library
         * cannot currently make cyclic, because it emits a reference rather than sharing a node.
         *
         * @param node    the document, or one of its nodes during the descent
         * @param visited the nodes already expanded, by identity
         */
        private static void expand(JsonNode node, Set<JsonNode> visited) {
            if (node == null || !node.isContainerNode() || !visited.add(node)) {
                return;
            }
            if (node instanceof ObjectNode object) {
                JsonNode marker = object.remove(MARKER);
                if (marker != null) {
                    apply(object, marker);
                }
            }
            for (JsonNode child : children(node)) {
                expand(child, visited);
            }
        }

        /**
         * A fresh set of document nodes compared by identity, so two structurally equal definitions are
         * still walked separately and only a node literally reached twice is skipped.
         *
         * @return the empty visited set
         */
        private static Set<JsonNode> newVisitedSet() {
            return Collections.newSetFromMap(new IdentityHashMap<>());
        }

        /**
         * The child nodes of a container, copied out before the descent so that a node removed or added
         * during expansion never invalidates the iteration.
         *
         * @param node the container node
         * @return its children, in document order
         */
        private static List<JsonNode> children(JsonNode node) {
            List<JsonNode> children = new ArrayList<>();
            node.elements().forEachRemaining(children::add);
            return children;
        }

        /**
         * Publishes one definition's alias spellings and appends its rules.
         *
         * <p>A spelling is published only where the owning property's own entry exists in the finished
         * {@code properties} object, and never over an entry already published under that spelling, so
         * expansion never displaces another property's description.
         *
         * @param definition the definition the plan was carried in
         * @param marker     the plan itself, already removed from the definition
         */
        private static void apply(ObjectNode definition, JsonNode marker) {
            boolean strict = marker.path(STRICT).asBoolean(false);
            JsonNode properties = definition.get("properties");
            if (properties == null || !properties.isObject()) {
                return;
            }
            ObjectNode published = (ObjectNode) properties;
            List<JsonNode> rules = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> plans = marker.path(ALIASES).fields();
            while (plans.hasNext()) {
                Map.Entry<String, JsonNode> plan = plans.next();
                String wireName = plan.getKey();
                JsonNode own = published.get(wireName);
                if (own == null) {
                    // The property's own wire name is not published here, so there is no schema to copy
                    // and nothing to state a rule about; its spellings stay reserved instead.
                    continue;
                }
                List<String> spellings = new ArrayList<>();
                spellings.add(wireName);
                for (JsonNode alias : plan.getValue()) {
                    String spelling = alias.asText();
                    if (published.has(spelling)) {
                        // Never displace another property's own description.
                        continue;
                    }
                    published.set(spelling, own.deepCopy());
                    spellings.add(spelling);
                }
                if (spellings.size() == 1) {
                    continue;
                }
                JsonNode rule = ruleFor(definition, wireName, spellings, strict);
                if (rule != null) {
                    rules.add(rule);
                }
            }
            appendRules(definition, rules);
        }

        /**
         * The rule stating which of a property's spellings may appear, or {@code null} when the profile
         * needs none: a lenient profile leaves an optional property unconstrained, because its mapper
         * accepts every spelling the binder sees.
         *
         * @param definition the definition, whose top-level {@code required} list an aliased property
         *                   leaves because the rule states its requirement instead
         * @param wireName   the property's own wire name
         * @param spellings  its own wire name followed by every published spelling
         * @param strict     whether the profile forbids several spellings at once
         * @return the rule, or {@code null} when none is needed
         */
        private static JsonNode ruleFor(
                ObjectNode definition, String wireName, List<String> spellings, boolean strict) {
            boolean required = removeRequired(definition, wireName);
            if (required) {
                ObjectNode rule = JsonNodeFactory.instance.objectNode();
                ArrayNode branches = rule.putArray(strict ? "oneOf" : "anyOf");
                spellings.forEach(spelling -> branches.add(requiredBranch(spelling)));
                return rule;
            }
            if (!strict) {
                return null;
            }
            ObjectNode rule = JsonNodeFactory.instance.objectNode();
            ArrayNode branches = rule.putArray("oneOf");
            spellings.forEach(spelling -> branches.add(requiredBranch(spelling)));
            // The none-present branch: without it a body omitting the property entirely satisfies no
            // branch, and an optional property would have become required.
            ObjectNode nonePresent = JsonNodeFactory.instance.objectNode();
            ArrayNode present = nonePresent.putObject("not").putArray("anyOf");
            spellings.forEach(spelling -> present.add(requiredBranch(spelling)));
            branches.add(nonePresent);
            return rule;
        }

        /**
         * Appends a definition's rules: combined in one {@code allOf}, or standing alone when there is
         * exactly one rule and the definition does not already carry its keyword.
         *
         * @param definition the definition being expanded
         * @param rules      the rules to append, possibly empty
         */
        private static void appendRules(ObjectNode definition, List<JsonNode> rules) {
            if (rules.isEmpty()) {
                return;
            }
            if (rules.size() == 1) {
                String keyword = rules.get(0).fieldNames().next();
                if (!definition.has(keyword)) {
                    definition.setAll((ObjectNode) rules.get(0));
                    return;
                }
            }
            JsonNode allOf = definition.get("allOf");
            ArrayNode target = allOf != null && allOf.isArray() ? (ArrayNode) allOf : definition.putArray("allOf");
            rules.forEach(target::add);
        }

        /**
         * One rule branch: a spelling being present, carrying {@code required} alone, because the MCP
         * hardener closes any object that carries {@code properties}.
         *
         * @param spelling the spelling the branch requires
         * @return the branch
         */
        private static ObjectNode requiredBranch(String spelling) {
            ObjectNode branch = JsonNodeFactory.instance.objectNode();
            branch.putArray("required").add(spelling);
            return branch;
        }

        /**
         * Removes a property's own wire name from the definition's top-level {@code required} list,
         * reporting whether it was there, and drops an emptied list entirely.
         *
         * @param definition the definition being expanded
         * @param wireName   the property's own wire name
         * @return {@code true} when the property was required
         */
        private static boolean removeRequired(ObjectNode definition, String wireName) {
            JsonNode required = definition.get("required");
            if (required == null || !required.isArray()) {
                return false;
            }
            ArrayNode names = (ArrayNode) required;
            boolean found = false;
            for (int index = names.size() - 1; index >= 0; index--) {
                if (wireName.equals(names.get(index).asText())) {
                    names.remove(index);
                    found = true;
                }
            }
            if (names.isEmpty()) {
                definition.remove("required");
            }
            return found;
        }
    }
}
