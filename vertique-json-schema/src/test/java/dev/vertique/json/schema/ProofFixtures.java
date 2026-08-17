// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.List;

/**
 * Body-type fixtures for {@link AnnotationJsonSchemaGeneratorProofTest}.
 *
 * <p>Every DTO exposes public non-static fields, matching the shape the existing REST
 * {@code AnnotationSchemaSourceTest} fixtures use, so the differential comparison exercises the same
 * Victools property-discovery path REST exercises today.
 */
final class ProofFixtures {

    private ProofFixtures() {}

    // --- Default-mode fixtures ---

    /** Body DTO with one required field and one length-constrained field. */
    static final class ConstrainedDto {

        /** Required by {@code NOT_NULLABLE_FIELD_IS_REQUIRED}. */
        @NotNull
        public String name;

        /** Bounded string, exercising {@code minLength}/{@code maxLength}. */
        @Size(min = 2, max = 10)
        public String code;
    }

    /** Nested object whose own field is required. */
    static final class Address {

        /** Required, non-blank city. */
        @NotBlank
        public String city;
    }

    /** Body DTO with a nested object field (REST differential fixture 1). */
    static final class WithNested {

        /** The nested address object. */
        public Address address;
    }

    /** Element type of the generic-collection body (REST differential fixture 2). */
    static final class ItemDto {

        /** Required stock-keeping unit. */
        @NotNull
        public String sku;

        /** Ordered quantity. */
        public int quantity;
    }

    /** Closed annotated polymorphism base (REST differential fixture 3). */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Dog.class, name = "dog"),
        @JsonSubTypes.Type(value = Cat.class, name = "cat")
    })
    abstract static class Animal {

        /** Required animal name. */
        @NotNull
        public String name;
    }

    /** Concrete {@code dog} subtype. */
    static final class Dog extends Animal {

        /** Whether the dog is a good boy. */
        public boolean goodBoy;
    }

    /** Concrete {@code cat} subtype. */
    static final class Cat extends Animal {

        /** Remaining lives. */
        public int lives;
    }

    /** Body DTO carrying both a swagger {@code ##default} sentinel and a legitimate default. */
    static final class SentinelDto {

        /** Field whose swagger default is the unset-value sentinel. */
        @Schema(defaultValue = "##default")
        public String label;

        /** Field whose swagger default is a real application default that must survive. */
        @Schema(defaultValue = "eur")
        public String currency;
    }

    // --- Profile-override fixtures ---

    /** Body DTO with a plain {@link BigDecimal} property. */
    static final class AmountDto {

        /** The decimal amount whose wire form the profile override describes. */
        public BigDecimal amount;
    }

    /**
     * Body DTO whose {@link BigDecimal} property additionally carries Swagger refinement metadata —
     * a narrower pattern and a smaller maximum length than the profile fragment declares.
     */
    static final class RefinedAmountDto {

        /** The refined decimal amount. */
        @Schema(maxLength = 20, pattern = "^-?[0-9]+\\.[0-9]{2}$")
        public BigDecimal amount;
    }

    // --- Resolved generic types ---

    /** {@code List<ItemDto>} as a resolved {@link ParameterizedType}. */
    static final Type LIST_OF_ITEM_DTO = parameterized(List.class, ItemDto.class);

    /** {@code List<BigDecimal>} as a resolved {@link ParameterizedType}. */
    static final Type LIST_OF_BIG_DECIMAL = parameterized(List.class, BigDecimal.class);

    /**
     * Builds a resolved {@link ParameterizedType} with no owner type.
     *
     * @param rawType   the raw class
     * @param arguments the resolved type arguments
     * @return the parameterized type
     */
    static Type parameterized(Class<?> rawType, Type... arguments) {
        Type[] copy = arguments.clone();
        return new ParameterizedType() {

            @Override
            public Type[] getActualTypeArguments() {
                return copy.clone();
            }

            @Override
            public Type getRawType() {
                return rawType;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }

            @Override
            public String toString() {
                return rawType.getTypeName() + java.util.Arrays.toString(copy);
            }
        };
    }
}
