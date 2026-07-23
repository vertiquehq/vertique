// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.schema;

/**
 * A terminal step that marks the workflow as failed with a typed error.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id} — unique step identifier within the workflow.
 *   <li>{@code errorType} — logical error type identifier (e.g., {@code "order-cancelled"}).
 *   <li>{@code messageFactory} — registered fail message factory id that produces the error
 *       message from the current workflow state.
 * </ul>
 */
public record FailStep(String id, String errorType, String messageFactory) implements StepNode {}
