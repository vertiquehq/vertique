// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.registry.store.DefinitionFailure;
import dev.vertique.workflow.registry.store.DefinitionFailure.Rule;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.types.EchoedNames;
import java.util.Objects;

/**
 * The refusal of a start that resolves to a definition version of a scope that is evaluated as failing: newly
 * deployed code conflicts with the stored version, and the version stays refused until an operator repairs it.
 *
 * <p>The start call itself never throws this exception. A start of such a version is recorded, and the executor that
 * runs the recorded start refuses it with this exception: the command and the instance end {@code FAILED} with the
 * reason {@code DEFINITION_FAILING}, which names the rule only, and a warning log line names this exception class and
 * the rule. The caller finds the instance {@code FAILED}.
 *
 * <p>Instances already running on the version are not affected, and a replay of an earlier start returns its
 * instance. A retry cannot succeed while the version is failing, so this is a configuration failure and not a
 * retryable unavailability.
 *
 * <p>The message names the scope key, the definition id, the version and the rule only; it never includes document
 * content, state values or class names. The scope key and the id are repeated in bounded form.
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
        return "Definition '" + EchoedNames.bounded(key.definitionId()) + "' version " + key.definitionVersion()
                + " in scope '" + EchoedNames.bounded(key.scopeKey()) + "' is marked failing (" + failure.rule()
                + ") and does not accept new starts";
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
