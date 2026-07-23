// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * PostgreSQL-backed implementation of the workflow retention SPI.
 *
 * <p>Provides {@link dev.vertique.workflow.postgresql.retention.PgWorkflowRetentionService},
 * which archives and purges workflow instances in configurable batches using
 * {@code FOR UPDATE SKIP LOCKED} for safe concurrent execution.
 */
package dev.vertique.workflow.postgresql.retention;
