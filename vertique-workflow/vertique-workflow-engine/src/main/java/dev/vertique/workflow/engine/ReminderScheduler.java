// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.ReminderSpec;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.timer.TimerIntentPayload;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerStatusTransition;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Package-private collaborator responsible for scheduling and cancelling {@link TimerPurpose#TASK_REMINDER}
 * timers and recording associated history entries.
 *
 * <p>This class is extracted from {@link WorkflowEngine} as part of the Phase-1 decomposition
 * (PRD-WF-006, Slice C2). It absorbs the reminder scheduling cluster defined in the plan §4:
 * {@code scheduleReminderTimers}, {@code scheduleReminderTimer}, {@code scheduleBranchReminderTimer},
 * {@code cancelPendingReminders}, and {@code appendReminderCancelHistoryIfApplied}.
 *
 * <p>The {@link ReminderEmission} record and reminder-kind constants are co-located here because
 * they originate in reminder scheduling logic, even though they are also referenced by the
 * {@link WorkflowEngine#taskReminderFired} method (which remains on the facade until Slice C7).
 * Both are package-private, so {@link WorkflowEngine} can still read them directly.
 *
 * <p>This class is a leaf in the dependency graph: it depends on the history repository SPI,
 * the recorder router, the timer store, and the clock. It has no reference to the transition
 * driver or fork/join coordinator.
 *
 * <p>Instances are {@code @Singleton} and constructed by Dagger via {@code @Inject}.
 */
@Singleton
final class ReminderScheduler {

    // --- Reminder-kind constants (part of the v1 wire contract for TASK_REMINDER events;
    //     see vertique-workflow/vertique-workflow-events/src/main/resources/META-INF/vertique/module.md).
    // Package-private so WorkflowEngine.taskReminderFired
    //     can read them without duplication until that method moves in Slice C7. ---

    /** Attribute value for {@code ONE_SHOT_OFFSET}-mode reminders in the {@code TASK_REMINDER} event. */
    static final String REMINDER_KIND_ONE_SHOT_OFFSET = "ONE_SHOT_OFFSET";

    /** Attribute value for {@code RECURRING}-mode reminders in the {@code TASK_REMINDER} event. */
    static final String REMINDER_KIND_RECURRING = "RECURRING";

    // --- Dependencies ---

    private final WorkflowHistoryRepository<SqlClient> history;
    private final RecorderRouter recorders;
    private final TimerStore<SqlClient> timerStore;
    private final Clock clock;

    /**
     * Constructs a new {@code ReminderScheduler}.
     *
     * @param history    the history repository used to obtain sequence numbers and append entries
     * @param recorders  the recorder router used to schedule timer side-effect intents
     * @param timerStore the timer store used to find and cancel scheduled reminder timer rows
     * @param clock      the wall-clock used to timestamp history entries
     */
    @Inject
    ReminderScheduler(
            WorkflowHistoryRepository<SqlClient> history,
            RecorderRouter recorders,
            TimerStore<SqlClient> timerStore,
            Clock clock) {
        this.history = history;
        this.recorders = recorders;
        this.timerStore = timerStore;
        this.clock = clock;
    }

    // --- Reminder scheduling ---

    /**
     * Schedules all initial reminder timers declared on {@code htn.reminders()} for a newly-created
     * task. For {@link ReminderSpec.OneShotOffsets}, one timer is scheduled per offset. For
     * {@link ReminderSpec.RecurringInterval}, only the first interval timer is scheduled; subsequent
     * ones are scheduled by {@link WorkflowEngine#taskReminderFired} as each fires. When
     * {@code reminders} is {@code null}, this is a no-op.
     *
     * <p>All initial reminders are anchored to the same {@code taskCreatedAt} instant captured by
     * the caller at task-row construction time, so {@link ReminderSpec.OneShotOffsets} fires are at
     * exact {@code created_at + offset} (no slippage from intermediate work between task INSERT and
     * reminder scheduling) and the first {@link ReminderSpec.RecurringInterval} fire is at exact
     * {@code created_at + interval}. Subsequent recurring fires re-anchor in
     * {@code taskReminderFired} off the previous timer's persisted {@code fire_at}.
     *
     * @param inst          the workflow instance that owns the task
     * @param htn           the human-task node that declared the reminder spec
     * @param taskId        the stable task UUID
     * @param taskCreatedAt the instant at which the task row was created (anchor for all offsets)
     * @param tx            the active transaction
     * @return a {@link Future} that completes when all initial reminder timer rows and history
     *     entries are persisted
     */
    Future<Void> scheduleReminderTimers(
            WorkflowInstance inst, HumanTaskNode htn, UUID taskId, Instant taskCreatedAt, SqlClient tx) {
        ReminderSpec spec = htn.reminders();
        if (spec == null) {
            return Future.succeededFuture();
        }
        if (spec instanceof ReminderSpec.OneShotOffsets osu) {
            Future<Void> chain = Future.succeededFuture();
            for (Duration offset : osu.offsetsFromTaskCreation()) {
                chain = chain.compose(v -> scheduleReminderTimer(inst, htn, taskId, taskCreatedAt.plus(offset), tx));
            }
            return chain;
        } else if (spec instanceof ReminderSpec.RecurringInterval ri) {
            // Only the first interval is scheduled; subsequent ones are chained in taskReminderFired.
            return scheduleReminderTimer(inst, htn, taskId, taskCreatedAt.plus(ri.interval()), tx);
        }
        return Future.succeededFuture();
    }

    /**
     * Schedules one {@link TimerPurpose#TASK_REMINDER} timer via the {@code WORKFLOW_TIMER}
     * recorder and appends a {@code TIMER_SCHEDULED} history entry.
     *
     * <p>The {@code fireAt} parameter is an absolute instant. Callers must compute it from a stable
     * anchor (task creation time for initial reminders, or the previous fire's persisted
     * {@code fire_at} for the next recurring reminder) so that recurring reminders maintain a
     * steady cadence and do NOT drift across executor delays. Clamping {@code fireAt} to {@code >=
     * clock.instant()} is the caller's choice — leaving a strictly past {@code fireAt} causes the
     * delayed-job system to fire it as soon as possible, which is desirable for catch-up after a
     * long outage.
     *
     * @param inst   the workflow instance that owns the task
     * @param htn    the human-task node whose reminders configuration produced this timer
     * @param taskId the stable task UUID; stored in the timer row for reminder cascade routing
     * @param fireAt the absolute instant at which the timer should fire
     * @param tx     the active transaction
     * @return a {@link Future} that completes when both the timer row and the history entry are
     *     persisted
     */
    Future<Void> scheduleReminderTimer(
            WorkflowInstance inst, HumanTaskNode htn, UUID taskId, Instant fireAt, SqlClient tx) {
        return history.nextSequence(inst.id(), tx).compose(seq -> {
            TimerIntentPayload timerPayload = new TimerIntentPayload(fireAt, TimerPurpose.TASK_REMINDER, taskId);
            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.WORKFLOW_TIMER,
                    htn.stepId(),
                    timerPayload,
                    Map.of(),
                    WorkflowSideEffectIntent.Correlation.singlePath(inst.id(), seq, inst.definitionId(), htn.stepId()));
            return recorders.route(intent, tx).compose(recorderResult -> {
                if (!(recorderResult instanceof RecorderResult.Timer timerResult)) {
                    return Future.failedFuture(new WorkflowDefinitionException(
                            "WORKFLOW_TIMER recorder must return RecorderResult.Timer for TASK_REMINDER; got "
                                    + recorderResult.getClass().getSimpleName()));
                }
                UUID timerId = timerResult.timerId();
                String histPayload = Json.encode(
                        new TimerScheduledHistoryPayload(htn.stepId(), timerId, fireAt, TimerPurpose.TASK_REMINDER));
                return history.append(
                        new WorkflowHistoryEntry(
                                inst.id(), seq, WorkflowEntryType.TIMER_SCHEDULED, histPayload, clock.instant()),
                        tx);
            });
        });
    }

    /**
     * Schedules a single {@link TimerPurpose#TASK_REMINDER} timer for a branch-owned task.
     * Mirrors {@link #scheduleReminderTimer} but populates the branch identity on the correlation
     * so the timer row's {@code branch_token_id / fork_step_id / branch_id} columns are persisted.
     *
     * @param inst          the parent workflow instance (provides workflow id and definition id)
     * @param htn           the human-task node whose reminders config produced this timer
     * @param taskId        the stable task UUID
     * @param branchTokenId the branch token that owns the task (and therefore this timer)
     * @param forkStepId    the fork step id (branch identity tuple)
     * @param branchId      the branch id (branch identity tuple)
     * @param fireAt        the absolute instant at which this reminder should fire
     * @param tx            the active transaction
     * @return a {@link Future} that completes when the timer row and history entry are persisted
     */
    Future<Void> scheduleBranchReminderTimer(
            WorkflowInstance inst,
            HumanTaskNode htn,
            UUID taskId,
            UUID branchTokenId,
            String forkStepId,
            String branchId,
            Instant fireAt,
            SqlClient tx) {
        return history.nextSequence(inst.id(), tx).compose(seq -> {
            TimerIntentPayload timerPayload = new TimerIntentPayload(fireAt, TimerPurpose.TASK_REMINDER, taskId);
            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.WORKFLOW_TIMER,
                    htn.stepId(),
                    timerPayload,
                    Map.of(),
                    new WorkflowSideEffectIntent.Correlation(
                            inst.id(), seq, inst.definitionId(), htn.stepId(), branchTokenId, forkStepId, branchId));
            return recorders.route(intent, tx).compose(recorderResult -> {
                if (!(recorderResult instanceof RecorderResult.Timer timerResult)) {
                    return Future.failedFuture(new WorkflowDefinitionException(
                            "WORKFLOW_TIMER recorder must return RecorderResult.Timer for branch TASK_REMINDER; got "
                                    + recorderResult.getClass().getSimpleName()));
                }
                UUID timerId = timerResult.timerId();
                String histPayload = Json.encode(
                        new TimerScheduledHistoryPayload(htn.stepId(), timerId, fireAt, TimerPurpose.TASK_REMINDER));
                return history.append(
                        new WorkflowHistoryEntry(
                                inst.id(), seq, WorkflowEntryType.BRANCH_TIMER_SCHEDULED, histPayload, clock.instant()),
                        tx);
            });
        });
    }

    // --- Reminder cancellation ---

    /**
     * Cancels all {@link TimerPurpose#TASK_REMINDER} timers associated with {@code taskId},
     * appending a {@code TIMER_CANCELLED} history entry for each.
     *
     * <p><b>Lock order (cycle-4 codex round-2 correction):</b> this method does NOT acquire the
     * task row lock first. Both the cancel cascade and the reminder-fire executor follow the same
     * lock order — {@code workflow_timers} BEFORE {@code workflow_tasks}. Specifically:
     * <ul>
     *   <li>Cancel cascade: non-locking SELECT for SCHEDULED reminders → row lock acquired by each
     *       {@code timerStore.markCancelled} UPDATE on {@code workflow_timers} → eventual task row
     *       lock by the caller's {@code markCompleted}/{@code markCancelled}/{@code markExpired}.</li>
     *   <li>Reminder-fire executor: row lock on the timer via {@code lockForFiring} → row lock on
     *       the task via {@code incrementRemindersFiredCount}.</li>
     * </ul>
     * Acquiring the task row lock first here would invert the order vs. the firing path and risk a
     * database deadlock (SQLSTATE 40P01).
     *
     * <p>Trade-off: a recurring-reminder firing tx may commit a new SCHEDULED reminder row AFTER
     * this scan returns but BEFORE the caller's {@code markCompleted}-style UPDATE acquires the
     * task row lock and CLOSES the task. The leftover reminder is harmless — when its delayed-job
     * eventually fires, the firing executor invokes {@code taskReminderFired} which calls
     * {@code incrementRemindersFiredCount} (gated on {@code status='OPEN'}), observes
     * {@link java.util.Optional#empty()} for the now-CLOSED task, and emits no history/event. The
     * executor then {@code markFired}s the timer and the orphan is gone. Cost: one wasted
     * delayed-job execution per orphaned reminder; never a correctness gap.
     *
     * @param taskId the task whose scheduled reminder timers should be cancelled
     * @param inst   the workflow instance that owns the task (used for history)
     * @param tx     the active transaction
     * @return a {@link Future} that completes when all reminder timers are cancelled
     */
    Future<Void> cancelPendingReminders(UUID taskId, WorkflowInstance inst, SqlClient tx) {
        return timerStore.findScheduledRemindersForTask(taskId, tx).compose(reminderIds -> {
            Future<Void> chain = Future.succeededFuture();
            Instant now = clock.instant();
            for (UUID timerId : reminderIds) {
                chain = chain.compose(v -> timerStore.markCancelled(timerId, now, tx))
                        .compose(transition -> appendReminderCancelHistoryIfApplied(transition, inst, timerId, tx));
            }
            return chain;
        });
    }

    /**
     * Appends a {@code TIMER_CANCELLED} history entry only when {@code markCancelled} actually
     * cancelled the row ({@link TimerStatusTransition#APPLIED}). For any LOST_TO_* transition the
     * row already reached a different terminal state (e.g. the firing executor won a race and
     * marked it FIRED) — appending a cancellation entry there would produce misleading history
     * showing both TIMER_FIRED and TIMER_CANCELLED for the same row.
     *
     * @param transition the status transition returned by {@code timerStore.markCancelled}
     * @param inst       the workflow instance that owns the timer (used for history)
     * @param timerId    the id of the reminder timer row
     * @param tx         the active transaction
     * @return a {@link Future} that completes when the history entry is inserted, or immediately
     *     if the transition was not {@link TimerStatusTransition#APPLIED}
     */
    Future<Void> appendReminderCancelHistoryIfApplied(
            TimerStatusTransition transition, WorkflowInstance inst, UUID timerId, SqlClient tx) {
        if (transition == TimerStatusTransition.APPLIED) {
            return appendTimerCancelledHistoryForReminder(inst, timerId, tx);
        }
        return Future.succeededFuture();
    }

    /**
     * Appends a {@code TIMER_CANCELLED} history entry for a cancelled reminder timer.
     *
     * <p>Uses {@link TimerCancelledCause#TASK_CLOSED} as the cause, since reminder timers are
     * only cancelled when the owning task is being closed (completed, cancelled, or expired).
     *
     * @param inst    the workflow instance that owns the timer
     * @param timerId the id of the reminder timer that was cancelled
     * @param tx      the active transaction
     * @return a {@link Future} that completes when the history entry is inserted
     */
    private Future<Void> appendTimerCancelledHistoryForReminder(WorkflowInstance inst, UUID timerId, SqlClient tx) {
        return history.nextSequence(inst.id(), tx).compose(seq -> {
            Instant now = clock.instant();
            String histPayload = Json.encode(new TimerCancelledHistoryPayload(
                    inst.currentStepId(), timerId, now, TimerCancelledCause.TASK_CLOSED));
            return history.append(
                    new WorkflowHistoryEntry(inst.id(), seq, WorkflowEntryType.TIMER_CANCELLED, histPayload, now), tx);
        });
    }

    // --- Package-private types used by WorkflowEngine.taskReminderFired ---

    /**
     * Pair returned by the {@code taskReminderFired} per-spec branch: the future that schedules the
     * next recurring reminder timer (or {@link Future#succeededFuture()} for terminal/one-shot
     * branches), and the attribute map for the {@code TASK_REMINDER} event.
     *
     * @param scheduleNext the future that schedules the next timer (or a no-op succeeded future)
     * @param attributes   the event attribute map for the {@code TASK_REMINDER} event emission
     */
    record ReminderEmission(Future<Void> scheduleNext, Map<String, Object> attributes) {}
}
