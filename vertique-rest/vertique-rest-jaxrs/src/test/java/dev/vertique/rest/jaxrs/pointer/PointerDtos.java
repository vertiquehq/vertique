// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.pointer;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Body DTO fixtures for the body violation pointer integration tests. Each shape isolates one
 * relation between a Java property and the wire name the request carries it under.
 */
public final class PointerDtos {

    private PointerDtos() {}

    /** A body whose only member is renamed on the wire. */
    public record Address(
            @JsonProperty("zip_code") @NotBlank String zipCode) {}

    /** A list element with one required member. */
    public record Item(@NotBlank String name) {}

    /** A body whose member is renamed by the route's naming strategy, never by an annotation. */
    public record Shipping(@NotBlank String zipCode) {}

    /** A body holding a list of {@link Item}s. */
    public record Cart(@Valid List<Item> items) {}

    /** A setter-bound nested object with a required member. */
    public static class Owner {
        @NotBlank
        private String name;

        /**
         * Returns the name.
         *
         * @return the name
         */
        public String getName() {
            return name;
        }

        /**
         * Sets the name.
         *
         * @param name the name
         */
        public void setName(String name) {
            this.name = name;
        }
    }

    /**
     * A body with a class-level constraint, a map, a list, a nested object, a required member and two
     * members whose wire names need RFC 6901 escaping.
     *
     * @param slash the member named {@code a/b} on the wire
     * @param tilde the member named {@code t~x} on the wire
     * @param name  a required member
     * @param items a list of items
     * @param byKey a map of items
     * @param owner a nested object
     */
    @ConflictFree
    public record Basket(
            @JsonProperty("a/b") @NotBlank String slash,
            @JsonProperty("t~x") @NotBlank String tilde,
            @NotNull String name,
            @Valid List<Item> items,
            @Valid Map<String, Item> byKey,
            @Valid Owner owner)
            implements ConflictFree.Conflicting {

        @Override
        public boolean conflicts() {
            return "conflict".equals(name);
        }
    }

    /**
     * A list element carrying a class-level constraint.
     *
     * @param label the label; {@code "conflict"} makes the element conflict
     */
    @ConflictFree
    public record Checked(String label) implements ConflictFree.Conflicting {

        @Override
        public boolean conflicts() {
            return "conflict".equals(label);
        }
    }

    /** A body holding a list of {@link Checked} elements. */
    public record Ledger(@Valid List<Checked> entries) {}

    /** A body holding a set of strings. */
    public record Tagged(@NotNull Set<@NotBlank String> tags) {}

    /** A body holding lists of lists of strings. */
    public record Grid(List<List<@NotBlank String>> lists) {}

    /** A body whose member also answers to the alias {@code zip}. */
    public record Aliased(@JsonAlias("zip") @NotBlank String zipCode) {}

    /** A setter-bound body whose member also answers to the alias {@code zip}. */
    public static class AliasedBean {
        @NotBlank
        private String zipCode;

        /**
         * Returns the zip code.
         *
         * @return the zip code
         */
        public String getZipCode() {
            return zipCode;
        }

        /**
         * Sets the zip code; a later key for the same property replaces an earlier one.
         *
         * @param zipCode the zip code
         */
        @JsonAlias("zip")
        public void setZipCode(String zipCode) {
            this.zipCode = zipCode;
        }
    }

    /** A body whose list drops {@code null} elements while binding. */
    public static class Skipping {
        @Valid
        private List<Item> items;

        /**
         * Returns the items.
         *
         * @return the items
         */
        public List<Item> getItems() {
            return items;
        }

        /**
         * Sets the items; {@code null} elements are skipped by the mapper.
         *
         * @param items the items
         */
        @JsonSetter(contentNulls = Nulls.SKIP)
        public void setItems(List<Item> items) {
            this.items = items;
        }
    }

    /** A public-field POJO whose field is renamed on the wire. */
    public static class PublicAddress {
        @JsonProperty("zip_code")
        @NotBlank
        public String zipCode;
    }

    /** A POJO whose constrained private field is written only by a renamed setter. */
    public static class RenamedSetter {
        @NotBlank
        private String zipCode;

        /**
         * Sets the zip code; the setter, not the field, carries the wire name.
         *
         * @param zip the zip code
         */
        @JsonProperty("zip_code")
        public void setZip(String zip) {
            this.zipCode = zip;
        }
    }

    /** A body whose only member is named by the empty string on the wire, through a naming strategy. */
    public record Blank(@NotBlank String blank) {}

    /** A polymorphic body element typed by the {@code type} property. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Dog.class, name = "dog"),
        @JsonSubTypes.Type(value = Cat.class, name = "cat")
    })
    public interface Animal {}

    /** A dog, whose required member is renamed on the wire. */
    public record Dog(@JsonProperty("woof") @NotBlank String bark) implements Animal {}

    /** A cat. */
    public record Cat(@NotBlank String purr) implements Animal {}
}
