// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Compiler bridge that translates a parsed {@link dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument}
 * into a {@link dev.vertique.workflow.dsl.WorkflowDefinition} by driving the existing
 * {@link dev.vertique.workflow.dsl.WorkflowBuilder} DSL.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link dev.vertique.workflow.definition.compiler.WorkflowDefinitionCompiler} — top-level
 *       emitter; dispatches each {@link dev.vertique.workflow.definition.schema.StepNode} variant
 *       to the corresponding {@link dev.vertique.workflow.dsl.WorkflowBuilder} method.</li>
 *   <li>{@link dev.vertique.workflow.definition.compiler.DecisionRouteCompiler} — compiles
 *       {@link dev.vertique.workflow.definition.schema.DecisionStep} route tables into a
 *       synthesized {@code Function<S, String>} resolver with a stable fingerprint.</li>
 *   <li>{@link dev.vertique.workflow.definition.compiler.DecisionRouteResolver} — value object
 *       returned by the decision route compiler: the resolver function plus the fingerprinted
 *       {@link dev.vertique.workflow.registry.CallbackId}.</li>
 *   <li>{@link dev.vertique.workflow.definition.compiler.DocumentBackedWorkflowDefinition} —
 *       adapter that implements {@link dev.vertique.workflow.dsl.WorkflowDefinition} from a
 *       deserialized document.</li>
 * </ul>
 */
package dev.vertique.workflow.definition.compiler;
