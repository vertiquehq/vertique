// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * PostgreSQL dialect adapters for the portable workflow engine: transaction runner, exception
 * mapping, {@link WorkflowPostgresqlModule} (which {@code include}s
 * {@link dev.vertique.workflow.engine.WorkflowEngineModule}) and {@link WorkflowMigrationsModule}.
 * Side-effect routing lives in {@code RecorderRouter} (package-private in
 * {@code vertique-workflow-engine}).
 *
 * <p>{@link WorkflowPostgresqlModule} provides the eight PostgreSQL workflow stores as scoped
 * instances built with the optional {@code DbSchemas} placement, so every workflow table is read and
 * written in the schema mapped for {@code workflow} (and resolves through the connection's default
 * schema when no mapping exists). {@link WorkflowMigrationsModule} declares the packaged schema
 * migrations as the {@code workflow} migration instance for Flyway; it is included by
 * {@link WorkflowPostgresqlModule} and can also be installed alone in a component that only runs
 * migrations.
 *
 * <p>{@link WorkflowMigrationsModule} declares database schema migrations. It is unrelated to
 * {@link dev.vertique.workflow.migration.WorkflowMigrationModule}, which wires in-flight workflow
 * definition migration and differs from it by one letter.
 */
package dev.vertique.workflow.postgresql.engine;
