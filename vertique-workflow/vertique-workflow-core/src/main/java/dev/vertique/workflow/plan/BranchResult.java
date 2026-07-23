// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import dev.vertique.workflow.state.BranchStatus;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;

/**
 * Per-branch outcome made available to a join's reducer callback.
 *
 * <p>The fan-in machinery (see {@link JoinNode#branchResultReducerCallbackId()}) builds a
 * {@code Map<String, BranchResult>} keyed by {@link BranchStart#branchId()} and passes it,
 * together with the current workflow state, to the reducer registered on the {@link JoinNode}.
 * The reducer returns the new workflow state to be persisted before the next step.
 *
 * @param branchId the branch identifier this result belongs to
 * @param status terminal status reached by the branch
 * @param result optional opaque payload contributed by the branch (typically the JSON form of a
 *     branch-local state field). {@code null} when the branch did not record one or when the
 *     branch terminated abnormally.
 * @param errorType optional {@code WorkflowOrchestrationException}-derived error type when the
 *     branch reached a non-success terminal status; {@code null} on success
 * @param errorMessage optional human-readable error message paired with {@code errorType}
 */
public record BranchResult(
        String branchId,
        BranchStatus status,
        @Nullable JsonObject result,
        @Nullable String errorType,
        @Nullable String errorMessage) {}
