// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DelayedJobPayloadDecoder")
class DelayedJobPayloadDecoderTest {

    private static final String ADDRESS = "jobs/delayed/test/execute";

    record Payload(String name, int count) {}

    /** Fails with an exception Jackson passes through unwrapped, quoting the value it was given. */
    static final class ThrowingDeserializer extends StdDeserializer<Object> {
        ThrowingDeserializer() {
            super(Object.class);
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext ctx) throws IOException {
            throw new ArithmeticException(parser.getValueAsString());
        }
    }

    @JsonDeserialize(using = ThrowingDeserializer.class)
    record Throwing(String value) {}

    private static DelayedJobPayloadDecoder decoderFor(Class<?> type) {
        return new DelayedJobPayloadDecoder(Map.of(ADDRESS, type));
    }

    @Test
    @DisplayName("converts a stored object to the declared record")
    void convertsToRecord() {
        Object decoded = decoderFor(Payload.class)
                .decode(ADDRESS, new JsonObject().put("name", "a").put("count", 2));

        assertEquals(new Payload("a", 2), decoded);
    }

    @Test
    @DisplayName("returns a value of the declared type unchanged")
    void returnsInstanceUnchanged() {
        JsonObject stored = new JsonObject().put("name", "a");

        assertSame(stored, decoderFor(JsonObject.class).decode(ADDRESS, stored));
        assertSame(stored, decoderFor(Object.class).decode(ADDRESS, stored));
    }

    @Test
    @DisplayName("returns the stored value unchanged for a handler with no declared payload")
    void returnsStoredValueForUnknownHandler() {
        JsonObject stored = new JsonObject().put("name", "a");

        assertSame(stored, new DelayedJobPayloadDecoder(Map.of()).decode(ADDRESS, stored));
    }

    @Test
    @DisplayName("a null payload stays null")
    void nullStaysNull() {
        assertNull(decoderFor(Payload.class).decode(ADDRESS, null));
    }

    @Test
    @DisplayName("a failure names the type and never quotes the value")
    void failureDoesNotQuoteTheValue() {
        DelayedJobPayloadDecoder decoder = decoderFor(Payload.class);
        JsonObject stored = new JsonObject().put("name", "a").put("count", "secret-value");

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> decoder.decode(ADDRESS, stored));

        assertEquals("Job payload cannot be decoded to " + Payload.class.getTypeName(), failure.getMessage());
        assertFalse(failure.getMessage().contains("secret-value"));
        assertNull(failure.getCause());
    }

    @Test
    @DisplayName("a failure Jackson does not wrap is reported the same way")
    void unwrappedFailureIsReported() {
        DelayedJobPayloadDecoder decoder = decoderFor(Throwing.class);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decode(ADDRESS, new JsonObject().put("value", "secret-value")));

        assertEquals("Job payload cannot be decoded to " + Throwing.class.getTypeName(), failure.getMessage());
        assertNull(failure.getCause());
    }
}
