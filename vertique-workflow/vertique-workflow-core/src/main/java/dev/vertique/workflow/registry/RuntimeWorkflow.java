// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import dev.vertique.workflow.plan.CompensationNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import java.util.Map;
import java.util.function.Function;

/**
 * Bundles the serialization-friendly {@link WorkflowPlan} with the runtime metadata the engine
 * needs to execute it.
 *
 * <p>The plan itself only carries string class names and {@link CallbackId} references. The
 * engine needs actual {@link Class} objects for JSON deserialization and a {@link Function} for
 * computing the initial state from the start payload. These are held here so the plan stays
 * serializable (e.g., for cycle-6 definition files).
 *
 * <p>The {@code initialState} function uses raw types ({@code Function<Object, Object>}) to bridge
 * Java generics at the registry boundary. The engine casts the start payload to
 * {@code startPayloadType} before calling the function and casts the result back to
 * {@code stateType} before JSON encoding.
 *
 * <p>The two derived maps ({@code nodeById} and {@code compensationByForwardStepId}) are computed
 * once by the {@link #of} factory and cached here, so the engine can look up nodes in O(1) rather
 * than scanning {@code plan.nodes()} on every step.
 *
 * <p>Use {@link #of} to construct instances; the canonical record constructor is package-private for
 * testing.
 *
 * @param plan the serialization-friendly plan produced by the {@link WorkflowBuilder}
 * @param stateType runtime {@code Class} object for the workflow state; used for JSON
 *     deserialization
 * @param startPayloadType runtime {@code Class} object for the start payload; the engine coerces
 *     the incoming payload to this type before calling {@code initialState}
 * @param initialState raw-typed bridge function that maps the start payload to the initial
 *     workflow state; caller must ensure types are consistent with {@code startPayloadType} and
 *     {@code stateType}
 * @param signalPayloadTypes map from signal name to the expected payload {@code Class}; used by
 *     the engine to coerce externally-ingested signal payloads (e.g., raw {@code Map}) to the
 *     declared type via {@code Json.decodeValue}
 * @param callbacks the callback registry populated by the builder; holds all executable functions
 *     referenced by the plan's {@code CallbackId}s
 * @param nodeById pre-computed map from step id to {@link WorkflowNode} for O(1) lookup; computed
 *     by the {@link #of} factory from {@code plan.nodes()}
 * @param compensationByForwardStepId pre-computed map from a forward step id to its
 *     {@link CompensationNode}; contains only entries for nodes that have a compensation node;
 *     computed by the {@link #of} factory from {@code plan.nodes()}
 */
public record RuntimeWorkflow(
        WorkflowPlan plan,
        Class<?> stateType,
        Class<?> startPayloadType,
        Function<Object, Object> initialState,
        Map<String, Class<?>> signalPayloadTypes,
        WorkflowCallbackRegistry callbacks,
        Map<String, WorkflowNode> nodeById,
        Map<String, CompensationNode> compensationByForwardStepId) {

    // --- Factory ---

    /**
     * Constructs a {@code RuntimeWorkflow}, eagerly computing the two node-lookup maps from
     * {@code plan.nodes()}.
     *
     * @param plan the serialization-friendly workflow plan
     * @param stateType runtime {@code Class} for the workflow state
     * @param startPayloadType runtime {@code Class} for the start payload
     * @param initialState function that maps start payload to initial state
     * @param signalPayloadTypes map from signal name to payload class
     * @param callbacks callback registry populated by the builder
     * @return a fully-initialized {@code RuntimeWorkflow} with pre-computed lookup maps
     */
    public static RuntimeWorkflow of(
            WorkflowPlan plan,
            Class<?> stateType,
            Class<?> startPayloadType,
            Function<Object, Object> initialState,
            Map<String, Class<?>> signalPayloadTypes,
            WorkflowCallbackRegistry callbacks) {
        Map<String, WorkflowNode> byId = new java.util.HashMap<>();
        Map<String, CompensationNode> compMap = new java.util.HashMap<>();
        for (WorkflowNode n : plan.nodes()) {
            byId.put(n.stepId(), n);
            if (n instanceof CompensationNode cn) {
                compMap.put(cn.forwardStepId(), cn);
            }
        }
        return new RuntimeWorkflow(
                plan,
                stateType,
                startPayloadType,
                initialState,
                signalPayloadTypes,
                callbacks,
                Map.copyOf(byId),
                Map.copyOf(compMap));
    }
}
