// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Checks that {@link DefinitionKey} refuses scope keys that hide format characters or padding. */
class DefinitionKeyScopeRulesTest {

    private static String codePoint(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /** Scope keys that a person cannot tell from another key or from nothing. */
    static Stream<Arguments> deceptiveScopeKeys() {
        return Stream.of(
                Arguments.of("a zero-width space inside", "ac" + codePoint(0x200B) + "me"),
                Arguments.of("a right-to-left override", "ac" + codePoint(0x202E) + "me"),
                Arguments.of("a left-to-right mark at the end", "acme" + codePoint(0x200E)),
                Arguments.of("a byte order mark at the start", codePoint(0xFEFF) + "acme"),
                Arguments.of("only a zero-width space", codePoint(0x200B)),
                Arguments.of("spaces around only format characters", " " + codePoint(0x200B) + codePoint(0x2060) + " "),
                Arguments.of("a leading space", " acme"),
                Arguments.of("a trailing space", "acme "),
                Arguments.of("a trailing ideographic space", "acme" + codePoint(0x3000)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("deceptiveScopeKeys")
    @DisplayName("rejects a scope key with a format character or padding, naming the field")
    void rejectsDeceptiveScopeKeys(String caseName, String scopeKey) {
        // When a key is built with the deceptive scope key
        // Then it is refused for the scope key field
        assertThatThrownBy(() -> new DefinitionKey(scopeKey, "purchase-approval", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scopeKey");
    }

    @Test
    @DisplayName("never repeats a rejected scope key")
    void doesNotEchoTheRejectedKey() {
        // When a key is built with a scope key that hides a zero-width space behind a marker
        String hostile = "leak-marker" + codePoint(0x200B);

        // Then it is refused without repeating the key
        assertThatThrownBy(() -> new DefinitionKey(hostile, "purchase-approval", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("leak-marker");
    }

    @Test
    @DisplayName("still accepts ordinary scope keys, an inner space and supplementary characters")
    void acceptsValidScopeKeys() {
        // When keys are built with ordinary scope keys
        // Then they are accepted and carry the scope key unchanged
        String supplementary = codePoint(0x1F600).repeat(255);
        assertThatCode(() -> new DefinitionKey("default", "purchase-approval", 1))
                .doesNotThrowAnyException();
        assertThat(new DefinitionKey("acme eu", "purchase-approval", 1).scopeKey())
                .isEqualTo("acme eu");
        assertThat(new DefinitionKey(supplementary, "purchase-approval", 1).scopeKey())
                .isEqualTo(supplementary);
    }
}
