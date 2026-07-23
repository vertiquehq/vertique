// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.job;

import dev.vertique.job.delayed.DelayedJobClient;
import dev.vertique.job.delayed.DelayedJobContract;

/**
 * Typed delayed job contract for firing workflow timers.
 *
 * <p>When the workflow engine reaches a {@link dev.vertique.workflow.plan.TimerNode} or a
 * {@link dev.vertique.workflow.plan.WaitSignalNode} with a timeout branch, the
 * {@link dev.vertique.workflow.delayed.recorder.WorkflowTimerSideEffectRecorder} enqueues a job
 * via this contract. The job becomes eligible at the timer's {@code fireAt} instant and is
 * executed by {@link WorkflowTimerFireExecutor}.
 *
 * <p>The name {@code "vertique.workflow.timer.fire"} is the stable event bus address segment used
 * by the job infrastructure. It must not be changed without a migration of in-flight job rows.
 *
 * <p>Up to 5 attempts are allowed. Each attempt locks the timer row for update before calling the
 * engine callback, so duplicate deliveries are safe.
 *
 * @see WorkflowTimerFireExecutor
 * @see TimerFirePayload
 */
@DelayedJobContract(name = "vertique.workflow.timer.fire", maxAttempts = 5)
public interface WorkflowTimerFireJob extends DelayedJobClient<TimerFirePayload> {}
