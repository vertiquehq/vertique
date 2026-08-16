// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fixtures for the generator hardening contracts: the accepted and rejected {@link Type} grammar,
 * profile construction validation, direction filtering, exact-class matching, map-key exclusion, the
 * {@code @Schema(implementation = ...)} × override guard, and disjoint-type conflict detection.
 *
 * <p>Every schema-override fragment used here carries a unique {@code format} marker so a test can
 * assert the fragment's presence or absence by looking for that marker in the canonical document,
 * without depending on the exact post-{@code ALLOF_CLEANUP_AT_THE_END} normal form.
 */
final class HardeningFixtures {

    /**
     * A value carried by a fixture's Swagger metadata. No bounded diagnostic may ever echo it — the
     * rejection messages carry type identity only.
     */
    static final String SENTINEL_VALUE = "s3cr3t-fixture-value";

    /** Marker in the fragment declared for the {@code INPUT} direction only. */
    static final String INPUT_MARKER = "input-only-marker";

    /** Marker in the fragment declared for the {@code OUTPUT} direction only. */
    static final String OUTPUT_MARKER = "output-only-marker";

    /** Marker in the fragment declared for the exact-class matching fixture. */
    static final String EXACT_CLASS_MARKER = "exact-class-marker";

    /** Marker in the fragment declared for the map key/value position fixture. */
    static final String MAP_POSITION_MARKER = "map-position-marker";

    /** Marker in a fragment whose declared JSON type is deliberately disjoint from an object. */
    static final String DISJOINT_MARKER = "disjoint-marker";

    private HardeningFixtures() {}

    // --- Profiles ---

    /**
     * Resolves the real built-in {@code vertique-strict} profile, whose only declared override is a
     * {@code BOTH}-direction {@code BigDecimal} decimal-string fragment.
     *
     * @return the {@code vertique-strict} profile
     */
    static JsonMapperProfile strictProfile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique-strict"));
    }

    /**
     * Builds a well-formed profile declaring the given overrides.
     *
     * @param id        the profile id
     * @param overrides the declared overrides
     * @return the profile
     */
    static JsonMapperProfile profile(String id, List<JsonSchemaTypeOverride> overrides) {
        return new JsonMapperProfile() {

            @Override
            public JsonProfileId id() {
                return JsonProfileId.of(id);
            }

            @Override
            public ObjectMapper mapper() {
                return new ObjectMapper();
            }

            @Override
            public List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
                return overrides;
            }
        };
    }

    /**
     * Builds a deliberately malformed profile: any of its three members may be {@code null}.
     *
     * @param id        the profile id, possibly {@code null}
     * @param mapper    the mapper, possibly {@code null}
     * @param overrides the override list, possibly {@code null} or containing {@code null}
     * @return the malformed profile
     */
    static JsonMapperProfile malformedProfile(
            JsonProfileId id, ObjectMapper mapper, List<JsonSchemaTypeOverride> overrides) {
        return new JsonMapperProfile() {

            @Override
            public JsonProfileId id() {
                return id;
            }

            @Override
            public ObjectMapper mapper() {
                return mapper;
            }

            @Override
            public List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
                return overrides;
            }
        };
    }

    /**
     * Builds a single-keyword string fragment carrying a unique {@code format} marker.
     *
     * @param marker the marker value
     * @return the parsed fragment
     */
    static JsonSchemaFragment markerFragment(String marker) {
        return JsonSchemaFragment.parse("{\"type\":\"string\",\"format\":\"" + marker + "\"}");
    }

    // --- Accepted-grammar fixtures ---

    /** Plain body DTO used as the accepted {@code Class} form. */
    static final class SimpleDto {

        /** An ordinary string property. */
        public String label;
    }

    /** Declaring type of the {@code Owner.Nested<String>} owner-type fixture. */
    static final class Owner {

        private Owner() {}

        /**
         * A static member class, so a parameterized reference to it carries {@link Owner} as its
         * reflection owner type.
         *
         * @param <T> the element type
         */
        static final class Nested<T> {

            /** The single carried value. */
            public T value;
        }
    }

    /** Holder whose fields supply the reflection-derived {@link Type} forms the contract tests use. */
    static final class TypeHolder {

        /** Supplies a {@link java.lang.reflect.GenericArrayType} through {@code getGenericType()}. */
        public List<String>[] matrix;

        /** Supplies a {@code List<?>} and, through its argument, a {@link java.lang.reflect.WildcardType}. */
        public List<?> wildcards;

        /** Supplies a {@link ParameterizedType} with a non-null owner type. */
        public Owner.Nested<String> nested;

        /** Carries a Swagger value no bounded diagnostic may echo. */
        @Schema(defaultValue = SENTINEL_VALUE)
        public String labelled;
    }

    /**
     * Generic type whose single parameter supplies a {@link java.lang.reflect.TypeVariable}.
     *
     * @param <T> the unresolved parameter
     */
    static final class Generic<T> {

        /** The unresolved property. */
        public T value;
    }

    // --- Override matching fixtures ---

    /** Supertype an override is declared for. */
    static class Money {

        /** The amount in minor units. */
        public long minorUnits;
    }

    /** Subtype of {@link Money}, which exact-class matching must not cover. */
    static final class TaxedMoney extends Money {

        /** The applied tax rate in basis points. */
        public int taxBasisPoints;
    }

    /** Control DTO whose property type is exactly the overridden class. */
    static final class MoneyDto {

        /** A {@link Money}-typed property. */
        public Money money;
    }

    /** DTO whose property type is a subclass of the overridden class. */
    static final class TaxedMoneyDto {

        /** A {@link TaxedMoney}-typed property. */
        public TaxedMoney money;
    }

    // --- Schema.implementation guard fixtures ---

    /** Replacement type a {@code @Schema(implementation = ...)} property redirects to. */
    static final class ReplacementPojo {

        /** A property name unique enough to prove the redirect took effect. */
        public String replacementMarker;
    }

    /** Property whose declared type is directly an overridden class. */
    static final class ImplementationOnOverriddenDto {

        /** Directly overridden declared type. */
        @Schema(implementation = String.class)
        public BigDecimal amount;
    }

    /** Property whose declared type graph carries an overridden class as a collection element. */
    static final class ImplementationOnNestedOverriddenDto {

        /** Nested overridden declared type. */
        @Schema(implementation = String.class)
        public List<BigDecimal> amounts;
    }

    /** Property whose {@code @Schema} lives on the accessor rather than the field. */
    static final class ImplementationOnGetterDto {

        /** The declared, overridden property type. */
        public BigDecimal amount;

        /**
         * Carries the annotation the field deliberately does not, proving the guard resolves
         * annotations with the same field/getter visibility as the Swagger module.
         *
         * @return the amount
         */
        @Schema(implementation = String.class)
        public BigDecimal getAmount() {
            return amount;
        }
    }

    /** Property with an implementation redirect whose declared type carries no override. */
    static final class ImplementationWithoutOverrideDto {

        /** Redirected to {@link ReplacementPojo}; {@code String} carries no override. */
        @Schema(implementation = ReplacementPojo.class)
        public String label;
    }

    /** Property whose only overridden class occupies a map-key position. */
    static final class ImplementationOnMapKeyDto {

        /** Overridden class in the excluded key position only. */
        @Schema(implementation = String.class)
        public Map<BigDecimal, String> byAmount;
    }

    /**
     * Property whose element redirect is declared through {@code @ArraySchema(schema = ...)}, the
     * form the Swagger module resolves only in a fake container item scope.
     */
    static final class ArraySchemaItemImplementationDto {

        /** Element redirect over an overridden element type. */
        @ArraySchema(schema = @Schema(implementation = Number.class))
        public List<BigDecimal> amounts;
    }

    /**
     * Property whose redirect is declared through {@code @ArraySchema(arraySchema = ...)} on a
     * non-container declared type, the form the Swagger module falls back to when no direct
     * {@code @Schema} is present.
     */
    static final class ArraySchemaContainerImplementationDto {

        /** Container-level redirect over an overridden declared type. */
        @ArraySchema(arraySchema = @Schema(implementation = String.class))
        public BigDecimal amount;
    }

    /** Property whose {@code @ArraySchema} element redirect targets an element carrying no override. */
    static final class ArraySchemaWithoutOverrideDto {

        /** Element redirect over a non-overridden element type; must redirect, not fail. */
        @ArraySchema(schema = @Schema(implementation = ReplacementPojo.class))
        public List<String> labels;
    }

    /**
     * Declares the redirected property as an unresolved type variable, so plain JDK reflection reads
     * a {@code TypeVariable} and only a declaring-context resolution recovers the actual class.
     *
     * @param <T> the parameter a subtype binds to the overridden class
     */
    static class InheritedImplementationBase<T> {

        /** Redirected property whose declared class is known only through the binding subtype. */
        @Schema(implementation = Number.class)
        public T amount;
    }

    /** Binds {@link InheritedImplementationBase}'s parameter to the overridden class. */
    static final class InheritedImplementationDto extends InheritedImplementationBase<BigDecimal> {}

    /**
     * Single-parameter holder used to nest a declared type past
     * {@code TypeGrammar.MAX_DEPTH} without hand-writing one class per level.
     *
     * @param <T> the carried type
     */
    static final class Nest<T> {

        /** The carried value. */
        public T value;
    }

    /**
     * Property whose declared type graph nests the overridden class deeper than the guard's supported
     * walk depth, so the bound — not the override — decides the outcome.
     */
    static final class DeeplyNestedImplementationDto {

        /** Overridden class at nesting depth 70, past the supported walk depth of 64. */
        @Schema(implementation = String.class)
        public Nest<
                        Nest<
                                Nest<
                                        Nest<
                                                Nest<
                                                        Nest<
                                                                Nest<
                                                                        Nest<
                                                                                Nest<
                                                                                        Nest<
                                                                                                Nest<
                                                                                                        Nest<
                                                                                                                Nest<
                                                                                                                        Nest<
                                                                                                                                Nest<
                                                                                                                                        Nest<
                                                                                                                                                Nest<
                                                                                                                                                        Nest<
                                                                                                                                                                Nest<
                                                                                                                                                                        Nest<
                                                                                                                                                                                Nest<
                                                                                                                                                                                        Nest<
                                                                                                                                                                                                Nest<
                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        Nest<
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                BigDecimal>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
                deep;
    }

    // --- Composition fixtures ---

    /**
     * Property whose Swagger {@code allOf} contribution is an object type, disjoint with the
     * string-typed profile fragment its declared type carries.
     */
    static final class DisjointAllOfDto {

        /** Overridden as a string by the profile, conjoined with an object schema by the property. */
        @Schema(allOf = {ReplacementPojo.class})
        public BigDecimal amount;
    }

    /**
     * Property whose {@code nullable} metadata produces an {@code anyOf} alternation rather than a
     * conjunction, so its {@code "null"} and {@code "string"} branches are not in conflict.
     */
    static final class NullableOverriddenDto {

        /** Nullable overridden decimal. */
        @Schema(nullable = true)
        public BigDecimal amount;
    }

    // --- Post-generation walk fixtures ---

    /**
     * An {@code $anchor}-style reference: {@code #}-rooted, but not a JSON pointer. The Swagger module
     * publishes a {@code @Schema(ref = ...)} value verbatim, so this reaches the generated document
     * unchanged and any walk that treats it as a pointer would fail on it.
     */
    static final String ANCHOR_REF = "#anchorName";

    /** Property whose Swagger metadata publishes an {@code $anchor}-style reference. */
    static final class AnchorRefDto {

        /** Carries the {@code $anchor}-style reference into the generated document. */
        @Schema(ref = ANCHOR_REF)
        public String label;
    }

    // --- Map position fixtures ---

    /** DTO whose map declares the overridden class as its key type. */
    static final class DecimalKeyedMapDto {

        /** Key position — never receives the fragment. */
        public Map<BigDecimal, String> byAmount;
    }

    /** DTO whose map declares the overridden class as its value type. */
    static final class DecimalValuedMapDto {

        /** Value position — receives the fragment. */
        public Map<String, BigDecimal> amounts;
    }

    // --- Resolved generic types ---

    /**
     * Reads a declared field's generic type from {@link TypeHolder}.
     *
     * @param field the field name
     * @return the field's generic type
     */
    static Type holderType(String field) {
        try {
            return TypeHolder.class.getField(field).getGenericType();
        } catch (NoSuchFieldException missing) {
            throw new IllegalStateException("fixture field '" + field + "' is missing", missing);
        }
    }
}
