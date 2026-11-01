// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.types.EchoedNames;

/**
 * Thrown when a signal addressed by contract, signal name and key finds no open wait record: no instance of the
 * contract waits on the key, the waiting instance has ended, the wait closed or the definition the instance migrated to
 * does not declare the signal before the signal was recorded.
 *
 * <p>The delivery fails in the caller's transaction and writes nothing: no command, no deduplication row and no history
 * entry. A wait may open later, so a caller may deliver the same signal again. The message names the contract and the
 * signal, each cut at a bounded length and cleaned of control characters, never the key. The accessors keep the full
 * names.
 */
public final class WorkflowSignalNoMatchException extends WorkflowException {

    private final String contractName;
    private final String signalName;

    /**
     * Creates the exception for a signal that matched no open wait.
     *
     * @param contractName the logical name of the contract the signal was addressed to
     * @param signalName the name of the signal
     */
    public WorkflowSignalNoMatchException(String contractName, String signalName) {
        super("Signal '" + EchoedNames.bounded(signalName) + "' of contract '" + EchoedNames.bounded(contractName)
                + "' matched no open wait");
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
