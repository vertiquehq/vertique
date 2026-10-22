// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown when a start names a business key that another instance of the same definition id already holds
 * in the same start scope.
 *
 * <p>A business key is unique per start scope and definition id. The refusal is a conflict, not a
 * persistence failure: retrying the same start cannot succeed while the other instance exists. The message
 * names the definition id and start scope and does not echo the business key; the caller supplied it.
 */
public class WorkflowBusinessKeyConflictException extends WorkflowConflictException {

    private final String scopeKey;
    private final String definitionId;

    /**
     * Creates a new {@code WorkflowBusinessKeyConflictException}.
     *
     * @param scopeKey the start scope in which the business key is already held
     * @param definitionId the definition id whose instances hold the business key
     * @param cause the underlying uniqueness failure, or {@code null}
     */
    public WorkflowBusinessKeyConflictException(String scopeKey, String definitionId, Throwable cause) {
        super(
                "A workflow instance of definition '" + definitionId + "' in scope '" + scopeKey
                        + "' already holds this business key",
                cause);
        this.scopeKey = scopeKey;
        this.definitionId = definitionId;
    }

    /**
     * Returns the start scope in which the business key is already held.
     *
     * @return the scope key
     */
    public String scopeKey() {
        return scopeKey;
    }

    /**
     * Returns the definition id whose instances hold the business key.
     *
     * @return the definition id
     */
    public String definitionId() {
        return definitionId;
    }
}
