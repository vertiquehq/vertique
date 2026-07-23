// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.schema;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Top-level record representing a deserialized workflow definition document.
 *
 * <p>This record is the root of the Jackson record tree produced by parsing a YAML or JSON
 * definition file. It contains only string identifiers and literal scalars — no Java functions
 * or runtime objects. Semantic validation (id resolution, graph reachability, type compatibility)
 * is performed by the validator in a later pipeline stage.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code definitionId} — unique identifier for this workflow definition (e.g.,
 *       {@code "order-fulfillment"}).
 *   <li>{@code definitionVersion} — monotonically increasing version number (e.g., {@code 1}).
 *   <li>{@code stateType} — fully-qualified class name of the workflow state type
 *       (e.g., {@code "dev.example.order.state.OrderState"}).
 *   <li>{@code contract} — fully-qualified class name of the {@code @WorkflowContract}-annotated
 *       interface. Each coexisting version of a definition MUST reference a distinct contract class.
 *   <li>{@code startPayloadType} — fully-qualified class name of the start command payload type.
 *   <li>{@code initialStateMapper} — registered {@code StartStateMapper} id that produces the
 *       initial state from the start payload.
 *   <li>{@code subjectResolver} — optional registered {@code SubjectResolver} id; {@code null}
 *       when not needed.
 *   <li>{@code initialStep} — id of the first step to execute after initialization.
 *   <li>{@code steps} — ordered list of all step nodes in the workflow graph.
 * </ul>
 */
public record WorkflowDefinitionDocument(
        String definitionId,
        long definitionVersion,
        String stateType,
        String contract,
        String startPayloadType,
        String initialStateMapper,
        @Nullable String subjectResolver,
        String initialStep,
        List<StepNode> steps) {}
