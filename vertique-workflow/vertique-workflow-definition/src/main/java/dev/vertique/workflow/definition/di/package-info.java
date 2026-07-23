// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Dagger wiring for {@code vertique-workflow-definition}: {@link
 * dev.vertique.workflow.definition.di.WorkflowDefinitionModule} aggregates application-provided
 * sources, named callback contributors, the expression profile, parser/compiler/service singletons,
 * and the startup {@code WorkflowContributor} that registers compiled documents into the
 * {@code WorkflowRegistry}.
 */
package dev.vertique.workflow.definition.di;
