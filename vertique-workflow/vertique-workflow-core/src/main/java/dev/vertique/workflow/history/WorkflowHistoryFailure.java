// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.history;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.HistoryActor;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An action that failed: the action failed, its transaction did not commit, or its commit was not confirmed. The
 * history entries the action wrote were not committed (the outcome of a lost commit may be unknown), and the
 * sequences they used may be reused.
 *
 * @param attemptId  minted once per attempt; identifies the attempt even across redelivery
 * @param instanceId the instance, or {@code null} when the action never resolved one
 * @param action     what was attempted
 * @param actor      who attempted it
 * @param onBehalfOf the subject the actor acted for, or {@code null}
 * @param attribution how far the actor can be relied on
 * @param errorType  the simple class name of the failure, never its message; {@code null} when unknown
 * @param occurredAt when the failure was observed
 */
public record WorkflowHistoryFailure(
        UUID attemptId,
        @Nullable WorkflowInstanceId instanceId,
        Action action,
        HistoryActor actor,
        @Nullable String onBehalfOf,
        HistoryAttribution attribution,
        @Nullable String errorType,
        Instant occurredAt) {

    /** The caller-driven actions that can fail. New constants are additive; consumers must tolerate unknown ones. */
    public enum Action {
        /** Start an instance. */
        START,
        /** Deliver a signal. */
        SIGNAL,
        /** Cancel an instance. */
        CANCEL,
        /** Retry a failed instance. */
        RETRY,
        /** Migrate an instance to another definition version. */
        MIGRATE,
        /** Complete a human task. */
        TASK_COMPLETE,
        /** Reassign a human task. */
        TASK_REASSIGN,
        /** Record the terminal failure of a command. */
        FAILURE_DISPOSITION
    }

    /** Validates the required components. */
    public WorkflowHistoryFailure {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(attribution, "attribution");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
