// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Source emitters for {@code {Contract}_WorkflowClientProxy} (a static, zero-reflection implementation of a
 * {@code @WorkflowContract} interface that delegates to {@code WorkflowOperations}) and for
 * {@code GeneratedWorkflowClientsModule} (a Dagger module binding each contract via
 * {@code WorkflowClientFactory.create}).
 */
package dev.vertique.codegen.workflow.processor.emit;
