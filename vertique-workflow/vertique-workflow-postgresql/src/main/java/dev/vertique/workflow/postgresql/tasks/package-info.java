// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * PostgreSQL-backed implementation of the {@link dev.vertique.workflow.tasks.TaskStore} SPI.
 *
 * <p>Contains {@link dev.vertique.workflow.postgresql.tasks.PgTaskStore}, which persists and
 * queries human task rows in the {@code workflow_tasks} table. All operations participate in the
 * caller-supplied transaction; this package does not open its own transactions.
 */
package dev.vertique.workflow.postgresql.tasks;
