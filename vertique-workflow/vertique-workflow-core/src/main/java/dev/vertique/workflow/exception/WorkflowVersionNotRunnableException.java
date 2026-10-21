// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.types.EchoedNames;
import java.util.Objects;

/**
 * Thrown when a node is asked to run a definition version that its deployed code does not hold: a start that
 * resolves to the version, or a signal, task completion, query, retry, migration or timer on an instance pinned to
 * it.
 *
 * <p>The version can be known to the cluster and runnable on another node, so the refusal is a property of this
 * node and not of the instance. The calling transaction is rolled back and nothing is written, and no node marks an
 * instance failed because it cannot run its pin. A timer fired on such a node is queued again for a node that has
 * the code. Because it extends {@link WorkflowVersionPinUnavailableException}, existing handlers keep working.
 *
 * <p>The message names the scope key, the definition id and the version only.
 */
public class WorkflowVersionNotRunnableException extends WorkflowVersionPinUnavailableException {

    private final transient DefinitionKey definitionKey;

    /**
     * Creates a new {@code WorkflowVersionNotRunnableException}.
     *
     * @param definitionKey the definition version this node holds no code for
     */
    public WorkflowVersionNotRunnableException(DefinitionKey definitionKey) {
        this(definitionKey, null);
    }

    /**
     * Creates a new {@code WorkflowVersionNotRunnableException} for an instance pinned to the version.
     *
     * @param definitionKey the definition version this node holds no code for
     * @param instanceId the id of the instance pinned to the version, or {@code null} when the refusal is not
     *     about an instance; it is available from {@link #instanceId()} and is not part of the message
     */
    public WorkflowVersionNotRunnableException(DefinitionKey definitionKey, WorkflowInstanceId instanceId) {
        super(
                Objects.requireNonNull(definitionKey, "definitionKey").scopeKey(),
                definitionKey.definitionId(),
                definitionKey.definitionVersion(),
                instanceId);
        this.definitionKey = definitionKey;
    }

    /**
     * Returns the definition version this node cannot run.
     *
     * @return the definition key; never {@code null}
     */
    public DefinitionKey definitionKey() {
        return definitionKey;
    }

    @Override
    public String getMessage() {
        return "Workflow definition '" + EchoedNames.bounded(definitionId()) + "' version " + version()
                + " in scope '" + EchoedNames.bounded(scopeKey())
                + "' is not runnable on this node: its code is not deployed here";
    }
}
