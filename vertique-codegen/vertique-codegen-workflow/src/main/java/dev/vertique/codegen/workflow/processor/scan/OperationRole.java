// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.scan;

/**
 * Identifies the workflow operation kind declared on a contract method.
 *
 * <p>Each constant corresponds to one of the method-level annotations in
 * {@code dev.vertique.workflow.contract}: {@code @WorkflowStart}, {@code @WorkflowSignal}, and
 * {@code @WorkflowQuery}. A method may carry more than one of these annotations (a contract
 * shape violation that the validator catches), in which case the
 * {@link OperationModel#declaredRoles()} list will contain multiple entries.
 */
public enum OperationRole {

    /** Corresponds to {@code @WorkflowStart} — the method starts a new workflow instance. */
    START,

    /** Corresponds to {@code @WorkflowSignal} — the method delivers a signal to a running instance. */
    SIGNAL,

    /** Corresponds to {@code @WorkflowQuery} — the method queries the state of a running instance. */
    QUERY
}
