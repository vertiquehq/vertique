// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.vertique.core.json.VertiqueJson;

/**
 * JSON serialization/deserialization helper for WebSocket messages. Uses the process JSON codec's
 * {@link com.fasterxml.jackson.databind.ObjectMapper} ({@link VertiqueJson#mapper()}) for
 * consistency with the rest of the process.
 *
 * <p>Supports two-phase deserialization for pre-materialization processing by
 * {@link dev.vertique.input.processing.InputObjectProcessor}: first decode to an intermediate
 * map/list structure, apply canonicalization and sanitization, then materialize to the target type.
 */
public class WebSocketMessageCodec {

    /**
     * Deserializes a JSON text message into an instance of the given type.
     *
     * @param <T>  the target type
     * @param text the JSON text to deserialize
     * @param type the target class
     * @return the deserialized instance; never {@code null}
     * @throws JsonProcessingException if the text is not valid JSON or cannot be mapped to {@code type}
     */
    <T> T decode(String text, Class<T> type) throws JsonProcessingException {
        return VertiqueJson.mapper().readValue(text, type);
    }

    /**
     * Decodes JSON text to an intermediate representation ({@code Map}, {@code List}, or scalar)
     * suitable for pre-materialization processing by
     * {@link dev.vertique.input.processing.InputObjectProcessor}.
     *
     * @param text the JSON text to decode; must not be {@code null}
     * @return the intermediate structure; never {@code null} for valid non-null JSON
     * @throws JsonProcessingException if the text is not valid JSON
     */
    Object decodeToIntermediate(String text) throws JsonProcessingException {
        return VertiqueJson.mapper().readValue(text, Object.class);
    }

    /**
     * Converts a processed intermediate representation to the target type.
     *
     * @param <T>          the target type
     * @param intermediate the processed intermediate (e.g. a {@code Map<String, Object>}
     *                     after canonicalization and sanitization); must not be {@code null}
     * @param type         the target class to convert to
     * @return the converted instance; never {@code null}
     * @throws IllegalArgumentException if the intermediate cannot be converted to {@code type}
     */
    <T> T convertFromIntermediate(Object intermediate, Class<T> type) {
        return VertiqueJson.mapper().convertValue(intermediate, type);
    }

    /**
     * Serializes an object to a JSON text string.
     *
     * @param message the object to serialize; must not be {@code null}
     * @return the JSON representation; never {@code null}
     * @throws JsonProcessingException if the object cannot be serialized
     */
    String encode(Object message) throws JsonProcessingException {
        return VertiqueJson.mapper().writeValueAsString(message);
    }
}
