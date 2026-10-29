// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.registry.store.DefinitionKey;
import java.util.Objects;

/**
 * Thrown when the registry holds no plan for a definition key and has none to load: a document key whose row does not
 * exist or is not available, a code key whose class this node does not deploy, or a document key that is not resident
 * in the plan cache when it is resolved.
 *
 * <p>It extends {@link WorkflowVersionPinUnavailableException}, so handlers written for the failure of
 * {@code resolve} keep working. A missing row is never cached: the next load reads the row again.
 *
 * <p>The message names the scope key, the definition id and the version only.
 */
public class WorkflowDefinitionNotFoundException extends WorkflowVersionPinUnavailableException {

    private final transient DefinitionKey definitionKey;

    /**
     * Creates a new {@code WorkflowDefinitionNotFoundException}.
     *
     * @param definitionKey the definition version for which no plan is held and none can be loaded
     */
    public WorkflowDefinitionNotFoundException(DefinitionKey definitionKey) {
        super(
                Objects.requireNonNull(definitionKey, "definitionKey").scopeKey(),
                definitionKey.definitionId(),
                definitionKey.definitionVersion(),
                null);
        this.definitionKey = definitionKey;
    }

    /**
     * Returns the definition version for which no plan is held.
     *
     * @return the definition key; never {@code null}
     */
    public DefinitionKey definitionKey() {
        return definitionKey;
    }
}
