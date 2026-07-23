// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

/**
 * SPI for observing <strong>persisted</strong> job state transitions.
 *
 * <p>Fired by the repository decorator <em>after</em> a persisted state transition is confirmed — by
 * {@code completeExecution} (success, failure, cancellation, dead-letter, abandon) or by either atomic
 * retry op, {@code failAndScheduleRetry} (the {@code FAILED} attempt) and {@code abandonAndScheduleRetry}
 * (the {@code ABANDONED} interruption). Unlike {@link JobInterceptor#onComplete} (which fires
 * pre-persistence on the handler reply path only), this observer sees the durable outcome on all paths,
 * including failure-retry, timeout, dead-node recovery, and cancel.
 *
 * <p>Each listener receives a curated, safe-by-type {@link JobExecutionStateTransitionEvent} — never the
 * raw {@link JobExecution}.
 *
 * <p><strong>Implementations MUST NOT block.</strong> The call runs synchronously on the job-completion
 * thread, which may be a Vert.x event loop, timer, or event-bus callback. Submit any async work
 * fire-and-forget (do not chain on it). Exceptions thrown here are swallowed and logged; later listeners
 * still run.
 *
 * <p>Register listeners via Dagger multibinding ({@code @IntoSet}) against
 * {@code Set<JobExecutionStateTransitionListener>}.
 */
public interface JobExecutionStateTransitionListener {

    /**
     * Called once with the curated facts of a persisted job state transition.
     *
     * @param event the safe-by-type transition event; never {@code null}
     */
    void onStateTransition(JobExecutionStateTransitionEvent event);
}
