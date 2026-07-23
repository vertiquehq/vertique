// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.schema;

/**
 * A terminal step that marks the workflow as successfully completed.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id} — unique step identifier within the workflow.
 * </ul>
 */
public record CompleteStep(String id) implements StepNode {}
