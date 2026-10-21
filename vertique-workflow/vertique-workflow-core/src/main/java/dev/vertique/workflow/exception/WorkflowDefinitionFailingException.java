// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.registry.store.DefinitionFailure;
import dev.vertique.workflow.registry.store.DefinitionFailure.Rule;
import dev.vertique.workflow.registry.store.DefinitionKey;
import java.util.Objects;

/**
 * Thrown when a new start resolves to a definition version of a scope that is marked failing: newly deployed code
 * conflicts with the stored version, and the version stays refused until an operator repairs it.
 *
 * <p>Instances already running on the version are not affected, and a replay of an earlier start returns its
 * instance. A retry cannot succeed while the mark stands, so this is a configuration failure and not a retryable
 * unavailability.
 *
 * <p>The message names the scope key, the definition id, the version and the rule only; it never includes document
 * content, state values or class names.
 */
public class WorkflowDefinitionFailingException extends WorkflowConfigurationException {

    private final transient DefinitionKey definitionKey;
    private final Rule rule;

    /**
     * Creates a new {@code WorkflowDefinitionFailingException}.
     *
     * @param definitionKey the failing definition version the start resolved to
     * @param failure the failure the version is marked with
     */
    public WorkflowDefinitionFailingException(DefinitionKey definitionKey, DefinitionFailure failure) {
        super(message(
                Objects.requireNonNull(definitionKey, "definitionKey"), Objects.requireNonNull(failure, "failure")));
        this.definitionKey = definitionKey;
        this.rule = failure.rule();
    }

    private static String message(DefinitionKey key, DefinitionFailure failure) {
        return "Definition '" + key.definitionId() + "' version " + key.definitionVersion() + " in scope '"
                + key.scopeKey() + "' is marked failing (" + failure.rule() + ") and does not accept new starts";
    }

    /**
     * Returns the scope that owns the failing version.
     *
     * @return the scope key; never {@code null}
     */
    public String scopeKey() {
        return definitionKey.scopeKey();
    }

    /**
     * Returns the failing definition version.
     *
     * @return the definition key; never {@code null}
     */
    public DefinitionKey definitionKey() {
        return definitionKey;
    }

    /**
     * Returns the rule the version breaks.
     *
     * @return the rule; never {@code null}
     */
    public Rule rule() {
        return rule;
    }
}
