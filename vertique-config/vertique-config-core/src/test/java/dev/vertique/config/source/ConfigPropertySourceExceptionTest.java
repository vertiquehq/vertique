// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.source;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.exception.ConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigPropertySourceException}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Lookup-failure message composition: contains source name, key, and detail.</li>
 *   <li>Create-time failure message composition: contains source name and detail; key is null.</li>
 *   <li>Messages never contain resolved values that were never passed to the constructor.</li>
 *   <li>Fluent accessor correctness: {@link ConfigPropertySourceException#sourceName()} and
 *       {@link ConfigPropertySourceException#key()} return the values passed to the
 *       constructor.</li>
 *   <li>Inheritance: extends {@link ConfigurationException}.</li>
 *   <li>Cause propagation: all constructor overloads behave correctly.</li>
 * </ul>
 */
class ConfigPropertySourceExceptionTest {

    // --- Message composition ---

    @Nested
    @DisplayName("Message composition")
    class MessageCompositionTests {

        @Test
        @DisplayName("Message contains source name")
        void shouldContainSourceName() {
            var ex = new ConfigPropertySourceException("vault-primary", "db.password", "connection refused");
            assertTrue(ex.getMessage().contains("vault-primary"), "Message must contain the source name");
        }

        @Test
        @DisplayName("Message contains the key")
        void shouldContainKey() {
            var ex = new ConfigPropertySourceException("vault-primary", "db.password", "connection refused");
            assertTrue(ex.getMessage().contains("db.password"), "Message must contain the lookup key");
        }

        @Test
        @DisplayName("Message contains the detail string")
        void shouldContainDetail() {
            var ex = new ConfigPropertySourceException("vault-primary", "db.password", "authentication failed (403)");
            assertTrue(
                    ex.getMessage().contains("authentication failed (403)"), "Message must contain the detail string");
        }

        @Test
        @DisplayName("Message does not contain values that were never provided")
        void shouldNotContainUninvolvedValues() {
            // The exception is only given name, key, and detail — never a resolved secret.
            // Assert that the message cannot contain a value the test never passed in.
            String resolvedSecret = "s3cr3t-p@ssword";
            var ex = new ConfigPropertySourceException("vault-primary", "db.password", "connection refused");
            assertFalse(
                    ex.getMessage().contains(resolvedSecret),
                    "Message must not contain values that were never passed to the constructor");
        }
    }

    // --- Fluent accessors ---

    @Nested
    @DisplayName("Fluent accessors")
    class FluentAccessorTests {

        @Test
        @DisplayName("sourceName() returns the constructor-supplied source name")
        void shouldReturnSourceName() {
            var ex = new ConfigPropertySourceException("aws-secrets", "api.key", "timeout");
            assertEquals("aws-secrets", ex.sourceName(), "sourceName() must return the value supplied at construction");
        }

        @Test
        @DisplayName("key() returns the constructor-supplied key")
        void shouldReturnKey() {
            var ex = new ConfigPropertySourceException("aws-secrets", "api.key", "timeout");
            assertEquals("api.key", ex.key(), "key() must return the value supplied at construction");
        }
    }

    // --- Inheritance ---

    @Nested
    @DisplayName("Inheritance")
    class InheritanceTests {

        @Test
        @DisplayName("Extends ConfigurationException")
        void shouldExtendConfigurationException() {
            var ex = new ConfigPropertySourceException("src", "k", "detail");
            assertInstanceOf(
                    ConfigurationException.class,
                    ex,
                    "ConfigPropertySourceException must extend ConfigurationException");
        }
    }

    // --- Create-time / schema failure form ---

    @Nested
    @DisplayName("Create-time failure form (two-arg constructors)")
    class CreateTimeFailureTests {

        @Test
        @DisplayName("Two-arg constructor message contains source name")
        void shouldContainSourceNameInCreateTimeMessage() {
            var ex = new ConfigPropertySourceException("vault-primary", "required field 'address' is missing or blank");
            assertTrue(ex.getMessage().contains("vault-primary"), "Message must contain the source name");
        }

        @Test
        @DisplayName("Two-arg constructor message contains detail")
        void shouldContainDetailInCreateTimeMessage() {
            var ex = new ConfigPropertySourceException("vault-primary", "required field 'address' is missing or blank");
            assertTrue(
                    ex.getMessage().contains("required field 'address' is missing or blank"),
                    "Message must contain the detail string");
        }

        @Test
        @DisplayName("Two-arg constructor key() returns null")
        void shouldReturnNullKeyForCreateTimeFailure() {
            var ex = new ConfigPropertySourceException("vault-primary", "authentication failed");
            assertNull(ex.key(), "key() must be null for create-time failures");
        }

        @Test
        @DisplayName("Two-arg constructor sourceName() returns the source name")
        void shouldReturnSourceNameForCreateTimeFailure() {
            var ex = new ConfigPropertySourceException("vault-primary", "authentication failed");
            assertEquals("vault-primary", ex.sourceName());
        }

        @Test
        @DisplayName("Three-arg create-time constructor preserves the cause")
        void shouldPreserveCauseInCreateTimeThreeArg() {
            var root = new RuntimeException("underlying I/O error");
            var ex = new ConfigPropertySourceException("vault-primary", "authentication failed", root);
            assertSame(root, ex.getCause(), "getCause() must return the supplied cause");
            assertNull(ex.key(), "key() must be null for create-time failures");
        }

        @Test
        @DisplayName("Three-arg create-time constructor with null cause leaves getCause() null")
        void shouldAcceptNullCauseInCreateTimeThreeArg() {
            var ex = new ConfigPropertySourceException("vault-primary", "authentication failed", (Throwable) null);
            assertNull(ex.getCause(), "getCause() must be null when null is passed explicitly as cause");
        }

        @Test
        @DisplayName("Create-time message format is 'Property source <name> failed: <detail>'")
        void shouldUseCreateTimeMessageFormat() {
            var ex = new ConfigPropertySourceException("my-source", "required field 'address' is missing or blank");
            assertTrue(
                    ex.getMessage().startsWith("Property source 'my-source' failed:"),
                    "Create-time message must use the correct format prefix");
        }

        @Test
        @DisplayName("Create-time message does not contain 'look up key'")
        void shouldNotContainLookUpKeyInCreateTimeMessage() {
            var ex = new ConfigPropertySourceException("vault-primary", "schema validation failure");
            assertFalse(
                    ex.getMessage().contains("look up key"),
                    "Create-time message must NOT use the lookup-failure format");
        }
    }

    // --- Cause propagation ---

    @Nested
    @DisplayName("Cause propagation")
    class CausePropagationTests {

        @Test
        @DisplayName("Four-arg constructor preserves the cause")
        void shouldPreserveCause() {
            var root = new RuntimeException("underlying I/O error");
            var ex = new ConfigPropertySourceException("vault-primary", "db.password", "I/O error", root);
            assertSame(root, ex.getCause(), "getCause() must return the supplied cause");
        }

        @Test
        @DisplayName("Three-arg constructor leaves cause null")
        void shouldLeaveNullCauseWhenNoCauseSupplied() {
            var ex = new ConfigPropertySourceException("vault-primary", "db.password", "not found");
            assertNull(ex.getCause(), "getCause() must be null when no cause is supplied");
        }

        @Test
        @DisplayName("Four-arg constructor with null cause leaves getCause() null")
        void shouldAcceptNullCauseExplicitly() {
            var ex = new ConfigPropertySourceException("vault-primary", "db.password", "not found", null);
            assertNull(ex.getCause(), "getCause() must be null when null is passed explicitly as cause");
        }
    }
}
