// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Migration SPI for advancing workflow instances from one definition version to another.
 *
 * <p>Applications implement {@link dev.vertique.workflow.migration.WorkflowMigrationHandler} to
 * provide version-specific state transformations. Handlers are registered via the Dagger
 * {@code Set<WorkflowMigrationHandler<?, ?>>} multibinding declared by
 * {@link dev.vertique.workflow.migration.WorkflowMigrationModule} and aggregated at startup by
 * {@link dev.vertique.workflow.migration.DefaultWorkflowMigrationRegistry}.
 *
 * <p>The narrow context record passed to each handler, {@link dev.vertique.workflow.migration.MigrationContext},
 * references {@link dev.vertique.workflow.migration.WorkflowHistoryEntrySummary} — a projection
 * over the full {@link dev.vertique.workflow.state.WorkflowHistoryEntry} that lives in
 * {@code dev.vertique.workflow.state}.
 *
 * <p>PRD-WF-003 §7.4 — migration is a per-instance, handler-driven operation. Batch runners are
 * explicitly out of scope for v1.
 */
package dev.vertique.workflow.migration;
