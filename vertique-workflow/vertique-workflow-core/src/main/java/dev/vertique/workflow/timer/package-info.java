// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * SPI types and value objects for workflow timer management.
 *
 * <p>A workflow timer is a durable record that causes the engine to fire a callback at a future
 * {@link java.time.Instant}. Timers are used by two node types:
 * <ul>
 *   <li>{@link dev.vertique.workflow.plan.TimerNode} — standalone timer wait; the workflow
 *       suspends until the timer fires.</li>
 *   <li>{@link dev.vertique.workflow.plan.WaitSignalNode} with a
 *       {@link dev.vertique.workflow.plan.WaitSignalNode.TimeoutBranch} — timeout branch that
 *       fires if the expected signal does not arrive within the allowed window.</li>
 * </ul>
 *
 * <p>This package contains:
 * <ul>
 *   <li>{@link dev.vertique.workflow.timer.TimerRecord} — immutable snapshot of a timer row.</li>
 *   <li>{@link dev.vertique.workflow.timer.TimerStatus} — lifecycle status enum.</li>
 *   <li>{@link dev.vertique.workflow.timer.TimerStatusTransition} — result of a status-update
 *       CAS operation.</li>
 *   <li>{@link dev.vertique.workflow.timer.TimerStore} — transactional SPI implemented by the
 *       storage backend (e.g., {@code vertique-workflow-postgresql}).</li>
 * </ul>
 *
 * <p>Implementations of {@link dev.vertique.workflow.timer.TimerStore} live in the storage module
 * ({@code vertique-workflow-postgresql}). The timer executor (scheduling, recovery, firing) lives
 * in {@code vertique-workflow-delayed}.
 */
package dev.vertique.workflow.timer;
