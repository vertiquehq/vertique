// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Parse → validate → compile pipeline for workflow definition documents.
 *
 * <p>The single public class {@link dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline}
 * orchestrates the three pipeline stages for a single
 * {@link dev.vertique.workflow.definition.source.DefinitionResource}. It has no dependency on
 * {@link dev.vertique.workflow.registry.WorkflowRegistry} so it can be used at startup (via
 * {@link dev.vertique.workflow.definition.service.WorkflowDefinitionBootstrap}) and at runtime
 * (via {@link dev.vertique.workflow.definition.service.DefaultWorkflowDefinitionService})
 * without a Dagger cycle.
 */
package dev.vertique.workflow.definition.pipeline;
