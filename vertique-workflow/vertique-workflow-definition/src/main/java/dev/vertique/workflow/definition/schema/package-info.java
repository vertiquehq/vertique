// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Jackson record tree representing a parsed workflow definition document.
 *
 * <p>Records in this package are pure data containers deserialized from YAML or JSON sources. They
 * contain NO validation logic — that is the responsibility of the validator in the
 * {@code dev.vertique.workflow.definition.validator} package (Slice D). The records accept
 * anything that Jackson can map; semantic correctness is checked later.
 *
 * <p>Key types:
 * <ul>
 *   <li>{@link dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument} — top-level document.
 *   <li>{@link dev.vertique.workflow.definition.schema.StepNode} — sealed polymorphic step discriminated by the
 *       {@code type} JSON/YAML property.
 *   <li>Step variants: {@link dev.vertique.workflow.definition.schema.ServiceStep},
 *       {@link dev.vertique.workflow.definition.schema.WaitSignalStep},
 *       {@link dev.vertique.workflow.definition.schema.TimerStep},
 *       {@link dev.vertique.workflow.definition.schema.HumanTaskStep},
 *       {@link dev.vertique.workflow.definition.schema.DecisionStep},
 *       {@link dev.vertique.workflow.definition.schema.CompleteStep},
 *       {@link dev.vertique.workflow.definition.schema.FailStep},
 *       {@link dev.vertique.workflow.definition.schema.CompensationStep},
 *       {@link dev.vertique.workflow.definition.schema.ForkStep},
 *       {@link dev.vertique.workflow.definition.schema.JoinStep}.
 * </ul>
 */
package dev.vertique.workflow.definition.schema;
