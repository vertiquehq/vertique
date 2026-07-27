// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
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
 * <p><strong>Write-side length bound (json-004).</strong> The plain-string form written here is
 * bounded to the same {@value BigDecimalStrictStringDeserializer#MAX_LENGTH} characters that
 * {@link BigDecimalStrictStringDeserializer} enforces on read, via the shared
 * {@link BigDecimalStrictStringDeserializer#MAX_LENGTH} constant. Without this bound, a
 * huge-scale or huge-precision {@code BigDecimal} — however it was constructed, not just by
 * parsing a wire string — would have its {@link BigDecimal#toPlainString()} materialize an
 * unbounded number of digits on the way out, an egress amplification that a published schema's
 * {@code maxLength} constraint would otherwise promise never happens. The bound is checked cheaply
 * via {@link BigDecimal#scale()} / {@link BigDecimal#precision()} <strong>before</strong>
 * {@code toPlainString()} is ever called, so a pathological value (e.g. a scale in the billions)
 * is rejected without paying for the digit expansion.
 *
 * @see BigDecimalStrictStringDeserializer
 */
public final class BigDecimalAsStringSerializer extends JsonSerializer<BigDecimal> {

    /**
     * Serializes {@code value} as a JSON string using {@link BigDecimal#toPlainString()}, rejecting
     * a value whose plain-string form would exceed
     * {@value BigDecimalStrictStringDeserializer#MAX_LENGTH} characters.
     *
     * <p>The bound is enforced in two steps. First, a cheap check on {@link BigDecimal#scale()} and
     * {@link BigDecimal#precision()} rejects any value whose plain-string length is <em>guaranteed</em>
     * to exceed the bound, without ever calling {@link BigDecimal#toPlainString()}: a scale magnitude
     * or precision beyond the bound already proves the plain form is too long, however far beyond it
     * lies (this is what keeps a scale in the billions from materializing gigabytes of digits). Second,
     * for the values that pass the cheap check — where scale and precision are individually within
     * bound but their combination could still land on either side of the exact character bound (e.g. a
     * precision-2, scale-98 value plain-string to exactly 100 characters) — the plain string is
     * materialized (cheaply, since scale/precision are now known bounded) and its exact length is
     * checked.
     *
     * @param value the {@link BigDecimal} to serialize; never {@code null} (Jackson skips null values)
     * @param gen   the {@link JsonGenerator} to write into
     * @param sp    the {@link SerializerProvider} (unused)
     * @throws IOException if the underlying generator throws
     * @throws JsonMappingException if the plain-string form of {@code value} would exceed
     *         {@value BigDecimalStrictStringDeserializer#MAX_LENGTH} characters; the message names the
     *         bound and the offending scale/precision/length, never the value's digits (the message is
     *         log-reachable via {@code JsonBodyEncoder}'s {@code EncodeException} wrapping)
     */
    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider sp) throws IOException {
        int maxLength = BigDecimalStrictStringDeserializer.MAX_LENGTH;
        int scale = value.scale();
        int precision = value.precision();
        // Cheap pre-check: a scale magnitude or precision beyond maxLength already guarantees the
        // plain-string form exceeds it, so reject before ever paying for toPlainString().
        if (scale > maxLength || scale < -maxLength || precision > maxLength) {
            throw JsonMappingException.from(gen, boundViolationMessage(maxLength, scale, precision, null));
        }
        String plain = value.toPlainString();
        if (plain.length() > maxLength) {
            throw JsonMappingException.from(gen, boundViolationMessage(maxLength, scale, precision, plain.length()));
        }
        gen.writeString(plain);
    }

    /**
     * Builds the value-free bound-violation message, naming the bound and the offending
     * scale/precision (and, once materialized, the exact length) — never the value's digits.
     *
     * @param maxLength the enforced maximum plain-string length
     * @param scale     the offending value's {@link BigDecimal#scale()}
     * @param precision the offending value's {@link BigDecimal#precision()}
     * @param length    the materialized plain-string length, or {@code null} when rejected by the
     *                  cheap pre-check before materialization
     * @return the exception message
     */
    private static String boundViolationMessage(int maxLength, int scale, int precision, Integer length) {
        StringBuilder message = new StringBuilder("BigDecimal plain-string form exceeds the maximum length of ")
                .append(maxLength)
                .append(" characters (scale=")
                .append(scale)
                .append(", precision=")
                .append(precision);
        if (length != null) {
            message.append(", length=").append(length);
        }
        return message.append(')').toString();
    }
}
