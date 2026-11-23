// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.types.EchoedNames;

/**
 * Thrown when a correlated signal that carries a provenance cannot be attributed to a named actor: no identity is
 * bound, the caller is recorded as anonymous, or the actor mapper refused the identity.
 *
 * <p>The delivery fails in the caller's transaction and writes nothing: no command, no deduplication row, no
 * open-signal count and no history entry. A delivery without provenance is not subject to this rule: it is recorded with
 * no actor when no identity is bound, and with the anonymous actor when the caller is not authenticated. The message names the contract and the signal, each cut at a bounded length and cleaned of
 * control characters, never the key. The accessors keep the full names.
 */
public final class WorkflowSignalActorException extends WorkflowException {

    private final String contractName;
    private final String signalName;

    /**
     * Creates the exception for a signal that carries a provenance and has no usable actor.
     *
     * @param contractName the logical name of the contract the signal was addressed to
     * @param signalName the name of the signal
     */
    public WorkflowSignalActorException(String contractName, String signalName) {
        super("Signal '" + EchoedNames.bounded(signalName) + "' of contract '" + EchoedNames.bounded(contractName)
                + "' carries a provenance and cannot be recorded without an actor");
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
