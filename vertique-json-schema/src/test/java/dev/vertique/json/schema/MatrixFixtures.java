// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fixture types for {@link SchemaFixtureMatrixTest}: one type per FR-JSON-081 baseline matrix row,
 * plus the fixtures backing the determinism, divergence, and wire-honesty proofs in the same class.
 *
 * <p>Every DTO exposes public non-static fields, matching the shape the rest of this package's
 * fixtures use, so generation exercises the same Victools property-discovery path REST exercises
 * today.
 */
final class MatrixFixtures {

    private MatrixFixtures() {}

    // --- ordinaryPojo ---

    /** An ordinary POJO with a required field and a length-constrained field. */
    static final class OrdinaryPojo {

        /** Required by {@code NOT_NULLABLE_FIELD_IS_REQUIRED}. */
        @NotNull
        public String name;

        /** Bounded string, exercising {@code minLength}/{@code maxLength}. */
        @Size(min = 2, max = 8)
        public String code;
    }

    // --- record ---

    /**
     * A Java record whose canonical constructor components become the generated schema's
     * properties.
     *
     * @param name the record's first component
     * @param age  the record's second component
     */
    record RecordDto(String name, int age) {}

    // --- inheritance ---

    /** Base type contributing one inherited property. */
    static class AnimalBase {

        /** The inherited property a subclass schema must still carry. */
        public String species;
    }

    /** Subtype adding its own property alongside the inherited one. */
    static final class DogSubtype extends AnimalBase {

        /** The subtype's own property. */
        public int legs;
    }

    // --- nestedGenericCollections ---

    /** Holder whose field resolves to a doubly-nested generic collection, {@code List<Set<String>>}. */
    static final class NestedGenericHolder {

        /** {@code List<Set<String>>}: an array of arrays of strings. */
        public List<Set<String>> tags;
    }

    // --- resolvedMap ---

    /** Holder whose field resolves to {@code Map<String, Integer>}. */
    static final class MapHolder {

        /** A resolved map property (S4 as-built fact: Victools 4.38.0 emits a bare object type). */
        public Map<String, Integer> counts;
    }

    // --- optional ---

    /** Holder whose field resolves to {@code Optional<String>}. */
    static final class OptionalHolder {

        /** An optional property; {@code FLATTENED_OPTIONALS} unwraps it to its value type's schema. */
        public Optional<String> nickname;
    }

    // --- enums ---

    /** A three-constant enum used by {@link EnumHolder}. */
    enum Color {

        /** Red. */
        RED,
        /** Green. */
        GREEN,
        /** Blue. */
        BLUE
    }

    /** Holder whose field resolves to an enum type. */
    static final class EnumHolder {

        /** An enum-typed property; {@code FLATTENED_ENUMS} emits a string type with an enum list. */
        public Color color;
    }

    // --- temporal ---

    /** Holder carrying two {@code java.time} temporal properties. */
    static final class TemporalHolder {

        /** An {@link Instant}-typed property. */
        public Instant createdAt;

        /** A {@link LocalDate}-typed property. */
        public LocalDate bornOn;
    }

    // --- jacksonMetadata ---

    /** DTO exercising {@code @JsonProperty} renaming and {@code @JsonIgnore} exclusion. */
    static final class JacksonMetadataDto {

        /** Renamed on the wire to {@code full_name}. */
        @JsonProperty("full_name")
        public String name;

        /** Excluded from the generated schema entirely. */
        @JsonIgnore
        public String secret;
    }

    // --- jakartaConstraints ---

    /** DTO exercising {@code @Min}/{@code @Max}, {@code @Pattern}, and {@code @NotNull}. */
    static final class JakartaConstraintsDto {

        /** Bounded integer, exercising {@code minimum}/{@code maximum}. */
        @Min(1)
        @Max(100)
        public int quantity;

        /** Pattern-constrained string. Deliberately contains the {@code pattern} keyword's 'a'. */
        @Pattern(regexp = "^[A-Z]+$")
        public String code;

        /** Required by {@code NOT_NULLABLE_FIELD_IS_REQUIRED} and {@code @NotNull} alike. */
        @NotNull
        public String required;
    }

    // --- swaggerMetadata ---

    /** DTO exercising {@code @Schema} description/title/minLength metadata. */
    static final class SwaggerMetadataDto {

        /** Carries a description and a title. */
        @Schema(description = "the item label", title = "Label")
        public String label;

        /** Carries a minimum length. */
        @Schema(minLength = 3)
        public String code;
    }

    // --- closedPolymorphism ---

    /** Closed annotated polymorphism base: exactly two declared subtypes. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Car.class, name = "car"),
        @JsonSubTypes.Type(value = Truck.class, name = "truck")
    })
    abstract static class Vehicle {

        /** Property common to every subtype. */
        public String plate;
    }

    /** Concrete {@code car} subtype. */
    static final class Car extends Vehicle {

        /** Seat count. */
        public int seats;
    }

    /** Concrete {@code truck} subtype. */
    static final class Truck extends Vehicle {

        /** Payload capacity in tons. */
        public double payloadTons;
    }

    // --- recursiveGraph ---

    /** Self-referencing node type: a recursive object graph, not an unresolved recursive type. */
    static final class TreeNode {

        /** The node's own value. */
        public String value;

        /** The recursive self-reference. */
        public TreeNode parent;
    }

    // --- wire-honesty fixture (jakartaConstraintInapplicableToWireTypeNotEmitted) ---

    /** DTO whose {@link BigDecimal} property carries a Jakarta numeric-domain constraint. */
    static final class DecimalMinDto {

        /** Under the strict profile this property's wire type is a string, not a number. */
        @DecimalMin("0.01")
        public BigDecimal amount;
    }

    // --- property-model divergence fixture (differentMapperConfigurationsDivergeExplicitly) ---

    /** DTO whose single property is visible only under field-based Jackson visibility. */
    static final class VisibilityDto {

        /** Visible under default (field-detecting) visibility; invisible when fields are hidden. */
        public String visibleField;
    }
}
