// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import dev.vertique.core.context.DurableMetadata;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;

/**
 * Records a signal that was already durable before it arrived, such as one the outbox relay delivers to
 * {@code workflow.signals.post}, in the caller's transaction.
 *
 * <p>Each method behaves exactly as the {@code signal} method of {@link TransactionalWorkflowOperations} that has the
 * same parameters, in every respect but one: it is never refused because the cluster holds too many due commands (see
 * {@link dev.vertique.workflow.exception.WorkflowIntakeRefusedException}). The signal is already stored, and
 * refusing it would only spend delivery attempts until it was dead-lettered while the queue it would join is the one
 * that must drain. The same checks, guards and sanitization apply as to the public form, and the caller keeps its own
 * decision about which signal names it accepts.
 *
 * <p>An application signals from outside through {@link TransactionalWorkflowOperations} or {@link
 * WorkflowOperations}, which the limit applies to. Use this interface only for a delivery whose signal is already
 * durable and whose refusal would be lost or retried by a transport that cannot wait.
 *
 * @param <TX> the transaction context type (for example {@code SqlClient} in the PostgreSQL implementation)
 */
public interface RelayedSignals<TX> {

    /**
     * Records a signal, as {@link TransactionalWorkflowOperations#signal(WorkflowInstanceId, String, Object, String,
     * String, String, Object)} does, without the check of the intake limit.
     *
     * @param id workflow instance id
     * @param signalName signal name
     * @param payload signal payload
     * @param signalDedupKey caller-supplied dedup key
     * @param forkStepId optional fork step id; required when {@code branchId} is set
     * @param branchId optional branch id; required when {@code forkStepId} is set
     * @param tx the active transaction context
     * @return a {@link Future} that completes when the signal is recorded
     */
    Future<Void> signal(
            WorkflowInstanceId id,
            String signalName,
            Object payload,
            String signalDedupKey,
            @Nullable String forkStepId,
            @Nullable String branchId,
            TX tx);

    /**
     * Records a signal with an explicit durable-context carrier, as {@link
     * TransactionalWorkflowOperations#signal(WorkflowInstanceId, String, Object, String, String, String,
     * DurableMetadata, Object)} does, without the check of the intake limit. The carrier is sanitized as the public
     * form sanitizes it.
     *
     * @param id workflow instance id
     * @param signalName signal name
     * @param payload signal payload
     * @param signalDedupKey caller-supplied dedup key
     * @param forkStepId optional fork step id; required when {@code branchId} is set
     * @param branchId optional branch id; required when {@code forkStepId} is set
     * @param signalMetadata optional explicit durable-context carrier; {@code null} for none
     * @param tx the active transaction context
     * @return a {@link Future} that completes when the signal is recorded
     */
    Future<Void> signal(
            WorkflowInstanceId id,
            String signalName,
            Object payload,
            String signalDedupKey,
            @Nullable String forkStepId,
            @Nullable String branchId,
            @Nullable DurableMetadata signalMetadata,
            TX tx);
}
