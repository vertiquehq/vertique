// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

/**
 * A plan node that suspends the workflow until a durable timer fires.
 *
 * <p>When the engine reaches this node it schedules a durable timer via the
 * {@link dev.vertique.workflow.sideeffect.IntentKind#WORKFLOW_TIMER} recorder, sets the instance
 * status to {@code WAITING} with {@code waitType="TIMER"}, and suspends until the timer fires.
 * When the timer fires, the engine transitions the instance to {@code RUNNING} and advances to
 * {@code nextStepId}.
 *
 * @param stepId unique step identifier within the plan
 * @param nextStepId step to advance to after the timer fires
 * @param spec how the timer's fire time is determined
 */
public record TimerNode(String stepId, String nextStepId, TimerSpec spec) implements WorkflowNode {}
