// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.executor;

import java.time.Duration;

/**
 * An event of the workflow executor, as reported to a {@link WorkflowExecutorObserver}.
 *
 * <p>The interface is sealed, but the set of event types grows additively in later releases. An observer must
 * tolerate an event type it does not know, for example with a {@code default} arm of its {@code switch} that
 * ignores it. No event carries an instance id or a payload.
 */
public sealed interface WorkflowExecutorEvent {

    /**
     * A command reached a settled state, or was dead-lettered.
     *
     * @param kind    the command kind, for example {@code START}
     * @param outcome the outcome: {@code COMPLETE}, {@code REJECTED}, {@code STALE}, {@code FAILED},
     *                {@code SUPERSEDED}, {@code DEAD_LETTERED}, {@code CANCELLED} or {@code PARKED}
     * @param elapsed the time from the claim of the command to its outcome
     */
    record CommandSettled(String kind, String outcome, Duration elapsed) implements WorkflowExecutorEvent {}

    /**
     * A command that was not runnable was deferred.
     *
     * @param kind          the command kind
     * @param deferralCount the number of deferrals, this one included
     */
    record CommandDeferred(String kind, int deferralCount) implements WorkflowExecutorEvent {}

    /**
     * A segment failed transiently, rolled back and was scheduled for a retry that spends no attempt.
     *
     * @param kind           the command kind
     * @param transientCount the number of transient failures of the command, this one included
     */
    record TransientRetry(String kind, int transientCount) implements WorkflowExecutorEvent {}

    /**
     * A claim ended by a crash or a lease overrun, and the command was claimed again after its lease.
     *
     * @param kind the command kind
     */
    record LeaseExpired(String kind) implements WorkflowExecutorEvent {}

    /**
     * The state of the command queue, sampled periodically.
     *
     * @param available              the commands an executor could claim now
     * @param stuck                  the commands reported stuck
     * @param parked                 the parked commands
     * @param deadLettered           the dead-lettered commands
     * @param notificationQueueUsage the fill of the wake-up notification queue, from 0 to 1
     */
    record QueueSnapshot(long available, long stuck, long parked, long deadLettered, double notificationQueueUsage)
            implements WorkflowExecutorEvent {}

    /**
     * A recording found no actor to attribute it to.
     *
     * @param kind the command kind
     */
    record ActorAbsent(String kind) implements WorkflowExecutorEvent {}

    /**
     * This node's intake limit changed between admitting and refusing starts and signals from outside. Every node
     * reports its own transitions, including a node that runs no executor.
     *
     * <p>A node that opens intake because its reads keep failing reports the transition with {@code readFailed}
     * {@code true}, and the commands it counted last are then stale.
     *
     * @param refusing   {@code true} when the node now refuses, {@code false} when it now admits
     * @param due        the due commands counted by the last successful reading, at most the high mark plus one
     * @param readFailed {@code true} when the node admits again only because its reads kept failing
     */
    record IntakePressureChanged(boolean refusing, long due, boolean readFailed) implements WorkflowExecutorEvent {}
}
