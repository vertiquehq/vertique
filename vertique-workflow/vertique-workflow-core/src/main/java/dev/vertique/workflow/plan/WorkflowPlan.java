// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import dev.vertique.workflow.registry.CallbackId;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Immutable, serialization-friendly representation of a workflow definition's step graph.
 *
 * <p>The plan is a pure data structure — it contains only step ids, {@code CallbackId} references,
 * and class-name strings. All executable Java functions ({@code Function}, {@code BiFunction}) are
 * stored separately in the {@code WorkflowCallbackRegistry}. This design makes {@code planHash}
 * deterministic across JVM restarts and makes the plan trivially serializable (cycle-6 definition
 * files reuse this same shape).
 *
 * <p>The {@code planHash} is a SHA-256 hex digest computed over the plan's content (node ids,
 * callback ids, and ordering). Plans with identical node structure produce identical hashes. The
 * engine uses the hash to detect plan-version drift for in-flight instances.
 *
 * @param definitionId the unique identifier of the workflow definition
 * @param definitionVersion monotonically increasing version of this plan
 * @param planHash SHA-256 hex digest of the plan content; deterministic across JVM restarts
 * @param stateTypeName fully-qualified class name ({@link Class#getName()}) of the workflow state
 *     type; held as a string so the plan is serialization-friendly
 * @param initialStepId step id of the first node the engine executes after {@code start()}
 * @param nodes ordered list of all nodes in the plan; must include the node identified by
 *     {@code initialStepId}
 * @param subjectResolverCallbackId optional {@link CallbackId} of the subject-resolver function
 *     registered via {@code WorkflowBuilder.subject(...)}; {@code null} when no subject resolver
 *     was configured (cycle-1 through cycle-4 plans always have {@code null} here, preserving
 *     plan-hash continuity)
 */
public record WorkflowPlan(
        String definitionId,
        long definitionVersion,
        String planHash,
        String stateTypeName,
        String initialStepId,
        List<WorkflowNode> nodes,
        @Nullable CallbackId subjectResolverCallbackId) {}
