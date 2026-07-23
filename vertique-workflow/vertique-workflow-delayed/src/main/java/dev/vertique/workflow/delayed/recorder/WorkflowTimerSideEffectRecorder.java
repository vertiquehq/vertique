// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.recorder;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.job.delayed.DelayedJobOptions;
import dev.vertique.workflow.delayed.WorkflowTimerCarriers;
import dev.vertique.workflow.delayed.compose.WorkflowDelayedComposeValidator;
import dev.vertique.workflow.delayed.job.TimerFirePayload;
import dev.vertique.workflow.delayed.job.WorkflowTimerFireJob;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.timer.TimerIntentPayload;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * {@link WorkflowSideEffectRecorder} for {@link IntentKind#WORKFLOW_TIMER} intents.
 *
 * <p>When the workflow engine schedules a durable timer (standalone, signal-timeout, due-date, or
 * reminder), it emits a {@code WORKFLOW_TIMER} intent whose payload is a {@link TimerIntentPayload}
 * carrying the {@link TimerPurpose} and (for task-scoped purposes) the owning task id. This
 * recorder:
 * <ol>
 *   <li>Extracts the {@code fireAt} instant and timer metadata from the intent payload.</li>
 *   <li>Generates a stable {@code timerId} UUID.</li>
 *   <li>Captures ambient durable context exactly once for the recovery-state copy via the
 *       carrier-threading {@link DurableContextPropagator#mergeCaptured(dev.vertique.core.context.DurableMetadata,
 *       java.lang.String, DurableCarrierDescriptor)} overload, signed for the
 *       {@link WorkflowTimerCarriers workflow-timer carrier} — not the delayed-job carrier the
 *       underlying job execution row uses. See {@link WorkflowTimerCarriers} for why the two
 *       carriers must differ (P2.S0 commit 4, PRD identity-002 §14.6/A9, F5 row binding).</li>
 *   <li>Enqueues a {@link WorkflowTimerFireJob} transactionally within the caller's transaction via
 *       <em>ordinary</em> enqueue — {@link DelayedJobOptions#premergedMetadata()} is left
 *       {@code null} — so {@code DelayedJobService} independently captures and signs the ambient
 *       durable context (including any bound identity snapshot) for the job's own execution
 *       carrier. A premerged blob signed for the workflow-timer carrier would be unbindable at the
 *       job's own carrier and is rejected by {@code DelayedJobService.enqueuePremerged} when it
 *       carries the identity-snapshot namespace.</li>
 *   <li>Inserts a {@link TimerRecord} with {@link TimerStatus#SCHEDULED} into
 *       {@code workflow_timers} with the workflow-timer-carrier-signed
 *       {@link dev.vertique.core.context.DurableMetadata} document in
 *       {@link TimerRecord#metadata()} — the recovery-state copy that allows
 *       {@link dev.vertique.workflow.delayed.recovery.WorkflowTimerRecoveryService} to
 *       re-enqueue orphaned timers with the original durable context, verified against the
 *       reproducible workflow-timer carrier.</li>
 *   <li>Returns {@link RecorderResult#ofTimer(UUID)} so the engine can correlate the timer ID
 *       with the workflow instance's wait state.</li>
 * </ol>
 *
 * <p>The two metadata documents ({@code workflow_timers.metadata} and the underlying delayed
 * job's {@code job_executions.metadata}) are captured independently, each signed for its own
 * carrier, and written within one transaction, ensuring recovery always has an original durable
 * context available that can be verified before re-enqueue.
 *
 * <p>The recorder is intentionally side-effect-free beyond the transactional writes: it never
 * makes live downstream calls, ensuring the workflow transaction stays bounded and roll-backable.
 *
 * <p>The {@link WorkflowDelayedComposeValidator} is a required constructor parameter that forces
 * Dagger to construct and run the startup validator before this recorder can be used. See
 * {@link WorkflowDelayedComposeValidator} for the validation contract.
 */
@Singleton
public final class WorkflowTimerSideEffectRecorder implements WorkflowSideEffectRecorder<SqlClient> {

    private final TimerStore<SqlClient> timerStore;
    private final WorkflowTimerFireJob fireJob;
    private final Clock clock;
    private final DurableContextPropagator propagator;

    /**
     * Creates a new timer side-effect recorder.
     *
     * <p>The {@code composeValidator} parameter is unused after construction. It exists purely to
     * force Dagger to construct the validator (which performs startup checks) before this recorder
     * is created. This mirrors the pattern used by
     * {@link dev.vertique.workflow.services.recorder.OutboxSideEffectRecorder}.
     *
     * @param timerStore       SPI for persisting timer rows; must use the caller's transaction
     * @param fireJob          typed delayed job client for enqueueing timer-fire jobs
     * @param clock            source of the current time for {@code scheduledAt} timestamps
     * @param propagator       durable context propagator; used to capture ambient context at
     *                         timer-create time under the {@link DispatchBoundary#DELAYED_JOB}
     *                         boundary
     * @param composeValidator startup validator; injected for its construction side-effect only
     */
    @Inject
    public WorkflowTimerSideEffectRecorder(
            TimerStore<SqlClient> timerStore,
            WorkflowTimerFireJob fireJob,
            Clock clock,
            DurableContextPropagator propagator,
            WorkflowDelayedComposeValidator composeValidator) {
        this.timerStore = timerStore;
        this.fireJob = fireJob;
        this.clock = clock;
        this.propagator = propagator;
        // composeValidator is intentionally unused after construction; injection here forces the
        // validator's checks to run during Dagger graph instantiation, not opt-in at app boot.
    }

    /**
     * Returns {@link IntentKind#WORKFLOW_TIMER}.
     *
     * @return the intent kind handled by this recorder
     */
    @Override
    public IntentKind kind() {
        return IntentKind.WORKFLOW_TIMER;
    }

    /**
     * Records a {@link WorkflowSideEffectIntent} as a transactional timer row and delayed job.
     *
     * <p>The intent's {@link WorkflowSideEffectIntent#payload()} must be a {@link TimerIntentPayload}
     * carrying the {@link TimerPurpose} and (for task-scoped purposes) the owning task id.
     *
     * @param intent the side-effect intent to record; must have {@code kind=WORKFLOW_TIMER} and a
     *               {@link TimerIntentPayload} payload
     * @param tx     the open database transaction to use for the delayed-job enqueue and timer insert
     * @return a {@link Future} that completes with {@link RecorderResult#ofTimer(UUID)} on success,
     *         or fails if the enqueue or insert fails
     * @throws IllegalArgumentException via failed future if the payload is not a {@link TimerIntentPayload}
     */
    @Override
    public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
        if (!(intent.payload() instanceof TimerIntentPayload tip)) {
            return Future.failedFuture(new IllegalArgumentException(
                    "WorkflowTimerSideEffectRecorder requires a TimerIntentPayload payload; got "
                            + (intent.payload() == null
                                    ? "null"
                                    : intent.payload().getClass().getName())));
        }
        Instant fireAt = tip.fireAt();
        TimerPurpose purpose = tip.purpose();
        UUID taskId = tip.taskId();
        WorkflowInstanceId workflowId = intent.correlation().workflowId();

        UUID timerId = UUID.randomUUID();

        // Recovery-state copy: capture ambient durable context once, signed for THIS timer's own
        // workflow-timer carrier (reproducible from workflow_timers.timer_id / .workflow_id alone —
        // see WorkflowTimerCarriers) rather than the delayed-job carrier the underlying job
        // execution row uses. This is what lets recovery verify row.metadata() against the exact
        // carrier it was signed for before re-enqueueing (P2.S0 commit 4, F5 row binding).
        DurableCarrierDescriptor timerCarrier = WorkflowTimerCarriers.of(timerId, workflowId);
        DurableMetadata recoveryMetadata =
                propagator.mergeCaptured(DurableMetadata.empty(), DispatchBoundary.DELAYED_JOB, timerCarrier);

        TimerFirePayload jobPayload = new TimerFirePayload(timerId, workflowId, null);
        // Ordinary enqueue — premergedMetadata is deliberately left null — so DelayedJobService
        // independently captures and signs ambient durable context (including any bound identity
        // snapshot) for the job's own execution carrier. Threading recoveryMetadata here instead
        // would sign the job's row with a document bound to the workflow-timer carrier, making it
        // unbindable at the job's own carrier (and DelayedJobService.enqueuePremerged now rejects
        // premerged metadata carrying the identity-snapshot namespace outright).
        DelayedJobOptions options = DelayedJobOptions.builder().runAt(fireAt).build();

        return fireJob.enqueue(jobPayload, options, tx).compose(executionId -> {
            TimerRecord record = new TimerRecord(
                    timerId,
                    workflowId,
                    intent.targetId(),
                    fireAt,
                    TimerStatus.SCHEDULED,
                    executionId,
                    clock.instant(),
                    null,
                    null,
                    null,
                    null,
                    purpose,
                    taskId,
                    intent.correlation().branchTokenId(),
                    intent.correlation().forkStepId(),
                    intent.correlation().branchId(),
                    recoveryMetadata); // recovery-state copy, signed for the workflow-timer carrier
            return timerStore.insertScheduled(record, tx).map(v -> RecorderResult.ofTimer(timerId));
        });
    }
}
