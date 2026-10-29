// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

/**
 * Result of firing a workflow timer.
 *
 * <p>The two constants allow the workflow executor, which fires a timer when its timer command
 * comes due, to distinguish between a first-delivery success and a duplicate-delivery no-op
 * without throwing an exception.
 */
public enum TimerFiringResult {

    /**
     * The timer event was applied — the workflow instance was advanced (or transitioned to a
     * timeout branch) and all state was persisted within the caller's transaction.
     */
    APPLIED,

    /**
     * The timer event was a no-op because the timer is no longer in {@link
     * dev.vertique.workflow.timer.TimerStatus#SCHEDULED} status. This happens when a duplicate
     * delivery races with a prior successful fire or a concurrent cancellation. The caller should
     * treat this as a successful idempotent delivery and settle the command.
     */
    STALE_NOOP
}
