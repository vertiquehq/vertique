// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link OutboxQueryConfig}: defaults, bounds and deserialization. */
@DisplayName("OutboxQueryConfig")
class OutboxQueryConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static Throwable rootOf(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    @Test
    @DisplayName("the limit is 16 MiB and the default is 1 MiB")
    void constants() throws Exception {
        assertEquals(16 * 1024 * 1024, OutboxQueryConfig.MAX_PAYLOAD_BYTES_LIMIT);
        assertEquals(1_048_576, OutboxQueryConfig.builder().build().maxPayloadBytes());
        assertEquals(1_048_576, mapper.readValue("{}", OutboxQueryConfig.class).maxPayloadBytes());
    }

    @Test
    @DisplayName("the builder accepts one byte and 16 MiB")
    void builderBounds() {
        assertEquals(1, OutboxQueryConfig.builder().maxPayloadBytes(1).build().maxPayloadBytes());
        assertEquals(
                16_777_216,
                OutboxQueryConfig.builder().maxPayloadBytes(16_777_216).build().maxPayloadBytes());
    }

    @Test
    @DisplayName("the builder rejects zero, negatives and values above 16 MiB")
    void builderRejects() {
        ValidationException zero = assertThrows(
                ValidationException.class,
                () -> OutboxQueryConfig.builder().maxPayloadBytes(0).build());
        ValidationException negative = assertThrows(
                ValidationException.class,
                () -> OutboxQueryConfig.builder().maxPayloadBytes(-1).build());
        ValidationException tooBig = assertThrows(
                ValidationException.class,
                () -> OutboxQueryConfig.builder().maxPayloadBytes(16_777_217).build());
        ValidationException intMax = assertThrows(ValidationException.class, () -> OutboxQueryConfig.builder()
                .maxPayloadBytes(Integer.MAX_VALUE)
                .build());

        assertEquals("maxPayloadBytes must be positive", zero.getMessage());
        assertEquals("maxPayloadBytes must be positive", negative.getMessage());
        assertEquals("maxPayloadBytes must be at most 16777216", tooBig.getMessage());
        assertEquals("maxPayloadBytes must be at most 16777216", intMax.getMessage());
    }

    @Test
    @DisplayName("Jackson reads maxPayloadBytes")
    void deserializes() throws Exception {
        OutboxQueryConfig config = mapper.readValue("{\"maxPayloadBytes\": 2048}", OutboxQueryConfig.class);

        assertEquals(2048, config.maxPayloadBytes());
    }

    @Test
    @DisplayName("Jackson rejects an out-of-range value with the validation failure as the cause")
    void deserializationRejects() {
        for (String json :
                new String[] {"{\"maxPayloadBytes\": 0}", "{\"maxPayloadBytes\": -5}", "{\"maxPayloadBytes\": 16777217}"
                }) {
            Exception failure = assertThrows(Exception.class, () -> mapper.readValue(json, OutboxQueryConfig.class));

            assertInstanceOf(ValidationException.class, rootOf(failure), json);
            assertTrue(rootOf(failure).getMessage().startsWith("maxPayloadBytes must be"), json);
        }
    }
}
