// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that a refusal repeats the message of a failure it did not write only in bounded form. */
class DefinitionPublicationRefusalTest {

    private static final DefinitionKey KEY = new DefinitionKey(WorkflowScopes.DEFAULT, "ship", 1);

    @Test
    @DisplayName("a long message with a forged line is cut and cleaned in the refusal and kept whole in the cause")
    void longMessageWithAForgedLineIsCutAndCleaned() {
        // Given a failure whose message is long and holds a line break
        String hostile = "validator said\nforged line " + "z".repeat(5000);
        IllegalStateException failure = new IllegalStateException(hostile);

        // When the refusal is made
        WorkflowDefinitionException refusal = DefinitionPublication.unbuildable(KEY, failure);

        // Then the refusal names the key, carries the start of the message without the line break and is bounded
        assertThat(refusal.getMessage())
                .contains("ship", "validator said?forged line")
                .doesNotContain("\n", "z".repeat(600));
        assertThat(refusal.getMessage().length()).isLessThan(800);

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
