// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * A step that evaluates a list of routing predicates and branches to the first matching step,
 * falling back to a default step if no predicate matches.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id} — unique step identifier within the workflow.
 *   <li>{@code routes} — ordered list of conditional routes. Jackson maps the {@code routes}
 *       array to this field.
 *   <li>{@code defaultRoute} — id of the step to transition to when no route matches. Mapped
 *       from the JSON/YAML key {@code "default"} via {@link JsonProperty} because {@code default}
 *       is a Java reserved word.
 * </ul>
 */
public record DecisionStep(
        String id,
        List<RouteEntry> routes,
        @JsonProperty("default") String defaultRoute) implements StepNode {

    /**
     * A single conditional route within a {@link DecisionStep}.
     *
     * <p>Exactly one of {@code when} or {@code condition} should be set in a valid document;
     * the parser does not enforce this — it is the validator's responsibility. The {@code to}
     * field is always required.
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code when} — an expression string evaluated by the workflow's expression profile
     *       (e.g., a CEL predicate). {@code null} when {@code condition} is used instead.
     *   <li>{@code condition} — a registered named condition id evaluated by the
     *       {@code NamedConditionRegistry}. {@code null} when {@code when} is used instead.
     *   <li>{@code to} — id of the step to transition to when this route is selected.
     * </ul>
     */
    public record RouteEntry(
            @Nullable String when, @Nullable String condition, String to) {}
}
