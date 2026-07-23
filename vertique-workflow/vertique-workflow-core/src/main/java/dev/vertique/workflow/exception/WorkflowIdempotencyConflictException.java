// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown when an idempotency key has been used before with a different operation fingerprint,
 * indicating the caller is attempting a logically different operation under the same key.
 *
 * <p>This is a programming error in the caller — idempotency keys must be unique per logical
 * operation. Reusing a key with different parameters (different decision, different payload, etc.)
 * is rejected to prevent silent data corruption.
 */
public class WorkflowIdempotencyConflictException extends WorkflowConflictException {

    private final String kind;
    private final String idempotencyKey;
    private final String existingFingerprint;
    private final String incomingFingerprint;

    /**
     * Creates a new {@code WorkflowIdempotencyConflictException}.
     *
     * @param kind the operation kind that was being attempted (e.g., {@code "task-complete"},
     *     {@code "task-reassign"})
     * @param idempotencyKey the idempotency key that was already used with a different fingerprint
     * @param existingFingerprint the fingerprint recorded by the first (winning) operation
     * @param incomingFingerprint the fingerprint of the current (conflicting) operation
     */
    public WorkflowIdempotencyConflictException(
            String kind, String idempotencyKey, String existingFingerprint, String incomingFingerprint) {
        super("Idempotency conflict for kind='" + kind + "', key='" + idempotencyKey
                + "': existing fingerprint='" + existingFingerprint
                + "', incoming fingerprint='" + incomingFingerprint + "'");
        this.kind = kind;
        this.idempotencyKey = idempotencyKey;
        this.existingFingerprint = existingFingerprint;
        this.incomingFingerprint = incomingFingerprint;
    }

    /**
     * Returns the operation kind (e.g., {@code "task-complete"}, {@code "task-reassign"}).
     *
     * @return the kind string
     */
    public String kind() {
        return kind;
    }

    /**
     * Returns the idempotency key that conflicted.
     *
     * @return the idempotency key
     */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    /**
     * Returns the fingerprint recorded by the first (winning) operation.
     *
     * @return the existing fingerprint
     */
    public String existingFingerprint() {
        return existingFingerprint;
    }

    /**
     * Returns the fingerprint of the current (conflicting) operation.
     *
     * @return the incoming fingerprint
     */
    public String incomingFingerprint() {
        return incomingFingerprint;
    }
}
