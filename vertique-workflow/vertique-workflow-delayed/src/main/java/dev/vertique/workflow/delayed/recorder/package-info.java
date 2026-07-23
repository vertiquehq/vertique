// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * {@link dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder} for
 * {@link dev.vertique.workflow.sideeffect.IntentKind#WORKFLOW_TIMER} intents.
 *
 * <p>The recorder enqueues a {@link dev.vertique.workflow.delayed.job.WorkflowTimerFireJob}
 * and inserts a {@link dev.vertique.workflow.timer.TimerRecord} row transactionally within
 * the workflow engine's transaction.
 */
package dev.vertique.workflow.delayed.recorder;
