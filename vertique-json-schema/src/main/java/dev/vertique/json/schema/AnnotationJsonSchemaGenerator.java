// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import jakarta.validation.Validator;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

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
 * through its builder rather than through the field: a builder method's own constraint is borrowed
 * from the built type's Jackson-introspected property of the same wire name — unconditionally, for
 * any builder, when that property has a getter and a backing field; only when it has no getter does
 * the borrow require {@link BuilderBorrowDetector} to judge it sound (a Lombok {@code @Builder
 * @Jacksonized} type, or the exact shape it generates). A property Jackson's introspection reports no
 * accessor for at all — a Lombok {@code @Builder} type's constrained private field with no {@code
 * @Getter} (BG1) — still
 * publishes by type, with no borrowed constraint, since the floor has nothing to join it by; a {@link
 * jakarta.validation.Validator} supplement, when one is active, still renders the constraint for that
 * one property by its own field-name join, independent of the floor's own borrow. The backing storage
 * of an any-setter or an any-getter is never described as a named property, and
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

    /** The input direction's property source, or {@code null} in the other construction modes. */
    private final InputPropertyDescriber describer;

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
        this(generator, false, null);
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
        this(generator, suppressInapplicableNumericKeywords, null);
    }

    private AnnotationJsonSchemaGenerator(
            SchemaGenerator generator, boolean suppressInapplicableNumericKeywords, InputPropertyDescriber describer) {
        this.generator = requirePinnedDialect(generator);
        this.suppressInapplicableNumericKeywords = suppressInapplicableNumericKeywords;
        this.describer = describer;
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
     *                                        override declaration is invalid, if a fragment applying
     *                                        in this direction carries the generator's reserved
     *                                        alias-expansion keyword on a schema object, or if the
     *                                        profile declares a duplicate effective override mapping
     *                                        for this direction
     */
    public static AnnotationJsonSchemaGenerator forInputProfile(JsonMapperProfile profile) {
        return forProfile(profile, Direction.INPUT, null);
    }

    /**
     * Constructs an input-direction generator exactly as {@link #forInputProfile(JsonMapperProfile)}
     * does, except that value-schema constraints are additionally read from Bean Validation metadata
     * ({@code validator.getConstraintsForClass}), whenever a non-null {@code validator} is supplied.
     *
     * <p>Bean Validation is an optional dependency: an application without a {@link Validator}
     * available passes {@code null} (or calls the single-argument overload), and generation is
     * unchanged from before this overload existed. When a validator is supplied, the schema library's
     * own Jakarta Validation module still runs unconditionally for every scoped member — it is the
     * floor every generation mode shares — and the Bean Validation metadata source only supplements
     * or, for a bounded set of shapes, corrects what it rendered; the two are never in conflict by
     * construction, never by one disabling the other — see {@code ConstraintSource} for the join
     * rules and the group filter.
     *
     * @param profile   the resolved JSON mapper profile whose mapper and input-applicable overrides
     *                  drive generation; must not be {@code null}
     * @param validator the Bean Validation validator to source constraints from, or {@code null} to
     *                  use the annotation walk
     * @return a generator configured for the profile's input direction
     * @throws NullPointerException          if {@code profile} is {@code null}
     * @throws JsonSchemaGenerationException if the profile's id, mapper, override list, or an
     *                                        override declaration is invalid, if a fragment applying
     *                                        in this direction carries the generator's reserved
     *                                        alias-expansion keyword on a schema object, or if the
     *                                        profile declares a duplicate effective override mapping
     *                                        for this direction
     */
    public static AnnotationJsonSchemaGenerator forInputProfile(JsonMapperProfile profile, Validator validator) {
        return forProfile(profile, Direction.INPUT, validator);
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
     *                                        override declaration is invalid, if a fragment applying
     *                                        in this direction carries the generator's reserved
     *                                        alias-expansion keyword on a schema object, or if the
     *                                        profile declares a duplicate effective override mapping
     *                                        for this direction
     */
    public static AnnotationJsonSchemaGenerator forOutputProfile(JsonMapperProfile profile) {
        return forProfile(profile, Direction.OUTPUT, null);
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
    private static AnnotationJsonSchemaGenerator forProfile(
            JsonMapperProfile profile, Direction direction, Validator validator) {
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
        InputPropertyDescriber describer = null;
        OutputPropertyNameResolver outputNames = null;
        // Bean Validation metadata supplements the input direction only: it is the direction
        // InputPropertyDescriber already owns the join for, and the output direction's own
        // OutputPropertyNameResolver has no equivalent join to a Validator's property descriptors. The
        // Jakarta Validation module is always installed as the floor regardless (see build() below);
        // null here means "no supplement", not "no constraints".
        ConstraintSource supplement =
                direction == Direction.INPUT && validator != null ? new MetadataConstraintSource(validator) : null;
        if (direction == Direction.INPUT) {
            // Read once, from the profile's own mapper instance: the same one that parses a body at the
            // REST gate, so the published rule and the binder's parse decision cannot disagree.
            boolean strict = validated.mapper().getFactory().isEnabled(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            describer = new InputPropertyDescriber(validated.mapper(), strict, supplement, validated);
        } else {
            outputNames = new OutputPropertyNameResolver(validated.mapper());
        }
        return new AnnotationJsonSchemaGenerator(build(builder, describer, outputNames), hasOverrides, describer);
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
        return build(builder, null, null);
    }

    /**
     * Installs the shared annotation modules and, for profile-aware generation, the direction's
     * property source.
     *
     * <p>The input direction describes an object from the profile mapper's resolved deserializer
     * through {@link InputPropertyDescriber}, registered as a type definition provider <em>after</em>
     * the modules: the Jackson module's subtype resolver keeps precedence for a polymorphic root and
     * consults the describer for each concrete subtype, and the profile override provider, registered
     * before the modules, keeps precedence for an overridden class. The output direction keeps the
     * schema library's own walk and only projects the mapper's serialization names and visibility onto
     * it through {@link OutputPropertyNameResolver}, because Victools' Jackson module reads annotations
     * but not an {@link ObjectMapper}-level naming strategy.
     *
     * <p>The Jakarta Validation module ({@code NOT_NULLABLE_FIELD_IS_REQUIRED}, {@code
     * INCLUDE_PATTERN_EXPRESSIONS}) is installed <strong>unconditionally, in every mode</strong> — it
     * is the floor: whatever it renders for a scoped field or getter is rendered whether or not a
     * {@link jakarta.validation.Validator} is supplied. A {@link MetadataConstraintSource} supplement,
     * present only when a {@code Validator} is supplied, is consulted on top of that floor by {@link
     * InputPropertyDescriber} — never in place of it — so a document generated with a validator still
     * renders every keyword {@code main} (no validator) would have rendered; see {@link
     * ConstraintSource} and {@link ResolvedConstraints} for the addition/correction split that keeps
     * the two from conflicting.
     */
    private static SchemaGenerator build(
            SchemaGeneratorConfigBuilder builder,
            InputPropertyDescriber describer,
            OutputPropertyNameResolver outputNames) {
        builder.with(new JacksonModule());
        builder.with(new JakartaValidationModule(
                JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS));
        builder.with(new Swagger2Module());
        if (describer != null) {
            builder.forTypesInGeneral().withCustomDefinitionProvider(describer);
        }
        if (outputNames != null) {
            builder.forFields()
                    .withIgnoreCheck(outputNames::isIgnored)
                    .withPropertyNameOverrideResolver(outputNames::resolve);
            builder.forMethods()
                    .withIgnoreCheck(outputNames::isIgnored)
                    .withPropertyNameOverrideResolver(outputNames::resolve);
        }
        return new SchemaGenerator(builder.build());
    }

    /**
     * The output direction's view of a type: the serialization names the profile's mapper materializes
     * and the members it serializes, keyed by the exact member Victools is publishing, with the
     * property's internal and wire name as the fallback for a member the introspection does not link.
     */
    private static final class OutputPropertyNameResolver {
        private final ObjectMapper mapper;

        private record PropertyMetadata(String wireName, boolean visible) {}

        private record PropertyNames(
                Map<Member, PropertyMetadata> byMember,
                Map<String, PropertyMetadata> byInternalName,
                Map<String, Set<Member>> walkedFieldsByWireName) {}

        private final ClassValue<PropertyNames> namesByType = new ClassValue<>() {
            @Override
            protected PropertyNames computeValue(Class<?> type) {
                return introspect(type);
            }
        };

        private OutputPropertyNameResolver(ObjectMapper mapper) {
            this.mapper = mapper;
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
            PropertyMetadata byMember = names.byMember().get(scope.getRawMember());
            if (byMember != null) {
                return byMember;
            }
            PropertyMetadata byName = names.byInternalName().get(scope.getName());
            if (publishesTwoMembersUnderOneName(names, scope, byName)) {
                throw renameCollision(scope, byName);
            }
            return byName;
        }

        /**
         * Whether answering a renamed member with the property its rename lands on would publish two
         * walked members under one name: a {@code @Schema(name = ...)} landing on a property backed by
         * another field the schema library walks is the developer's error to resolve, while a
         * landed-on property no other walked field backs — the accessor pair behind a Lombok-style
         * {@code boolean isActive} — is described by this member alone.
         */
        private static boolean publishesTwoMembersUnderOneName(
                PropertyNames names, MemberScope<?, ?> scope, PropertyMetadata landedOn) {
            if (landedOn == null || scope.getName().equals(scope.getDeclaredName())) {
                return false;
            }
            if (landedOn.equals(names.byInternalName().get(scope.getDeclaredName()))) {
                return false;
            }
            for (Member field : names.walkedFieldsByWireName().getOrDefault(landedOn.wireName(), Set.of())) {
                if (!field.equals(scope.getRawMember())) {
                    return true;
                }
            }
            return false;
        }

        private static JsonSchemaGenerationException renameCollision(
                MemberScope<?, ?> scope, PropertyMetadata occupant) {
            return Diagnostics.failure(
                    "JSON Schema generation failed for "
                            + Diagnostics.typeIdentity(scope.getDeclaringType().getErasedType())
                            + ": member \""
                            + Diagnostics.truncate(scope.getDeclaredName(), Diagnostics.MAX_SHORT_IDENTITY_LENGTH)
                            + "\" is renamed to \""
                            + Diagnostics.truncate(scope.getName(), Diagnostics.MAX_SHORT_IDENTITY_LENGTH)
                            + "\", which another property already carries (whose wire name is \""
                            + Diagnostics.truncate(occupant.wireName(), Diagnostics.MAX_SHORT_IDENTITY_LENGTH)
                            + "\"); rename one of the two properties, or name them apart on the wire with"
                            + " @JsonProperty",
                    null);
        }

        /** The fields the schema library walks, grouped by the wire name each is described under. */
        private static Map<String, Set<Member>> walkedFieldsByWireName(
                Class<?> type, Map<Member, PropertyMetadata> members, Map<String, PropertyMetadata> internalNames) {
            Map<String, Set<Member>> fields = new HashMap<>();
            for (Class<?> current = type;
                    current != null && current != Object.class;
                    current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                        continue;
                    }
                    PropertyMetadata property = members.get(field);
                    if (property == null) {
                        property = internalNames.get(field.getName());
                    }
                    if (property != null && property.wireName() != null) {
                        fields.computeIfAbsent(property.wireName(), key -> new HashSet<>())
                                .add(field);
                    }
                }
            }
            Map<String, Set<Member>> copy = new HashMap<>();
            fields.forEach((wireName, backing) -> copy.put(wireName, Set.copyOf(backing)));
            return Map.copyOf(copy);
        }

        private PropertyNames introspect(Class<?> type) {
            JavaType javaType = mapper.getTypeFactory().constructType(type);
            BeanDescription description = mapper.getSerializationConfig().introspect(javaType);
            AnnotationIntrospector introspector =
                    mapper.getSerializationConfig().getAnnotationIntrospector();
            Map<Member, PropertyMetadata> members = new HashMap<>();
            Map<String, PropertyMetadata> internalNames = new HashMap<>();
            for (BeanPropertyDefinition property : description.findProperties()) {
                boolean visible = property.couldSerialize()
                        && propertyAccess(introspector, property) != JsonProperty.Access.WRITE_ONLY;
                PropertyMetadata metadata = new PropertyMetadata(property.getName(), visible);
                internalNames.put(property.getInternalName(), metadata);
                internalNames.put(property.getName(), metadata);
                for (AnnotatedMember member :
                        new AnnotatedMember[] {property.getField(), property.getGetter(), property.getSetter()}) {
                    if (member != null) {
                        members.put(member.getMember(), metadata);
                    }
                }
            }
            return new PropertyNames(
                    Map.copyOf(members),
                    Map.copyOf(internalNames),
                    walkedFieldsByWireName(type, members, internalNames));
        }

        private static JsonProperty.Access propertyAccess(
                AnnotationIntrospector introspector, BeanPropertyDefinition property) {
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
                // Applied first of the three post-generation passes, once the underlying schema library
                // has fully finished writing to every node (a scoped-member correction — "pattern" under
                // INCLUDE_PATTERN_EXPRESSIONS in particular — cannot be applied any earlier without the
                // library's own later write treating it as a conflicting value; see
                // InputPropertyDescriber#applyScopedConstraints). A no-op in every mode but the input
                // direction with an active Bean Validation supplement, which alone writes the marker.
                InputPropertyDescriber.applyDeferredScopedConstraints(generated);
                // Applied next, so an alias copy of a property schema is taken after its nullability
                // is final; a no-op in every mode but the input direction, which alone writes the mark.
                InputPropertyDescriber.applyNullability(generated);
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
        if (describer != null) {
            describer.resetAfterAbortedGeneration();
        }
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
         * under this exact wire name is refused by {@link #requireNoMarkerPropertyName}, and a profile
         * override fragment carrying it on a schema object is refused when the profile is validated
         * (see {@link #fragmentCarriesMarker}), so the only occurrences expansion strips at a schema
         * position are the plans the generator wrote. Literal data — the value of a {@code const},
         * {@code enum}, {@code default}, {@code examples} or {@code example} member — is never
         * inspected, stripped, or executed.
         */
        static final String MARKER = "x-vertique-alias-plan";

        /** The plan member carrying whether the profile's mapper forbids several spellings. */
        static final String STRICT = "strict";

        /** The plan member carrying each aliased property's spellings, keyed by its own wire name. */
        static final String ALIASES = "aliases";

        /**
         * The keywords whose value is JSON data rather than schema: none of the walks descends into
         * one, so a data object carrying {@link #MARKER} is neither stripped, executed as a plan, nor
         * read as a published property.
         *
         * <p>An exclusion rather than an allowlist of subschema keywords, deliberately: expansion must
         * reach every position the generator places a plan at, and an allowlist that missed one would
         * leave that plan unexpanded and its aliases undescribed.
         */
        private static final Set<String> LITERAL_KEYWORDS = Set.of("const", "enum", "default", "examples", "example");

        /**
         * The keywords whose value is an object keyed by names rather than by keywords: its members are
         * schemas whatever they are called, so a property named {@code const} is still descended.
         */
        private static final Set<String> NAMED_MEMBER_KEYWORDS =
                Set.of("properties", "patternProperties", "$defs", "dependentSchemas");

        private AliasExpansion() {}

        /**
         * Refuses a document in which some object would publish a property whose wire name is exactly
         * {@link #MARKER} — whether it already publishes one, or whether expansion would publish one
         * under that name for an alias spelling.
         *
         * <p>The keyword is reserved to the plan, so a property published under it would share its name
         * with the generator's own bookkeeping, and a spelling equal to it would be published by the
         * very expansion that reads the plan naming it. Both are refused here, before expansion runs,
         * in every construction mode. The plan itself is written as a direct child of a definition
         * node and never as a key inside a {@code properties} object, so only the genuine collision is
         * caught. The descent visits schema positions only: a {@code properties} object or a plan
         * inside literal data is data and is never refused.
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
            walkSchemaPositions(node, false, visited, schema -> {
                JsonNode properties = schema.get("properties");
                if (properties != null && properties.isObject() && properties.has(MARKER)) {
                    throw markerCollision(type, "rename the property on the wire (for example with @JsonProperty)");
                }
                if (planListsMarkerSpelling(schema.get(MARKER))) {
                    throw markerCollision(
                            type,
                            "rename the alias spelling expansion would publish under it (for example with"
                                    + " @JsonAlias)");
                }
            });
        }

        /**
         * Whether a profile override fragment carries {@link #MARKER} as a member of a schema object at
         * any depth. Such a member is not a plan the generator wrote: expansion would silently strip
         * it, or execute it as a plan against the enclosing schema, so the fragment is refused when the
         * profile is validated. Literal data is not inspected, so a {@code const} or {@code enum} value
         * may carry the keyword as an ordinary member.
         *
         * @param fragment the parsed fragment
         * @return {@code true} when some schema object in the fragment carries the keyword
         */
        static boolean fragmentCarriesMarker(JsonNode fragment) {
            boolean[] found = {false};
            walkSchemaPositions(
                    fragment,
                    false,
                    newVisitedSet(),
                    schema -> found[0] |= schema.has(MARKER)
                            || schema.has(InputPropertyDescriber.NULLABLE_MARKER)
                            || schema.has(InputPropertyDescriber.SCOPED_CONSTRAINTS_MARKER));
            return found[0];
        }

        /**
         * Visits every object at a schema position of a document, parent before children, never
         * entering literal data; the entry point the describer's post-generation walk shares.
         *
         * @param node    the document
         * @param visitor the action applied to each schema object
         */
        static void walkSchemaPositions(JsonNode node, Consumer<ObjectNode> visitor) {
            walkSchemaPositions(node, false, newVisitedSet(), visitor);
        }

        /**
         * Visits every object at a schema position of a document, parent before children, and never
         * enters literal data.
         *
         * <p>A member of a schema object is descended unless its keyword is one of {@link
         * #LITERAL_KEYWORDS}; a member of a {@link #NAMED_MEMBER_KEYWORDS} object is a schema whatever
         * its name, so it is always descended and the object itself, which is not a schema, is not
         * visited. An array's elements are descended, because an array reached here is a list of
         * subschemas. Children are read after the visitor runs, so a member the visitor adds is walked
         * and a member it removes is not.
         *
         * @param node         the node to walk
         * @param namedMembers whether {@code node} is an object keyed by names rather than keywords
         * @param visited      the nodes already walked, by identity
         * @param visitor      the action applied to each schema object
         */
        private static void walkSchemaPositions(
                JsonNode node, boolean namedMembers, Set<JsonNode> visited, Consumer<ObjectNode> visitor) {
            if (node == null || !node.isContainerNode() || !visited.add(node)) {
                return;
            }
            if (!(node instanceof ObjectNode object)) {
                for (JsonNode element : children(node)) {
                    walkSchemaPositions(element, false, visited, visitor);
                }
                return;
            }
            if (!namedMembers) {
                visitor.accept(object);
            }
            for (Map.Entry<String, JsonNode> member : new ArrayList<>(object.properties())) {
                String keyword = member.getKey();
                JsonNode value = member.getValue();
                if (namedMembers) {
                    walkSchemaPositions(value, false, visited, visitor);
                } else if (!LITERAL_KEYWORDS.contains(keyword)) {
                    walkSchemaPositions(
                            value, NAMED_MEMBER_KEYWORDS.contains(keyword) && value.isObject(), visited, visitor);
                }
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
         * Applies and removes every alias plan the document carries, at any schema position and any
         * depth. Literal data — the value of a {@link #LITERAL_KEYWORDS} member — is never entered.
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
            walkSchemaPositions(node, false, visited, schema -> {
                JsonNode marker = schema.remove(MARKER);
                if (marker != null) {
                    apply(schema, marker);
                }
            });
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
