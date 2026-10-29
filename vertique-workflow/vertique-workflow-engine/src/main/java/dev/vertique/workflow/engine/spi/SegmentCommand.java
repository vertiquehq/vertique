// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import jakarta.annotation.Nullable;
import java.util.UUID;

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
 * @param contextJson the durable context captured when the command was recorded, in the carrier wrapper, or
 *                    {@code null} when the capture was empty
 * @param timerKind   the timer kind of a {@code TIMER} command ({@code FIRE}, {@code TIMEOUT}, {@code DUE} or
 *                    {@code REMINDER}), or {@code null}
 * @param timerId     the timer row a {@code TIMER} command fires, or {@code null}
 * @param taskId      the task a {@code TIMER} command of a due date or reminder belongs to, or {@code null}
 * @param actor       who recorded the command, or {@code null} for a command recorded before actors were logged
 */
public record SegmentCommand(
        long commandId,
        WorkflowInstanceId workflowId,
        String kind,
        @Nullable String payloadJson,
        @Nullable String targetJson,
        @Nullable String dedupKey,
        @Nullable String explicitCarrierJson,
        @Nullable String contextJson,
        @Nullable String timerKind,
        @Nullable UUID timerId,
        @Nullable UUID taskId,
        @Nullable CommandActor actor) {

    /**
     * Creates a command that carries the timer fields and no recorded actor.
     *
     * @param commandId   the command's row id
     * @param workflowId  the instance the command belongs to
     * @param kind        the command kind
     * @param payloadJson the command's payload as recorded, or {@code null}
     * @param targetJson  the command's target as recorded, or {@code null}
     * @param dedupKey    the caller's idempotency key as recorded, or {@code null}
     * @param explicitCarrierJson the explicit durable-context carrier a signal was recorded with, or {@code null}
     * @param contextJson the durable context captured when the command was recorded, or {@code null}
     * @param timerKind   the timer kind of a {@code TIMER} command, or {@code null}
     * @param timerId     the timer row a {@code TIMER} command fires, or {@code null}
     * @param taskId      the task a {@code TIMER} command belongs to, or {@code null}
     */
    public SegmentCommand(
            long commandId,
            WorkflowInstanceId workflowId,
            String kind,
            @Nullable String payloadJson,
            @Nullable String targetJson,
            @Nullable String dedupKey,
            @Nullable String explicitCarrierJson,
            @Nullable String contextJson,
            @Nullable String timerKind,
            @Nullable UUID timerId,
            @Nullable UUID taskId) {
        this(
                commandId,
                workflowId,
                kind,
                payloadJson,
                targetJson,
                dedupKey,
                explicitCarrierJson,
                contextJson,
                timerKind,
                timerId,
                taskId,
                null);
    }

    /**
     * Creates a command that is not a timer.
     *
     * @param commandId   the command's row id
     * @param workflowId  the instance the command belongs to
     * @param kind        the command kind
     * @param payloadJson the command's payload as recorded, or {@code null}
     * @param targetJson  the command's target as recorded, or {@code null}
     * @param dedupKey    the caller's idempotency key as recorded, or {@code null}
     * @param explicitCarrierJson the explicit durable-context carrier a signal was recorded with, or {@code null}
     * @param contextJson the durable context captured when the command was recorded, or {@code null}
     */
    public SegmentCommand(
            long commandId,
            WorkflowInstanceId workflowId,
            String kind,
            @Nullable String payloadJson,
            @Nullable String targetJson,
            @Nullable String dedupKey,
            @Nullable String explicitCarrierJson,
            @Nullable String contextJson) {
        this(
                commandId,
                workflowId,
                kind,
                payloadJson,
                targetJson,
                dedupKey,
                explicitCarrierJson,
                contextJson,
                null,
                null,
                null);
    }

    /**
     * Creates a command that carries no explicit carrier and no recorded context.
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
        this(commandId, workflowId, kind, payloadJson, targetJson, dedupKey, null, null);
    }

    /**
     * Creates a command that carries no recorded context.
     *
     * @param commandId   the command's row id
     * @param workflowId  the instance the command belongs to
     * @param kind        the command kind
     * @param payloadJson the command's payload as recorded, or {@code null}
     * @param targetJson  the command's target as recorded, or {@code null}
     * @param dedupKey    the caller's idempotency key as recorded, or {@code null}
     * @param explicitCarrierJson the explicit durable-context carrier a signal was recorded with, or {@code null}
     */
    public SegmentCommand(
            long commandId,
            WorkflowInstanceId workflowId,
            String kind,
            @Nullable String payloadJson,
            @Nullable String targetJson,
            @Nullable String dedupKey,
            @Nullable String explicitCarrierJson) {
        this(commandId, workflowId, kind, payloadJson, targetJson, dedupKey, explicitCarrierJson, null);
    }
}
