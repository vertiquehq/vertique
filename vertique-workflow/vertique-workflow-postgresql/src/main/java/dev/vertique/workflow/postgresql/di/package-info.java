// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Dagger wiring entry for workflow PostgreSQL. Prefer
 * {@link dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule} in
 * {@code dev.vertique.workflow.postgresql.engine}, which {@code include}s the portable
 * {@link dev.vertique.workflow.engine.WorkflowEngineModule} and binds PostgreSQL repositories,
 * stores, and query/retention services.
 */
package dev.vertique.workflow.postgresql.di;
