// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * PostgreSQL dialect adapters for the portable workflow engine: transaction runner, exception
 * mapping, and {@link WorkflowPostgresqlModule} (which {@code include}s
 * {@link dev.vertique.workflow.engine.WorkflowEngineModule}). Side-effect routing lives in
 * {@link dev.vertique.workflow.engine.RecorderRouter}.
 */
package dev.vertique.workflow.postgresql.engine;
