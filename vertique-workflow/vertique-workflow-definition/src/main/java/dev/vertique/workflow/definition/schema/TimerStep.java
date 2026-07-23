// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.schema;

/**
 * A step that defers execution until a specified point in time, then advances to the next step.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id} — unique step identifier within the workflow.
 *   <li>{@code fireAt} — when the timer fires, expressed as an ISO-8601 Duration
 *       (relative to step entry, e.g., {@code "PT1H"}), an ISO-8601 Instant (absolute
 *       wall-clock time), or {@code "ref:<resolverId>"} where {@code resolverId} is a registered
 *       timer resolver id. Value-level parsing is the validator's responsibility.
 *   <li>{@code next} — id of the step to transition to when the timer fires.
 * </ul>
 */
public record TimerStep(String id, String fireAt, String next) implements StepNode {}
