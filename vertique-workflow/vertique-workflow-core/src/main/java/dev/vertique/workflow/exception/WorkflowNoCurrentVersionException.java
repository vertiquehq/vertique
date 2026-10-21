// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.types.EchoedNames;

/**
 * Thrown when a start without a version finds no current version: the start scope has no activation record, the
 * default scope has no record either, and the default scope holds no version that can be started.
 *
 * <p>It is a {@link WorkflowDefinitionMissingException}, so callers that handle a missing definition handle this
 * too. It carries no version, because none is current.
 */
public class WorkflowNoCurrentVersionException extends WorkflowDefinitionMissingException {

    private final String scopeKey;
    private final String definitionId;

    /**
     * Creates the exception for a start scope and definition id that have no current version.
     *
     * @param scopeKey the scope the start ran in
     * @param definitionId the definition id that has no current version
     */
    public WorkflowNoCurrentVersionException(String scopeKey, String definitionId) {
        super(definitionId, scopeKey);
        this.scopeKey = scopeKey;
        this.definitionId = definitionId;
    }

    /**
     * Returns the scope the start ran in.
     *
     * @return the start scope key
     */
    public String scopeKey() {
        return scopeKey;
    }

    /**
     * Returns the definition id that has no current version.
     *
     * @return the definition id
     */
    public String definitionId() {
        return definitionId;
    }

    @Override
    public String getMessage() {
        return "Workflow definition '" + EchoedNames.bounded(definitionId) + "' has no current version for scope '"
                + EchoedNames.bounded(scopeKey) + "'.";
    }
}
