// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that a refusal repeats the message of a failure it did not write as supplied. */
class DefinitionPublicationRefusalTest {

    private static final DefinitionKey KEY = new DefinitionKey(WorkflowScopes.DEFAULT, "ship", 1);

    @Test
    @DisplayName("a long message with a line break is repeated as supplied in the refusal and the cause")
    void longMessageIsRepeatedAsSupplied() {
        // Given a failure whose message is long and holds a line break
        String hostile = "validator said\nforged line " + "z".repeat(5000);
        IllegalStateException failure = new IllegalStateException(hostile);

        // When the refusal is made
        WorkflowDefinitionException refusal = DefinitionPublication.unbuildable(KEY, failure);

        // Then the refusal names the key and carries the whole message unchanged
        assertThat(refusal.getMessage()).contains("ship").endsWith("from its stored source: " + hostile);

        // And the cause keeps the failure unchanged
        assertThat(refusal.getCause()).isSameAs(failure);
        assertThat(failure.getMessage()).isEqualTo(hostile);
    }

    @Test
    @DisplayName("a failure without a message is refused without one")
    void failureWithoutAMessageIsRefused() {
        assertThat(DefinitionPublication.unbuildable(KEY, new IllegalStateException())
                        .getMessage())
                .endsWith("from its stored source: null");
    }
}
