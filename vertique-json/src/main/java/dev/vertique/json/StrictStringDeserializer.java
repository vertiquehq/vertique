// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.io.IOException;

/**
 * Opt-in Jackson deserializer for {@link String} that <strong>rejects scalar-to-string
 * coercion</strong>.
 *
 * <p>Jackson's default {@code String} deserializer silently coerces non-string scalars: a JSON
 * number {@code 123} or {@code true} targeting a {@code String} property becomes the string
 * {@code "123"} or {@code "true"}. This deserializer disables that coercion: only a JSON
 * {@code VALUE_STRING} token is accepted; any other scalar token (number, boolean) causes a
 * {@link com.fasterxml.jackson.databind.exc.MismatchedInputException}.
 *
 * <p>A JSON {@code null} is <strong>allowed</strong> and yields {@code null} (the standard
 * "absent value" semantics): Jackson routes a {@code null} token through the null-value provider, so
 * {@link #deserialize(JsonParser, DeserializationContext) deserialize} is never invoked for it. Only
 * non-{@code null}, non-string tokens are rejected.
 *
 * <p>This deserializer is <strong>opt-in</strong> and is <strong>not</strong> registered by
 * {@link JacksonDefaults} or the {@code vertique} profile. Register it on an application-owned
 * {@link com.fasterxml.jackson.databind.ObjectMapper} via a
 * {@link com.fasterxml.jackson.databind.module.SimpleModule} when strict string-only fields are
 * required.
 */
public final class StrictStringDeserializer extends JsonDeserializer<String> {

    /**
     * Deserializes a {@link String} from a JSON string token only.
     *
     * <p>Accepts {@code VALUE_STRING}. Rejects any non-string scalar (e.g. {@code VALUE_NUMBER_INT},
     * {@code VALUE_NUMBER_FLOAT}, {@code VALUE_TRUE}, {@code VALUE_FALSE}) with a
     * {@link com.fasterxml.jackson.databind.exc.MismatchedInputException}.
     *
     * @param p    the {@link JsonParser} positioned at the value token
     * @param ctxt the {@link DeserializationContext} for error reporting
     * @return the string value
     * @throws IOException                                                     if the underlying parser throws
     * @throws com.fasterxml.jackson.databind.exc.MismatchedInputException    if the current token is not a JSON string
     */
    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() != JsonToken.VALUE_STRING) {
            throw MismatchedInputException.from(
                    p, String.class, "Expected JSON string; scalar coercion rejected — got " + p.currentToken());
        }
        return p.getText();
    }
}
