// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.types;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.core.scope.ScopeKey;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins that {@link ScopeKeyRules#require} and the core {@link ScopeKey} accept and reject the same keys, because a
 * key the workflow has validated is validated again by the outbox with {@link ScopeKey}.
 */
@DisplayName("ScopeKeyRules parity with ScopeKey")
class ScopeKeyRulesParityTest {

    private static final String SUPPLEMENTARY = "😀";

    static Stream<Arguments> keys() {
        return Stream.of(
                Arguments.of("plain", "acme", true),
                Arguments.of("mixed case", "AcMe-Tenant_1", true),
                Arguments.of("supplementary characters", "ac" + SUPPLEMENTARY + "me", true),
                Arguments.of("internal whitespace", "acme corp north", true),
                Arguments.of("255 code points", "x".repeat(255), true),
                Arguments.of("255 supplementary code points", SUPPLEMENTARY.repeat(255), true),
                Arguments.of("empty", "", false),
                Arguments.of("blank", "   ", false),
                Arguments.of("256 code points", "x".repeat(256), false),
                Arguments.of("256 supplementary code points", SUPPLEMENTARY.repeat(256), false),
                Arguments.of("control character", "bad\u0001key", false),
                Arguments.of("format character", "bad\u200Bkey", false),
                Arguments.of("unpaired high surrogate", "bad\uD800key", false),
                Arguments.of("unpaired low surrogate", "bad\uDC00key", false),
                Arguments.of("leading whitespace", " acme", false),
                Arguments.of("trailing whitespace", "acme ", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keys")
    @DisplayName("both rules accept or both reject the key")
    void bothRulesAgree(String label, String key, boolean valid) {
        // Given: a key
        // When: each rule checks it
        boolean acceptedByRules = accepts(() -> ScopeKeyRules.require(key, "scopeKey"));
        boolean acceptedByCore = accepts(() -> new ScopeKey(key));

        // Then: the rules agree with each other and with the expected verdict
        assertEquals(acceptedByCore, acceptedByRules, "ScopeKeyRules and ScopeKey must give the same verdict");
        assertEquals(valid, acceptedByCore, "the verdict must be " + (valid ? "accept" : "reject"));
    }

    private static boolean accepts(Runnable check) {
        try {
            check.run();
            return true;
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }
}
