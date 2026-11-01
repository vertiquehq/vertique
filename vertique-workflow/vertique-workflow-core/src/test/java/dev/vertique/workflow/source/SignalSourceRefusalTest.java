// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link SignalSourceRefusal}, the marker a dispatchAs function fails with to refuse a
 * message outright, carries its error type and nothing else: no cause, no stack trace and no message
 * beyond the type, so nothing from the message can ride along with it.
 */
class SignalSourceRefusalTest {

    @Test
    @DisplayName("a refusal carries the error type and nothing else")
    void refusalCarriesTheErrorTypeAndNothingElse() {
        // Given a refusal built with an error type
        SignalSourceRefusal refusal = new SignalSourceRefusal("TokenRejected");

        // When the test reads it
        // Then it is an unchecked, final exception that reports the error type
        assertThat(Modifier.isFinal(SignalSourceRefusal.class.getModifiers())).isTrue();
        assertThat(refusal).isInstanceOf(RuntimeException.class);
        assertThat(refusal.errorType()).isEqualTo("TokenRejected");

        // and it has no cause
        assertThat(refusal.getCause()).isNull();

        // and its message is the error type or null, and holds nothing else
        assertThat(refusal.getMessage()).isIn("TokenRejected", null);

        // and it has no stack trace
        assertThat(refusal.getStackTrace()).isEmpty();

        // and a null or blank error type is refused at construction
        assertThatThrownBy(() -> new SignalSourceRefusal(null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new SignalSourceRefusal("")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new SignalSourceRefusal("   ")).isInstanceOf(RuntimeException.class);
    }
}
