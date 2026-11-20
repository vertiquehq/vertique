// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Workflow exception hierarchy. Each failure extends the workflow root for its kind:
 *
 * <ul>
 *   <li>{@link dev.vertique.workflow.exception.WorkflowException} (business rule, HTTP 400): an invalid
 *       start, signal or decision payload ({@code WorkflowInvalidPayloadException}), a signal with no
 *       usable actor ({@code WorkflowSignalActorException}), a signal past its limit
 *       ({@code WorkflowSignalLimitException}), a signal that matches no wait
 *       ({@code WorkflowSignalNoMatchException}) and an unavailable subject version
 *       ({@code WorkflowSubjectVersionUnavailableException}).</li>
 *   <li>{@link dev.vertique.workflow.exception.WorkflowConfigurationException}: definition errors
 *       ({@code WorkflowDefinitionException} and its subtypes), proxy contract violations
 *       ({@code WorkflowProxyContractException}) and other configuration, node-role and plan-hash
 *       problems.</li>
 *   <li>{@link dev.vertique.workflow.exception.WorkflowConflictException}: state conflicts, including
 *       signal rejection ({@code WorkflowSignalRejectedException}).</li>
 *   <li>{@link dev.vertique.workflow.exception.WorkflowNotFoundException}: a missing instance or task,
 *       and a definition with no row or no current version.</li>
 *   <li>{@link dev.vertique.workflow.exception.WorkflowUnavailableException}: an intake refusal and a
 *       version pin that is unknown, unavailable or not runnable
 *       ({@code WorkflowVersionPinUnavailableException} and its subtypes).</li>
 *   <li>{@link dev.vertique.workflow.exception.WorkflowTechnicalException}: technical and persistence
 *       failures.</li>
 * </ul>
 *
 * <p>{@code WorkflowException} is the business-rule root only. It is not the superclass of every workflow
 * failure, so a handler that catches it does not see the failures of the other roots.
 */
package dev.vertique.workflow.exception;
