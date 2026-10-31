// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link WorkflowSignalNoMatchException} and {@link WorkflowSignalAmbiguousException}: each names the contract
 * and the signal through two accessors and its message, the first is a {@link WorkflowException} and the second a
 * {@link WorkflowConflictException}, and both are final.
 */
class SignalMatchExceptionsTest {

    @Test
    @DisplayName("no-match and ambiguity name the contract and the signal only")
    void noMatchAndAmbiguousNameContractAndSignalOnly() {
        // Given a no-match and an ambiguity built for contract orders and signal payment-captured
        WorkflowSignalNoMatchException noMatch = new WorkflowSignalNoMatchException("orders", "payment-captured");
        WorkflowSignalAmbiguousException ambiguous = new WorkflowSignalAmbiguousException("orders", "payment-captured");

        // When their accessors, messages and hierarchy are read
        // Then each accessor returns the two names it was built with
        assertThat(noMatch.contractName()).isEqualTo("orders");
        assertThat(noMatch.signalName()).isEqualTo("payment-captured");
        assertThat(ambiguous.contractName()).isEqualTo("orders");
        assertThat(ambiguous.signalName()).isEqualTo("payment-captured");

        // And each message names both
        assertThat(noMatch.getMessage()).contains("orders", "payment-captured");
        assertThat(ambiguous.getMessage()).contains("orders", "payment-captured");

        // And no-match is a workflow exception, ambiguity a workflow conflict, and both are final
        assertThat(noMatch).isInstanceOf(WorkflowException.class);
        assertThat(ambiguous).isInstanceOf(WorkflowConflictException.class);
        assertThat(Modifier.isFinal(WorkflowSignalNoMatchException.class.getModifiers()))
                .isTrue();
        assertThat(Modifier.isFinal(WorkflowSignalAmbiguousException.class.getModifiers()))
                .isTrue();
    }
}
