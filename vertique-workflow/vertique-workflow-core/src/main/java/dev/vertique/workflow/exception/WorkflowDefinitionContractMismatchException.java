// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.registry.store.DefinitionKey;

/**
 * Thrown when a caller's contract differs from the contract of the definition it addresses.
 *
 * <p>A signal or query sent through a client of one contract to an instance pinned to a definition
 * version of another contract fails with this exception, and the instance, its history and its
 * deduplication rows are left as they were.
 *
 * <p>The message names definition ids, versions and contract names only; it never includes payloads,
 * state or document content.
 */
public class WorkflowDefinitionContractMismatchException extends WorkflowDefinitionException {

    private final transient DefinitionKey definitionKey;

    /**
     * Creates a new {@code WorkflowDefinitionContractMismatchException}.
     *
     * @param definitionKey the definition the caller addressed
     * @param message description of the mismatch; names ids, versions and contract names only, never
     *     payloads or state
     */
    public WorkflowDefinitionContractMismatchException(DefinitionKey definitionKey, String message) {
        super(message);
        this.definitionKey = definitionKey;
    }

    /**
     * Returns the definition the caller addressed.
     *
     * @return the definition key; never {@code null}
     */
    public DefinitionKey definitionKey() {
        return definitionKey;
    }
}
