// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * Opt-in Jackson serializer that writes a {@link BigDecimal} value as a JSON <em>string</em>
 * rather than a JSON number.
 *
 * <p>The string form uses {@link BigDecimal#toPlainString()}: scientific notation is never emitted
 * (e.g. {@code new BigDecimal("1.50")} → {@code "1.50"}), and trailing zeros in the scale are
 * preserved exactly as they appear on the source value.
 *
 * <p>This serializer is part of a <strong>matched opt-in pair</strong> with
 * {@link BigDecimalStrictStringDeserializer}: register both together on an application-owned
 * {@link com.fasterxml.jackson.databind.ObjectMapper} via a {@link com.fasterxml.jackson.databind.module.SimpleModule}
 * when a strict string wire form is required (e.g. for JavaScript clients that cannot safely
 * represent large decimals as IEEE-754 floats). This serializer is <strong>not</strong> registered
 * by {@link JacksonDefaults} or the {@code vertique} profile.
 *
 * <p><strong>Note:</strong> using this serializer changes the wire shape — a {@code BigDecimal}
 * field will be a JSON string on the wire, not a number. Clients must expect a string.
 *
 * @see BigDecimalStrictStringDeserializer
 */
public final class BigDecimalAsStringSerializer extends JsonSerializer<BigDecimal> {

    /**
     * Serializes {@code value} as a JSON string using {@link BigDecimal#toPlainString()}.
     *
     * @param value the {@link BigDecimal} to serialize; never {@code null} (Jackson skips null values)
     * @param gen   the {@link JsonGenerator} to write into
     * @param sp    the {@link SerializerProvider} (unused)
     * @throws IOException if the underlying generator throws
     */
    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider sp) throws IOException {
        gen.writeString(value.toPlainString());
    }
}
