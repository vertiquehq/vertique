// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Validates parsed {@link dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument}
 * instances against the full set of structural, semantic, and registry-resolution rules defined in
 * PRD-WF-003 (FR-WF-DEF-101/102/103).
 *
 * <p>Key types:
 * <ul>
 *   <li>{@link dev.vertique.workflow.definition.validator.Violation} — immutable record describing
 *       one validation problem.</li>
 *   <li>{@link dev.vertique.workflow.definition.validator.WorkflowDefinitionViolations} — immutable
 *       aggregate of all violations; provides a human-readable {@code formatted()} report.</li>
 *   <li>{@link dev.vertique.workflow.definition.validator.WorkflowDefinitionDocumentValidator} —
 *       {@code @Singleton} that walks one document and accumulates all violations.</li>
 *   <li>{@link dev.vertique.workflow.definition.validator.WorkflowDefinitionLoadException} —
 *       thrown by {@code validateOrThrow} when violations are present; carries the structured
 *       aggregate.</li>
 * </ul>
 */
package dev.vertique.workflow.definition.validator;
