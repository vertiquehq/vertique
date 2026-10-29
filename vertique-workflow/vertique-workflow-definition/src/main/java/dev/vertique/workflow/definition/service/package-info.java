// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Runtime service for loading, publishing, and querying document-backed workflow definitions.
 *
 * <p>The key types are:
 * <ul>
 *   <li>{@link dev.vertique.workflow.definition.service.WorkflowDefinitionService} — public SPI
 *       for loading, publishing, and listing document-backed definitions at runtime.</li>
 *   <li>{@link dev.vertique.workflow.definition.service.DefaultWorkflowDefinitionService} — the
 *       singleton implementation; uses {@code Provider<WorkflowRegistry>} to break the Dagger
 *       cycle.</li>
 *   <li>{@link dev.vertique.workflow.definition.service.CompiledDefinitionRef} — a safe,
 *       non-generic projection of a stored definition for external callers.</li>
 *   <li>{@link dev.vertique.workflow.definition.service.ActivationStatus} — lifecycle state
 *       for management surfaces; never overrides registry semantics.</li>
 *   <li>{@link dev.vertique.workflow.definition.service.WorkflowDefinitionBootstrap} — startup
 *       {@link dev.vertique.workflow.registry.WorkflowContributor} that iterates
 *       {@code Set<WorkflowDefinitionSource>} and compiles the packaged documents before the application
 *       starts serving traffic. It registers nothing; the engine's startup records the documents in the
 *       definition store.</li>
 * </ul>
 */
package dev.vertique.workflow.definition.service;
