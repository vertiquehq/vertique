// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.corpus;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The request body types of the embedding-equivalence corpus, one shape per kind of schema the
 * input-direction generator builds: creator, setter, and builder binding; constraints read without a
 * Bean Validation {@code Validator}; aliases; a self-referencing graph; mutually referencing types;
 * a nested type used twice; map values and a self-referencing map; a member-level closure; {@code
 * Optional}-valued extras; and nullable decimals whose wire form depends on the profile.
 *
 * <p>No type declares a hidden or ignored member, and no type with an any-setter declares another
 * member, so the generator reserves no name in any of their schemas. The two same-named types in
 * different packages are {@code fixture.corpus.a.Item} and {@code fixture.corpus.b.Item}.
 */
public final class CorpusBodies {

    private CorpusBodies() {}

    /** Bound through a constructor creator whose parameters carry the constraints. */
    public static final class CreatorOrder {

        private final int quantity;
        private final String label;

        /**
         * The creator.
         *
         * @param quantity at most five
         * @param label    at most four characters
         */
        @JsonCreator
        public CreatorOrder(
                @JsonProperty("quantity") @Max(5) int quantity, @JsonProperty("label") @Size(max = 4) String label) {
            this.quantity = quantity;
            this.label = label;
        }

        /**
         * Returns the quantity.
         *
         * @return the quantity
         */
        public int getQuantity() {
            return quantity;
        }

        /**
         * Returns the label.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }
    }

    /** Bound through setters whose backing fields carry the constraints. */
    public static final class SetterOrder {

        @Positive
        private int amount;

        @Size(max = 3)
        private String code;

        /**
         * Sets the amount.
         *
         * @param amount a positive amount
         */
        public void setAmount(int amount) {
            this.amount = amount;
        }

        /**
         * Sets the code.
         *
         * @param code at most three characters
         */
        public void setCode(String code) {
            this.code = code;
        }
    }

    /** Bound through a builder whose method carries the constraint. */
    @JsonDeserialize(builder = BuilderOrder.Builder.class)
    public static final class BuilderOrder {

        private final int amount;
        private final String note;

        private BuilderOrder(int amount, String note) {
            this.amount = amount;
            this.note = note;
        }

        /**
         * Returns the amount.
         *
         * @return the amount
         */
        public int getAmount() {
            return amount;
        }

        /**
         * Returns the note.
         *
         * @return the note
         */
        public String getNote() {
            return note;
        }

        /** The builder Jackson binds through. */
        @JsonPOJOBuilder(withPrefix = "with")
        public static final class Builder {

            private int amount;
            private String note;

            /**
             * Sets the amount.
             *
             * @param amount at most five
             * @return this builder
             */
            @Max(5)
            public Builder withAmount(int amount) {
                this.amount = amount;
                return this;
            }

            /**
             * Sets the note.
             *
             * @param note any text
             * @return this builder
             */
            public Builder withNote(String note) {
                this.note = note;
                return this;
            }

            /**
             * Builds the order.
             *
             * @return the order
             */
            public BuilderOrder build() {
                return new BuilderOrder(amount, note);
            }
        }
    }

    /** Field constraints the generator translates on its own, without a Bean Validation validator. */
    public static final class ConstrainedOrder {

        /** Required, two to five characters. */
        @NotNull
        @Size(min = 2, max = 5)
        public String code;

        /** One to nine. */
        @Min(1)
        @Max(9)
        public Integer level;

        /** Lowercase letters only. */
        @Pattern(regexp = "^[a-z]+$")
        public String slug;
    }

    /** Properties that also bind under an alias spelling. */
    public static final class AliasedOrder {

        /** Required, at most ten; also bound as {@code qty}. */
        @JsonAlias("qty")
        @Max(10)
        @NotNull
        public Integer quantity;

        /** At most three characters; also bound as {@code nm}. */
        @JsonAlias("nm")
        @Size(max = 3)
        public String name;
    }

    /** A node that references its own type: the schema refers back to its own root. */
    public static final class TreeNode {

        /** At most three characters. */
        @Size(max = 3)
        public String value;

        /** The parent node, of this same type. */
        public TreeNode parent;
    }

    /** Holds both halves of a reference cycle, so each half is a shared definition. */
    public static final class LinkedParts {

        /** The first half. */
        public PartA first;

        /** The second half. */
        public PartB second;
    }

    /** One half of a reference cycle; refers to {@link PartB}. */
    public static final class PartA {

        /** At most three characters. */
        @Size(max = 3)
        public String label;

        /** The other half. */
        public PartB next;
    }

    /** The other half of a reference cycle; refers back to {@link PartA}. */
    public static final class PartB {

        /** At most nine. */
        @Max(9)
        public Integer count;

        /** Back to the first half. */
        public PartA back;
    }

    /** Two members of one nested type, which the schema shares as one definition. */
    public static final class Route {

        /** Where the route starts. */
        public Address from;

        /** Where the route ends. */
        public Address to;
    }

    /** The nested type {@link Route} uses twice. */
    public static final class Address {

        /** Required, at most four characters. */
        @NotNull
        @Size(max = 4)
        public String city;

        /** Exactly five digits. */
        @Pattern(regexp = "^[0-9]{5}$")
        public String zip;
    }

    /** Map values: a type-use constraint on the value, and a value type also used as a member. */
    public static final class MapValues {

        /** Values of at most three characters. */
        public Map<String, @Size(max = 3) String> codes;

        /** Values described by {@link Limit}. */
        public Map<String, Limit> limits;

        /** A member of the same type as the {@link #limits} values. */
        public Limit fallback;
    }

    /** The value type of {@link MapValues#limits}. */
    public static final class Limit {

        /** At most ten. */
        @Max(10)
        public Integer max;
    }

    /** A map whose value type is itself: a bounded reference cycle. */
    public static class RecursiveMap extends HashMap<String, RecursiveMap> {

        private static final long serialVersionUID = 1L;
    }

    /** Holds a {@link RecursiveMap} at a named member. */
    public static final class RecursiveMapHolder {

        /** The self-referencing map. */
        public RecursiveMap tree;
    }

    /** One member closed at the member level, beside an open member of the same type. */
    public static final class ClosureHolder {

        /** Closed: accepts no extra key. */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
        public ExtrasOnly closed;

        /** Open: accepts string-valued extra keys. */
        public ExtrasOnly open;
    }

    /** A type whose only member is its any-setter. */
    public static final class ExtrasOnly {

        /** The extras, string-valued. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** A type whose only member is an any-setter with {@code Optional} values. */
    public static final class OptionalExtras {

        /** The extras, each an optional {@link Plain}. */
        @JsonAnySetter
        public Map<String, Optional<Plain>> extras = new LinkedHashMap<>();
    }

    /** The value type of {@link OptionalExtras}. */
    public static final class Plain {

        /** At most three characters. */
        @Size(max = 3)
        public String name;
    }

    /**
     * Nullable members, one a decimal: a string on the wire under {@code vertique-strict}, a number under
     * the default profile.
     */
    public static final class Amounts {

        /** A nullable decimal. */
        @Schema(nullable = true)
        public BigDecimal amount;

        /** A nullable string. */
        @Schema(nullable = true)
        public String note;
    }
}
