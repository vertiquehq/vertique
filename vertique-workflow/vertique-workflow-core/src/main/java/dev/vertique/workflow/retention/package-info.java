// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Workflow retention and archival SPI (cycle 4+).
 *
 * <p>Provides the {@link dev.vertique.workflow.retention.WorkflowRetentionService} interface for
 * soft-delete archival and hard-delete purge of terminal workflow instances. The PostgreSQL
 * implementation lives in {@code vertique-workflow-postgresql}. This package is SQL-free so that
 * application code can reference the service type without depending on the PostgreSQL stack.
 */
package dev.vertique.workflow.retention;
