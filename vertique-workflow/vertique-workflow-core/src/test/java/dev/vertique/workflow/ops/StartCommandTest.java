// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Checks that {@link StartCommand} refuses start and target scope keys that no scope can be named by. */
class StartCommandTest {

    private static final String SUPPLEMENTARY = new String(Character.toChars(0x1F600));

    /** Scope keys that break the scope key rules, each with a case name that says which rule. */
    static Stream<Arguments> invalidScopeKeys() {
        return Stream.of(
                Arguments.of("empty", ""),
                Arguments.of("blank", "   "),
                Arguments.of("256 code points", "a".repeat(256)),
                Arguments.of("256 supplementary code points", SUPPLEMENTARY.repeat(256)),
                Arguments.of("a tab", "ac" + (char) 9 + "me"),
                Arguments.of("a line feed", "acme" + (char) 10),
                Arguments.of("a delete character", (char) 127 + "acme"),
                Arguments.of("a C1 control character", "ac" + (char) 0x85 + "me"),
                Arguments.of("a NUL character", "ac" + (char) 0 + "me"),
                Arguments.of("an unpaired high surrogate", "ac" + (char) 0xD800 + "me"),
                Arguments.of("an unpaired low surrogate", "ac" + (char) 0xDC00 + "me"));
    }

    /** Scope keys that obey the rules, including the longest one. */
    static Stream<Arguments> validScopeKeys() {
        return Stream.of(
                Arguments.of("a plain key", "acme"),
                Arguments.of("the default scope", "default"),
                Arguments.of("255 code points", "a".repeat(255)),
                Arguments.of("255 supplementary code points", SUPPLEMENTARY.repeat(255)),
                Arguments.of("an inner space", "acme eu"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidScopeKeys")
    @DisplayName("rejects an invalid start scope")
    void rejectsInvalidStartScope(String caseName, String startScope) {
        // When a command is built with an invalid start scope, then it is refused and names the field
        assertThatThrownBy(() -> command(startScope, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startScope");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidScopeKeys")
    @DisplayName("rejects an invalid requested target scope")
    void rejectsInvalidRequestedTargetScope(String caseName, String targetScope) {
        // When a command is built with an invalid target scope, then it is refused and names the field
        assertThatThrownBy(() -> command(null, targetScope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestedTargetScope");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validScopeKeys")
    @DisplayName("accepts a valid start scope and a valid requested target scope")
    void acceptsValidScopes(String caseName, String scope) {
        // When a command is built with a valid scope key in either position
        // Then it is accepted and carries the key unchanged
        assertThat(command(scope, null).startScope()).isEqualTo(scope);
        assertThat(command(null, scope).requestedTargetScope()).isEqualTo(scope);
        assertThat(command(scope, scope).startScope()).isEqualTo(scope);
    }

    @Test
    @DisplayName("accepts a null start scope and a null requested target scope")
    void acceptsNullScopes() {
        // When a command is built with no scope at all
        // Then it is accepted and both scopes stay null for the engine to resolve
        assertThatCode(() -> command(null, null)).doesNotThrowAnyException();
        StartCommand command = command(null, null);
        assertThat(command.startScope()).isNull();
        assertThat(command.requestedTargetScope()).isNull();
    }

    private static StartCommand command(String startScope, String requestedTargetScope) {
        return new StartCommand(
                "purchase-approval",
                "purchase",
                new Object(),
                "key-1",
                null,
                null,
                null,
                startScope,
                requestedTargetScope);
    }
}
