// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.sideeffect;

import io.vertx.core.Future;

/**
 * SPI for durably recording a {@link WorkflowSideEffectIntent} within a caller-supplied
 * transaction.
 *
 * <p>Implementations must NOT make live downstream calls (e.g., HTTP, Kafka publish). They must
 * only write a durable record (e.g., an outbox row) within the caller's transaction. Delivery to
 * the downstream system happens after commit via a relay process.
 *
 * <p>Each implementation owns exactly one {@link IntentKind}. The {@code RecorderRouter} enforces
 * this invariant at construction time: duplicate kinds or missing kinds for emitted intents both
 * fail fast.
 *
 * <p>Implementations are contributed to the Dagger graph via the {@code @WorkflowRecorders}
 * qualified multibinding.
 *
 * @param <TX> the transaction context type (e.g., {@code SqlClient} in the PostgreSQL stack)
 */
public interface WorkflowSideEffectRecorder<TX> {

    /**
     * Returns the {@link IntentKind} that this recorder handles.
     *
     * <p>The {@code RecorderRouter} uses this value to route each intent to exactly one recorder.
     *
     * @return the intent kind owned by this recorder; never null
     */
    IntentKind kind();

    /**
     * Records a durable side-effect intent within the caller's transaction.
     *
     * <p>This method must NOT make live downstream calls. It should only write durable state (e.g.,
     * an outbox row) that a relay process will deliver after the transaction commits.
     *
     * <p>Most recorders produce no actionable output beyond confirming persistence and should return
     * {@link RecorderResult#empty()}. Recorders that schedule durable timers (e.g., the
     * {@link IntentKind#WORKFLOW_TIMER} recorder) return {@link RecorderResult#ofTimer(java.util.UUID)}
     * so the caller can correlate the timer id with the workflow instance wait state.
     *
     * @param intent the side-effect intent to record
     * @param tx the active transaction context; all writes must use this context
     * @return a {@link Future} that completes with the recording result when the intent has been
     *     durably recorded
     */
    Future<RecorderResult> record(WorkflowSideEffectIntent intent, TX tx);
}
