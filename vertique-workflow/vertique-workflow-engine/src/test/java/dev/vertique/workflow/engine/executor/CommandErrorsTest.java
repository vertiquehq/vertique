// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.engine.executor.CommandErrors.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Verifies that the stored reason of a failed command is a reason code and a bounded, framework-composed message. */
class CommandErrorsTest {

    @ParameterizedTest
    @EnumSource(Reason.class)
    @DisplayName("every reason code starts the stored value")
    void everyReasonCodeStartsTheStoredValue(Reason reason) {
        // Given a reason code and a message

        // When the value is formatted
        String value = CommandErrors.format(reason, "a message");

        // Then it is the code, a colon and the message
        assertThat(value).isEqualTo(reason.name() + ": a message");
    }

    @Test
    @DisplayName("the composed messages name only the kind, the counts and the status")
    void composedMessagesNameOnlyKindCountsAndStatus() {
        // Given the facts of five failures

        // When the values are composed
        String transientFailure = CommandErrors.transientFailure("START");
        String deterministic = CommandErrors.deterministicFailure("START");
        String stepLimit = CommandErrors.stepLimit("START", 50);
        String exhausted = CommandErrors.attemptsExhausted("START", 2, 2);
        String ended = CommandErrors.instanceEnded("SIGNAL", "COMPLETED");

        // Then each starts with its code and carries the counts it is given
        assertThat(transientFailure).startsWith("TRANSIENT_FAILURE: START command");
        assertThat(deterministic).startsWith("DETERMINISTIC_FAILURE: START command");
        assertThat(stepLimit).startsWith("STEP_LIMIT: START command").contains("50");
        assertThat(exhausted).startsWith("ATTEMPTS_EXHAUSTED: START command").contains("2 of 2");
        assertThat(ended).startsWith("INSTANCE_ENDED: SIGNAL command").contains("COMPLETED");
    }

    @Test
    @DisplayName("a kind or status that is not an identifier is reduced to one, so no foreign text reaches the value")
    void foreignTextInAKindOrStatusIsReducedToAnIdentifier() {
        // Given a kind and a status that carry text, a payload, quotes and a newline
        String kind = "start zz-secret {\"card\":\"4111\"}\nat Foo.bar(Foo.java:1)";

        // When the values are composed
        String value = CommandErrors.instanceEnded(kind, "completed'; DROP TABLE x; --");

        // Then only letters, digits, underscores and hyphens of each remain, upper-cased, within 40 characters
        assertThat(value)
                .doesNotContain("\n", "{", "\"", ";", "'", " zz", "(")
                .matches("INSTANCE_ENDED: [A-Za-z0-9_ -]+")
                .hasSizeLessThan(200);
    }

    @Test
    @DisplayName("control characters of a message become spaces")
    void controlCharactersBecomeSpaces() {
        // Given a message with a newline and a tab

        // When it is formatted
        String value = CommandErrors.format(Reason.DETERMINISTIC_FAILURE, "a\nb\tc");

        // Then none remains
        assertThat(value).isEqualTo("DETERMINISTIC_FAILURE: a b c");
    }

    @Test
    @DisplayName("a value longer than the column is cut to 4000 characters, the code first")
    void aLongValueIsCutToTheColumnSize() {
        // Given a message of 10,000 characters
        String message = "x".repeat(10_000);

        // When it is formatted
        String value = CommandErrors.format(Reason.STEP_LIMIT, message);

        // Then it holds exactly 4000 characters and still starts with the code
        assertThat(value).hasSize(CommandErrors.MAX_LENGTH).startsWith("STEP_LIMIT: xxx");
    }

    @Test
    @DisplayName("a missing kind is named unknown")
    void aMissingKindIsNamedUnknown() {
        assertThat(CommandErrors.transientFailure(null)).startsWith("TRANSIENT_FAILURE: UNKNOWN command");
        assertThat(CommandErrors.transientFailure("")).startsWith("TRANSIENT_FAILURE: UNKNOWN command");
    }
}
