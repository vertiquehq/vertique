// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Compile-time structural validators for {@code @WorkflowContract} interfaces (FR-WF-CG-040..049):
 * non-interface targets, operation-annotation cardinality, return types, key-source presence, duplicate
 * signal names, and parameter-annotation type/cardinality. Registry/plan-dependent checks remain runtime.
 */
package dev.vertique.codegen.workflow.processor.validate;
