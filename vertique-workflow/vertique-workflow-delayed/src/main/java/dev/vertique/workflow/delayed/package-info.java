// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Workflow timers and signal-wait timeouts.
 *
 * <p>Adds the {@code TimerNode} execution path and the {@code WaitSignalNode} timeout-branch path
 * to the workflow runtime, on top of the cycle-1 durable-saga MVP. Timer firing is durable across
 * JVM restarts via {@code vertique-job-delayed}'s standard polling and dead-node recovery; the
 * timer recorder enqueues the delayed job transactionally inside the workflow tx — there is no
 * outbox path for timers in cycle 2.
 *
 * <p>Module decoupling:
 * <ul>
 *   <li>{@code vertique-workflow-postgresql} does NOT depend on this module.</li>
 *   <li>This module does NOT depend on {@code vertique-workflow-postgresql} or any
 *       {@code inbox-outbox-*} module.</li>
 *   <li>Cancellation behavior the engine needs is exposed through workflow-core SPIs:
 *       {@code TimerStore<TX>} for storage, {@code TransactionalTimerCallbacks<TX>} for the
 *       internal lifecycle callbacks invoked by the firing job and recovery verticle.</li>
 * </ul>
 *
 * <p>Source-of-truth contract: cancellation is grounded in the {@code workflow_timers.status}
 * column. Signal-before-timeout race, {@code WorkflowOperations.cancel(...)} during a timer wait,
 * and the firing job's idempotency all share the same {@code FOR UPDATE} lock on the timer row
 * to serialize state transitions. Per the lock-order invariant, every tx that touches both
 * {@code workflow_timers} and {@code workflow_instances} acquires the timer-row lock first.
 *
 * <p><strong>Status:</strong> Incubating — API-breaking changes between cycles are explicitly
 * allowed. Promoted to Stable at cycle 4 (phase-1 close).
 *
 * @since cycle 2
 */
package dev.vertique.workflow.delayed;
