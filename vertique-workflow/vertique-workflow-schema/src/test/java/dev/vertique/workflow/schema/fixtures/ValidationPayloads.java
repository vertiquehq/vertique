// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema.fixtures;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * Payload types the keyed validation tests declare as decision and start types. They are public so that the
 * registration path can load them by their binary class name.
 */
public final class ValidationPayloads {

    private ValidationPayloads() {}

    /** A comment that must be present. */
    public record RequiredComment(@NotNull String comment) {}

    /** A comment of at most three characters. */
    public record ShortComment(@Size(max = 3) String comment) {}

    /** The priority of a decision. */
    public enum Priority {
        LOW,
        HIGH
    }

    /** An enum property and a numeric property, to reject with values of the wrong kind. */
    public record PriorityDecision(Priority priority, int amount) {}

    /** A record that declares itself closed to undeclared properties. */
    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Closed(String name) {}

    /** A code limited to lower case letters. */
    public record Coded(@Pattern(regexp = "^[a-z]+$") String code) {}

    /** A list whose every item is limited to lower case letters. */
    public record CodedList(List<@Pattern(regexp = "^[a-z]+$") String> codes) {}

    /** A list whose every item is limited to three characters, to produce one violation per long item. */
    public record ShortItems(List<@Size(max = 3) String> items) {}

    /** Two numeric constraints on one property. */
    public record Bounded(@Positive @Max(100) Integer amount) {}

    /** An inner record, reached through a nested property. */
    public record Inner(String label) {}

    /** A nested record and a list of strings. */
    public record Nested(Inner inner, List<String> tags) {}

    /** A map whose keys are chosen by the client. */
    public record Labelled(Map<String, Integer> labels) {}

    /** The payload of the first of two decisions that share an id and version across two definitions. */
    public record FirstChoice(@NotNull String alpha) {}

    /** The payload of the second of two decisions that share an id and version across two definitions. */
    public record SecondChoice(@NotNull String beta) {}
}
