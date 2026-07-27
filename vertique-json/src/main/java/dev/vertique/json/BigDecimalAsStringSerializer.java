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
 * (e.g. {@code new BigDecimal("1.50")} → {@code "1.50"}). Numerical equality is always preserved,
 * and the scale is preserved exactly for a <strong>non-negative</strong> scale (e.g. trailing zeros
 * in {@code "1.50"} round-trip as scale 2). A <strong>negative</strong>-scale value is instead
 * written in its expanded plain form — {@code new BigDecimal("1E+2")} writes {@code "100"}, and a
 * negative-scale zero (e.g. from {@code 1E+200 - 1E+200}) writes {@code "0"} — and re-reads with
 * scale 0, not the original negative scale (json-004 R2-4).
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
     * Serializes {@code value} as a JSON string using its bounded {@link BigDecimal#toPlainString()}
     * form, computed by {@link #boundedPlainString(BigDecimal)}; see that method's javadoc for the
     * exact rejection bound (including the negative-scale zero exemption, json-004 R2-2).
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
        try {
            gen.writeString(boundedPlainString(value));
        } catch (IllegalArgumentException rejected) {
            throw JsonMappingException.from(gen, rejected.getMessage());
        }
    }

    // --- Shared bounded plain-string form ---

    /**
     * Computes {@code value}'s bounded {@link BigDecimal#toPlainString()} form, independent of any
     * Jackson generator/provider.
     *
     * <p>Shared by {@link #serialize(BigDecimal, JsonGenerator, SerializerProvider)} (JSON string
     * values) and the {@code vertique-strict} profile's {@code BigDecimal} map-key serializer
     * (json-004 R2-3), so the write-side length bound is enforced identically for both a decimal
     * <em>value</em> and a decimal <em>map key</em> — a map key cannot bypass the value-side bound
     * and re-emerge from {@link BigDecimal#toString()}'s scientific notation, which the profile's own
     * bounded key deserializer would then reject on read.
     *
     * <p>The bound is enforced in two steps, mirroring {@link #serialize(BigDecimal, JsonGenerator,
     * SerializerProvider)}. First, a cheap check on {@link BigDecimal#scale()} and
     * {@link BigDecimal#precision()} rejects any value whose plain-string length is <em>guaranteed</em>
     * to exceed the bound, without ever calling {@link BigDecimal#toPlainString()}. A negative scale
     * beyond the bound is exempted for a <strong>zero-valued</strong> {@code value}
     * ({@link BigDecimal#signum()} {@code == 0}, json-004 R2-2): ordinary arithmetic (e.g. subtracting
     * two equal huge round numbers) can produce a zero with an arbitrarily negative scale whose
     * {@code toPlainString()} is still the single character {@code "0"} — the huge negative scale
     * alone does not guarantee an over-length plain form for a zero value the way it does for a
     * nonzero one. A <em>positive</em> scale beyond the bound is never exempted, zero-valued or not:
     * {@link BigDecimal#toPlainString()} always emits scale trailing-zero digits after the decimal
     * point regardless of sign, so a positive scale beyond the bound still guarantees an over-length
     * form. Second, for the values that pass the cheap check, the plain string is materialized
     * (cheaply, since scale/precision are now known bounded) and its exact length is checked.
     *
     * @param value the {@link BigDecimal} to render; never {@code null}
     * @return the bounded {@link BigDecimal#toPlainString()} form
     * @throws IllegalArgumentException if the plain-string form of {@code value} would exceed
     *         {@value BigDecimalStrictStringDeserializer#MAX_LENGTH} characters; the message names the
     *         bound and the offending scale/precision/length, never the value's digits
     */
    static String boundedPlainString(BigDecimal value) {
        int maxLength = BigDecimalStrictStringDeserializer.MAX_LENGTH;
        int scale = value.scale();
        int precision = value.precision();
        // Cheap pre-check: a scale magnitude or precision beyond maxLength already guarantees the
        // plain-string form exceeds it, so reject before ever paying for toPlainString() — except a
        // negative scale on a zero-valued BigDecimal, whose plain form is always just "0" (R2-2).
        if (scale > maxLength || (scale < -maxLength && value.signum() != 0) || precision > maxLength) {
            throw new IllegalArgumentException(boundViolationMessage(maxLength, scale, precision, null));
        }
        String plain = value.toPlainString();
        if (plain.length() > maxLength) {
            throw new IllegalArgumentException(boundViolationMessage(maxLength, scale, precision, plain.length()));
        }
        return plain;
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
