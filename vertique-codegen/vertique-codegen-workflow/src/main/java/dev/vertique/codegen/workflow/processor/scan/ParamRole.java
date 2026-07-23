// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.scan;

/**
 * Identifies the role a parameter plays in a workflow contract operation method.
 *
 * <p>The scanner assigns exactly one role to each parameter in declaration order. Roles are
 * determined by the presence of a parameter-level annotation ({@code @IdempotencyKey},
 * {@code @BusinessKey}, {@code @SubjectRef}, {@code @SignalDedupKey}), the parameter's type
 * ({@code WorkflowInstanceId} → {@code INSTANCE_ID}), or — when none of the above applies —
 * the catch-all {@code PAYLOAD} role.
 */
public enum ParamRole {

    /** The operation payload; the primary data argument carrying business information. */
    PAYLOAD,

    /** A {@code WorkflowInstanceId} parameter identifying which instance to target. */
    INSTANCE_ID,

    /** A {@code String} parameter annotated with {@code @IdempotencyKey}. */
    IDEMPOTENCY_KEY,

    /** A {@code String} parameter annotated with {@code @BusinessKey}. */
    BUSINESS_KEY,

    /** A parameter annotated with {@code @SubjectRef} carrying a {@code WorkflowSubjectRef}. */
    SUBJECT_REF,

    /** A {@code String} parameter annotated with {@code @SignalDedupKey} for signal deduplication. */
    SIGNAL_DEDUP_KEY
}
