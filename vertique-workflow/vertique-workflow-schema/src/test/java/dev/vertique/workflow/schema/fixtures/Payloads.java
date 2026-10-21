// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema.fixtures;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.vertique.workflow.types.WorkflowType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * Payload types the registration tests declare as decision and start types, one per shape the object-or-void rule
 * distinguishes. They are public so tests in the production package can name them; the registration path loads them
 * by their binary class name.
 */
public final class Payloads {

    private Payloads() {}

    /** A plain record: an object-shaped payload. */
    public record PlainRecord(String name, int count) {}

    /** A single-property record whose wire name differs under a snake case profile. */
    public record Comment(String displayName) {}

    /** A start payload that carries a logical name. */
    @WorkflowType("order.place")
    public record PlaceOrder(String customer) {}

    /** A decision payload that carries a logical name. */
    @WorkflowType("order.approval")
    public record OrderApproval(String comment) {}

    /** A required property with an alias: the schema states the requirement as a presence rule beside the type. */
    public record RequiredAliasRecord(
            @JsonAlias("qty") @NotNull Integer quantity, String note) {}

    /** The same property, optional: the alias is listed and no presence rule exists. */
    public record OptionalAliasRecord(@JsonAlias("qty") Integer quantity, String note) {}

    /** A record that serializes as one string: not object-shaped. */
    public record JsonValueRecord(@JsonValue String value) {}

    /** A closed polymorphic base whose schema has no root type: not object-shaped. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Car.class, name = "car"),
        @JsonSubTypes.Type(value = Truck.class, name = "truck")
    })
    public sealed interface SealedShape permits Car, Truck {}

    /** The {@code car} subtype. */
    public record Car(String plate) implements SealedShape {}

    /** The {@code truck} subtype. */
    public record Truck(String plate, int axles) implements SealedShape {}

    /** A record whose pattern does not compile. */
    public record BadPattern(@Pattern(regexp = "(") String code) {}

    /** A record whose pattern compiles and rejects upper case input. */
    public record GoodPattern(@Pattern(regexp = "^[a-z]+$") String code) {}

    /** A record with a {@link UUID} property, which a test profile replaces by a schema the engine cannot compile. */
    public record UuidHolder(UUID id) {}
}
