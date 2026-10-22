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
 *   <li>{@code stateType} — logical type name of the workflow state type (the {@code @WorkflowType}
 *       value, e.g., {@code "order-state"}). A packaged, trusted document may also give the binary
 *       class name.
 *   <li>{@code contract} — logical contract name of the {@code @WorkflowContract}-annotated
 *       interface (its {@code value()}, e.g., {@code "orders"}); a packaged document may also give
 *       the binary class name. The document's id and version are not compared with the annotation,
 *       and any number of documents and versions may name one contract. Contract names and type
 *       names are separate namespaces.
 *   <li>{@code startPayloadType} — logical type name of the start command payload type.
 *   <li>{@code initialStateMapper} — registered {@code StartStateMapper} id that produces the
 *       initial state from the start payload.
 *   <li>{@code subjectResolver} — optional registered {@code SubjectResolver} id; {@code null}
 *       when not needed.
 *   <li>{@code initialStep} — id of the first step to execute after initialization.
 *   <li>{@code steps} — ordered list of all step nodes in the workflow graph.
 *   <li>{@code activateOnDeploy} — optional; {@code false} publishes a packaged document without making it the
 *       default scope's current version. Absent means {@code true}. A document loaded through the definition
 *       service ignores it: such a document is published and activated by an operator.
 * </ul>
 *
 * @param definitionId unique identifier for the workflow definition
 * @param definitionVersion monotonically increasing version number
 * @param stateType logical type name of the workflow state type
 * @param contract logical contract name
 * @param startPayloadType logical type name of the start command payload type
 * @param initialStateMapper registered start-state mapper id
 * @param subjectResolver optional registered subject resolver id
 * @param initialStep id of the first step
 * @param steps ordered list of all step nodes
 * @param activateOnDeploy whether a packaged version activates when first published; {@code null} is read as
 *     {@code true}
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
        List<StepNode> steps,
        @Nullable Boolean activateOnDeploy) {

    /** Normalizes an absent {@code activateOnDeploy} to {@code true}. */
    public WorkflowDefinitionDocument {
        if (activateOnDeploy == null) activateOnDeploy = Boolean.TRUE;
    }

    /**
     * Creates a document without the {@code activateOnDeploy} header, which is read as {@code true}.
     *
     * @param definitionId unique identifier for the workflow definition
     * @param definitionVersion monotonically increasing version number
     * @param stateType logical type name of the workflow state type
     * @param contract logical contract name
     * @param startPayloadType logical type name of the start command payload type
     * @param initialStateMapper registered start-state mapper id
     * @param subjectResolver optional registered subject resolver id
     * @param initialStep id of the first step
     * @param steps ordered list of all step nodes
     */
    public WorkflowDefinitionDocument(
            String definitionId,
            long definitionVersion,
            String stateType,
            String contract,
            String startPayloadType,
            String initialStateMapper,
            @Nullable String subjectResolver,
            String initialStep,
            List<StepNode> steps) {
        this(
                definitionId,
                definitionVersion,
                stateType,
                contract,
                startPayloadType,
                initialStateMapper,
                subjectResolver,
                initialStep,
                steps,
                Boolean.TRUE);
    }
}
