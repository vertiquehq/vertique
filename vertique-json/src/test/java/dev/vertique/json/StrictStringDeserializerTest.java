// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the opt-in {@link StrictStringDeserializer}.
 *
 * <p>Registers the deserializer on a throwaway {@link ObjectMapper} and verifies:
 * <ul>
 *   <li>A JSON string token is accepted and returns the string value.</li>
 *   <li>A JSON number scalar is rejected with {@link MismatchedInputException}
 *       (no silent coercion).</li>
 *   <li>A JSON boolean scalar is rejected with {@link MismatchedInputException}.</li>
 *   <li>A JSON {@code null} is allowed and yields {@code null} (standard absent-value semantics;
 *       only non-{@code null}, non-string tokens are rejected).</li>
 * </ul>
 */
class StrictStringDeserializerTest {

    /** Wrapper record used to exercise the registered deserializer. */
    record StringWrapper(String value) {}

    private ObjectMapper mapper;

    /**
     * Builds a fresh {@link ObjectMapper} with {@link StrictStringDeserializer} registered
     * for {@link String}.
     */
    @BeforeEach
    void setUp() {
        SimpleModule module = new SimpleModule("StrictStringModule");
        module.addDeserializer(String.class, new StrictStringDeserializer());
        mapper = new ObjectMapper().registerModule(module);
    }

    // --- Acceptance tests ---

    @Nested
    @DisplayName("Accepted input")
    class Accepted {

        @Test
        @DisplayName("JSON string \"abc\" is accepted and returned as-is")
        void jsonString_accepted() throws Exception {
            StringWrapper result = mapper.readValue("{\"value\":\"abc\"}", StringWrapper.class);

            assertEquals("abc", result.value(), "A JSON string must be accepted and returned as the string value");
        }
    }

    // --- Rejection tests ---

    @Nested
    @DisplayName("Rejected input (scalar coercion disabled)")
    class Rejected {

        @Test
        @DisplayName("JSON integer 123 rejected with MismatchedInputException (no coercion to string)")
        void jsonInteger_rejected_withMismatchedInputException() {
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue("{\"value\":123}", StringWrapper.class),
                    "JSON integer must be rejected with MismatchedInputException");
        }

        @Test
        @DisplayName("JSON boolean true rejected with MismatchedInputException (no coercion to string)")
        void jsonBoolean_rejected_withMismatchedInputException() {
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue("{\"value\":true}", StringWrapper.class),
                    "JSON boolean must be rejected with MismatchedInputException");
        }
    }

    // --- Null handling ---

    @Nested
    @DisplayName("Null handling")
    class NullHandling {

        @Test
        @DisplayName("JSON null deserializes to a null String (allowed; no MismatchedInputException)")
        void jsonNull_yieldsNull() throws Exception {
            // A JSON null is routed through Jackson's null-value provider, never reaching
            // the strict deserializer's deserialize() — so it is allowed and yields null.
            StringWrapper result = mapper.readValue("{\"value\":null}", StringWrapper.class);

            assertNull(result.value(), "A JSON null must deserialize to a null String, not throw");
        }
    }
}
