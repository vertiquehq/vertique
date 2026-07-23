// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExceptionChainAssertions}.
 */
class ExceptionChainAssertionsTest {

    // --- containsAnywhere ---

    @Nested
    @DisplayName("containsAnywhere")
    class ContainsAnywhere {

        @Test
        @DisplayName("returns false for null exception")
        void nullException() {
            assertFalse(ExceptionChainAssertions.containsAnywhere(null, "needle"));
        }

        @Test
        @DisplayName("returns true when root message contains needle")
        void matchInRoot() {
            var ex = new RuntimeException("secret value here");
            assertTrue(ExceptionChainAssertions.containsAnywhere(ex, "secret"));
        }

        @Test
        @DisplayName("returns false when no message in chain contains needle")
        void noMatch() {
            var cause = new RuntimeException("unrelated cause");
            var ex = new RuntimeException("unrelated root", cause);
            assertFalse(ExceptionChainAssertions.containsAnywhere(ex, "secret"));
        }

        @Test
        @DisplayName("returns true when match is only in a nested cause")
        void matchInNestedCause() {
            var deepCause = new RuntimeException("deep secret leaked");
            var cause = new RuntimeException("intermediate", deepCause);
            var ex = new RuntimeException("outer", cause);
            assertTrue(ExceptionChainAssertions.containsAnywhere(ex, "secret"));
        }

        @Test
        @DisplayName("does not throw when a cause has a null message")
        void nullMessageInChain() {
            var causeWithNull = new RuntimeException((String) null);
            var ex = new RuntimeException("root", causeWithNull);
            assertFalse(ExceptionChainAssertions.containsAnywhere(ex, "needle"));
        }
    }

    // --- exceptionChainText ---

    @Nested
    @DisplayName("exceptionChainText")
    class ExceptionChainText {

        @Test
        @DisplayName("returns empty string for null exception")
        void nullException() {
            assertEquals("", ExceptionChainAssertions.exceptionChainText(null));
        }

        @Test
        @DisplayName("collects all non-null messages joined by \"; \"")
        void collectsMessages() {
            var cause = new RuntimeException("cause message");
            var ex = new RuntimeException("root message", cause);
            String text = ExceptionChainAssertions.exceptionChainText(ex);
            assertTrue(text.contains("root message"), "should contain root message");
            assertTrue(text.contains("cause message"), "should contain cause message");
            assertTrue(text.contains("; "), "should join with '; '");
        }

        @Test
        @DisplayName("skips null messages without throwing")
        void skipsNullMessages() {
            var causeWithNull = new RuntimeException((String) null);
            var ex = new RuntimeException("only this", causeWithNull);
            assertEquals("only this; ", ExceptionChainAssertions.exceptionChainText(ex));
        }
    }
}
