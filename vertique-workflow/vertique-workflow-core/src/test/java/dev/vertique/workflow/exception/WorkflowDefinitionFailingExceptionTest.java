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
}
