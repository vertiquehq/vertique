// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * Opt-in, <em>strict</em> Jackson deserializer for {@link BigDecimal} that accepts only a JSON
 * <strong>string</strong> token as input.
 *
 * <p>This is the strict counterpart to {@link BigDecimalAsStringSerializer}. Accepted input: a JSON
 * string whose content is a valid {@link BigDecimal} literal with <strong>no surrounding
 * whitespace</strong> (e.g. {@code "1.50"} → {@code new BigDecimal("1.50")}). The string is
 * passed directly to {@code new BigDecimal(String)} without trimming; any leading or trailing
 * whitespace causes a {@link com.fasterxml.jackson.databind.exc.MismatchedInputException}.
 * All other tokens are rejected with a clean {@link com.fasterxml.jackson.databind.exc.MismatchedInputException},
 * including:
 * <ul>
 *   <li>JSON numbers ({@code 1.5}) — rejected, even though they are valid decimals.</li>
 *   <li>{@code true} / {@code false} — rejected.</li>
 *   <li>JSON objects ({@code {}}) — rejected.</li>
 *   <li>JSON arrays ({@code []}) — rejected.</li>
 *   <li>Whitespace-padded strings ({@code " 1.5 "}) — rejected; the string must be an exact
 *       decimal literal with no surrounding whitespace.</li>
 *   <li>Malformed numeric strings ({@code "abc"}) — rejected with a clean mapping error,
 *       never a raw {@link NumberFormatException} or {@code NullPointerException}.</li>
 * </ul>
 *
 * <p>A JSON {@code null} is <strong>allowed</strong> and yields {@code null} (the standard
 * "absent value" semantics): Jackson routes a {@code null} token through the null-value provider, so
 * {@link #deserialize(JsonParser, DeserializationContext) deserialize} is never invoked for it. Only
 * non-{@code null}, non-string tokens are rejected.
 *
 * <p>This deserializer is part of a <strong>matched opt-in pair</strong> with
 * {@link BigDecimalAsStringSerializer}: register both on an application-owned
 * {@link com.fasterxml.jackson.databind.ObjectMapper} via a
 * {@link com.fasterxml.jackson.databind.module.SimpleModule} when a strict string wire form of
 * {@code BigDecimal} is required. This deserializer is <strong>not</strong> registered by
 * {@link JacksonDefaults} or the {@code vertique} profile.
 *
 * @see BigDecimalAsStringSerializer
 */
public final class BigDecimalStrictStringDeserializer extends JsonDeserializer<BigDecimal> {

    /**
     * Deserializes a {@link BigDecimal} from a JSON string token only.
     *
     * <p>Accepts {@code VALUE_STRING} with a valid decimal literal that contains no surrounding
     * whitespace. Rejects every other token — including JSON numbers, booleans, objects, and arrays
     * — and malformed or whitespace-padded decimal strings, with a clean
     * {@link com.fasterxml.jackson.databind.exc.MismatchedInputException} in all cases.
     *
     * @param p    the {@link JsonParser} positioned at the value token
     * @param ctxt the {@link DeserializationContext} for error reporting
     * @return the parsed {@link BigDecimal}
     * @throws IOException                                                     if the underlying parser throws
     * @throws com.fasterxml.jackson.databind.exc.MismatchedInputException    if the token is not a JSON string,
     *                                                                          or the string is not a valid decimal
     */
    @Override
    public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() != JsonToken.VALUE_STRING) {
            throw MismatchedInputException.from(
                    p,
                    BigDecimal.class,
                    "Expected a JSON string containing a decimal literal; got " + p.currentToken());
        }
        String text = p.getText();
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw MismatchedInputException.from(p, BigDecimal.class, "Not a valid decimal string: \"" + text + "\"");
        }
    }
}
