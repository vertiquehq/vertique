// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Annotation processor that generates static workflow client proxies and a Dagger module from
 * {@code @WorkflowContract}-annotated interfaces.
 *
 * <p>The central entry point is
 * {@link dev.vertique.codegen.workflow.processor.WorkflowContractProcessor}. Supporting classes are
 * organized into sub-packages:
 * <ul>
 *   <li>{@code scan} — APT-side scanner that classifies contract methods (start/signal/query) and
 *       resolves parameter roles</li>
 *   <li>{@code validate} — compile-time structural contract-shape validators</li>
 *   <li>{@code emit} — source emitters for {@code {Contract}_WorkflowClientProxy} and
 *       {@code GeneratedWorkflowClientsModule}</li>
 * </ul>
 */
package dev.vertique.codegen.workflow.processor;
