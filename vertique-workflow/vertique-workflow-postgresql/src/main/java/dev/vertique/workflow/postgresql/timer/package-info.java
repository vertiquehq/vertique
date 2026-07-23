// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * PostgreSQL-backed implementation of the {@link dev.vertique.workflow.timer.TimerStore} SPI.
 *
 * <p>Contains {@link dev.vertique.workflow.postgresql.timer.PgTimerStore}, which persists and
 * queries timer state in the {@code workflow_timers} table using the caller's transaction context.
 */
package dev.vertique.workflow.postgresql.timer;
