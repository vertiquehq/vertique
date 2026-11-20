// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.inboxoutbox.exception.InboxOutboxConfigurationException;
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
        assertEquals(1_048_576, OutboxQueryConfig.defaults().maxPayloadBytes());
        assertEquals(1_048_576, mapper.readValue("{}", OutboxQueryConfig.class).maxPayloadBytes());
    }

    @Test
    @DisplayName("the constructor accepts one byte and 16 MiB")
    void constructorBounds() {
        assertEquals(1, new OutboxQueryConfig(1).maxPayloadBytes());
        assertEquals(16_777_216, new OutboxQueryConfig(16_777_216).maxPayloadBytes());
    }

    @Test
    @DisplayName("the constructor rejects zero, negatives and values above 16 MiB")
    void constructorRejects() {
        InboxOutboxConfigurationException zero =
                assertThrows(InboxOutboxConfigurationException.class, () -> new OutboxQueryConfig(0));
        InboxOutboxConfigurationException negative =
                assertThrows(InboxOutboxConfigurationException.class, () -> new OutboxQueryConfig(-1));
        InboxOutboxConfigurationException tooBig =
                assertThrows(InboxOutboxConfigurationException.class, () -> new OutboxQueryConfig(16_777_217));
        InboxOutboxConfigurationException intMax =
                assertThrows(InboxOutboxConfigurationException.class, () -> new OutboxQueryConfig(Integer.MAX_VALUE));

        String message = "inboxOutbox.query.maxPayloadBytes must be between 1 and 16777216";
        assertEquals(message, zero.getMessage());
        assertEquals(message, negative.getMessage());
        assertEquals(message, tooBig.getMessage());
        assertEquals(message, intMax.getMessage());
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

            assertInstanceOf(InboxOutboxConfigurationException.class, rootOf(failure), json);
            assertTrue(rootOf(failure).getMessage().startsWith("inboxOutbox.query.maxPayloadBytes must be"), json);
        }
    }
}
