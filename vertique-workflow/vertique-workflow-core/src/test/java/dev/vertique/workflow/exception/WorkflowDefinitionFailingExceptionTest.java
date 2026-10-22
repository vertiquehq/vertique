// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.registry.store.DefinitionFailure;
import dev.vertique.workflow.registry.store.DefinitionFailure.Rule;
import dev.vertique.workflow.registry.store.DefinitionKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link WorkflowDefinitionFailingException}: the refusal of a start on a failing scope row names the scope,
 * the definition key and the rule, and is a configuration failure rather than a retryable one.
 */
class WorkflowDefinitionFailingExceptionTest {

    @Test
    @DisplayName("the refusal names the scope, the definition key and the rule, and is not retryable")
    void messageNamesScopeKeyAndRuleOnly() {
        // Given: the key (acme, purchase-approval, 3) and a state type failure
        DefinitionKey key = new DefinitionKey("acme", "purchase-approval", 3L);
        DefinitionFailure failure =
                new DefinitionFailure(Rule.STATE_TYPE_NOT_EXTENDED, "state type does not extend the default scope's");

        // When: the exception is constructed
        WorkflowDefinitionFailingException refusal = new WorkflowDefinitionFailingException(key, failure);

        // Then: the accessors return the inputs
        assertThat(refusal.scopeKey()).isEqualTo("acme");
        assertThat(refusal.definitionKey()).isEqualTo(key);
        assertThat(refusal.rule()).isEqualTo(Rule.STATE_TYPE_NOT_EXTENDED);

        // and: the message names the scope, the definition id, the version and the rule
        assertThat(refusal.getMessage())
                .contains("acme")
                .contains("purchase-approval")
                .contains("3")
                .contains("STATE_TYPE_NOT_EXTENDED");

        // and: it is a configuration failure that a retry cannot cure
        assertThat(refusal).isInstanceOf(WorkflowConfigurationException.class);
        assertThat(refusal).isNotInstanceOf(WorkflowUnavailableException.class);
    }

    @Test
    @DisplayName("the message repeats a long scope key and id only in bounded form")
    void messageBoundsTheEchoedScopeKeyAndId() {
        // Given: a key whose scope key and definition id are at the length limit
        String scope = "s".repeat(255);
        String id = "d".repeat(255);
        DefinitionKey key = new DefinitionKey(scope, id, 7L);

        // When: the exception is constructed
        WorkflowDefinitionFailingException refusal = new WorkflowDefinitionFailingException(
                key, new DefinitionFailure(Rule.STATE_TYPE_NOT_EXTENDED, "state type does not extend"));

        // Then: the message repeats neither whole and still names the rule
        assertThat(refusal.getMessage())
                .doesNotContain(scope, id, "s".repeat(100), "d".repeat(100))
                .contains("STATE_TYPE_NOT_EXTENDED");
        assertThat(refusal.getMessage().length()).isLessThan(300);

        // and: the typed accessors keep the full identifiers
        assertThat(refusal.scopeKey()).isEqualTo(scope);
        assertThat(refusal.definitionKey()).isEqualTo(key);
    }
}
