// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import jakarta.annotation.Nullable;

/**
 * A claimed command as a segment reads it after the fence held: still claimed by the segment's token and at the head
 * of its queue.
 *
 * <p>INTERNAL: framework wiring between the executor and the command repository.
 *
 * @param commandId   the command's row id
 * @param workflowId  the instance the command belongs to
 * @param kind        the command kind, for example {@code START}
 * @param payloadJson the command's payload as recorded, or {@code null}
 * @param targetJson  the command's target as recorded, or {@code null}
 * @param dedupKey    the caller's idempotency key as recorded, or {@code null}
 * @param explicitCarrierJson the explicit durable-context carrier a signal was recorded with, or {@code null}
 */
public record SegmentCommand(
        long commandId,
        WorkflowInstanceId workflowId,
        String kind,
        @Nullable String payloadJson,
        @Nullable String targetJson,
        @Nullable String dedupKey,
        @Nullable String explicitCarrierJson) {

    /**
     * Creates a command that carries no explicit carrier.
     *
     * @param commandId   the command's row id
     * @param workflowId  the instance the command belongs to
     * @param kind        the command kind
     * @param payloadJson the command's payload as recorded, or {@code null}
     * @param targetJson  the command's target as recorded, or {@code null}
     * @param dedupKey    the caller's idempotency key as recorded, or {@code null}
     */
    public SegmentCommand(
            long commandId,
            WorkflowInstanceId workflowId,
            String kind,
            @Nullable String payloadJson,
            @Nullable String targetJson,
            @Nullable String dedupKey) {
        this(commandId, workflowId, kind, payloadJson, targetJson, dedupKey, null);
    }
}
