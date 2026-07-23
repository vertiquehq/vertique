// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.schema;

import jakarta.annotation.Nullable;

/**
 * A step that invokes a named service callback and advances to the next step on success.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id} — unique step identifier within the workflow.
 *   <li>{@code target} — registered service-call target id (e.g., {@code "inventory.reserve"}).
 *   <li>{@code payloadMapper} — registered payload mapper id that maps the current state to the
 *       outgoing service payload.
 *   <li>{@code compensation} — optional id of the compensation step to run if this step needs
 *       to be rolled back; {@code null} if no compensation is defined.
 *   <li>{@code next} — id of the step to transition to on success.
 * </ul>
 */
public record ServiceStep(
        String id,
        String target,
        String payloadMapper,
        @Nullable String compensation,
        String next) implements StepNode {}
