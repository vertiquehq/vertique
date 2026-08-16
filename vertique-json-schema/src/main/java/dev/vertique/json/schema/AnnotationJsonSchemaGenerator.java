// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.MethodScope;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import com.github.victools.jsonschema.module.swagger2.Swagger2Module;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonSchemaTypeOverride.Direction;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;

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
 * <p>A profile's declared {@code JsonSchemaTypeOverride}s narrow how the generator represents an
 * exact Java class: the override fragment defines the baseline wire contract for that class, and
 * property-level Swagger schema metadata or applicable Jakarta constraints may further narrow —
 * never replace or broaden — that baseline. Where both the profile and a property contribute the
 * same single-valued keyword, generation represents their conjunction explicitly rather than
 * relying on registration or keyword-merge order; a detectable structural conflict fails
 * generation with {@link JsonSchemaGenerationException} instead of producing an unsatisfiable
 * contract. A non-default {@code @Schema(implementation = ...)} on a property whose declared type
 * graph carries an effective override also fails generation, because the pinned Victools version
 * silently drops the override fragment once {@code implementation} redirects the member's
 * resolved type.
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
 */
public final class AnnotationJsonSchemaGenerator {

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
     * @param generator the configured Victools generator this instance owns for its lifetime
     */
    AnnotationJsonSchemaGenerator(SchemaGenerator generator) {
        this(generator, false);
    }

    /**
     * Wraps an already-configured Victools generator, optionally applying
     * {@link NumericDomainKeywordFilter} to every document this instance generates.
     *
     * @param generator                           the configured Victools generator this instance owns
     *                                             for its lifetime
     * @param suppressInapplicableNumericKeywords  whether to run {@link NumericDomainKeywordFilter}
     *                                             after generation and before canonicalization
     */
    AnnotationJsonSchemaGenerator(SchemaGenerator generator, boolean suppressInapplicableNumericKeywords) {
        this.generator = generator;
        this.suppressInapplicableNumericKeywords = suppressInapplicableNumericKeywords;
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
        return new AnnotationJsonSchemaGenerator(build(builder), hasOverrides);
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
        builder.with(new JacksonModule())
                .with(new JakartaValidationModule(
                        JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                        JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS))
                .with(new Swagger2Module());
        return new SchemaGenerator(builder.build());
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
     * documents across independent generator instances and repeated calls.
     *
     * @param type the resolved Java type to generate a schema for; must not be {@code null}
     * @return the canonical, compact Draft 2020-12 JSON Schema document as a {@code String}
     * @throws JsonSchemaGenerationException if {@code type} is outside the accepted grammar, or if
     *                                        generation, override application, conflict detection,
     *                                        or canonicalization fails
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
            } catch (JsonSchemaGenerationException alreadyBounded) {
                throw alreadyBounded;
            } catch (RuntimeException failed) {
                throw Diagnostics.failure(
                        "JSON Schema generation failed for " + Diagnostics.typeIdentity(type), failed);
            }
            // Structural safety net: a document that conjoins disjoint explicit types is unsatisfiable,
            // and is refused before it can be canonicalized and handed to a consumer.
            DisjointTypeDetector.requireNoDisjointTypes(generated);
            if (suppressInapplicableNumericKeywords) {
                // PRD §6.2 wire-honesty: a Jakarta numeric-domain constraint (e.g. @DecimalMin) must
                // not be advertised against a wire type an override has replaced with a non-number.
                NumericDomainKeywordFilter.suppressInapplicableNumericKeywords(generated);
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
}
