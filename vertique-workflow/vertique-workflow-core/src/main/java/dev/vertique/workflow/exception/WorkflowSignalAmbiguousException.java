// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown when a signal addressed by contract, signal name and key finds more than one open wait record: several
 * instances, several definitions or scopes, or several branches of one instance wait on the same key.
 *
 * <p>The delivery fails in the caller's transaction and writes nothing: no command, no deduplication row and no history
 * entry. Narrowing the signal by definition id or scope may select one wait. The message names the contract and the
 * signal, never the key.
 */
public final class WorkflowSignalAmbiguousException extends WorkflowConflictException {

    private final String contractName;
    private final String signalName;

    /**
     * Creates the exception for a signal that matched more than one open wait.
     *
     * @param contractName the logical name of the contract the signal was addressed to
     * @param signalName the name of the signal
     */
    public WorkflowSignalAmbiguousException(String contractName, String signalName) {
        super("Signal '" + signalName + "' of contract '" + contractName + "' matched more than one open wait");
        this.contractName = contractName;
        this.signalName = signalName;
    }

    /**
     * The logical name of the contract the signal was addressed to.
     *
     * @return the contract name
     */
    public String contractName() {
        return contractName;
    }

    /**
     * The name of the signal.
     *
     * @return the signal name
     */
    public String signalName() {
        return signalName;
    }
}
