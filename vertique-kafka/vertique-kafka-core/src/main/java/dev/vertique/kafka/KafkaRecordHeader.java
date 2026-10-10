// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import io.vertx.core.buffer.Buffer;
import jakarta.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * One Kafka record header exactly as it is on the wire: a key and a value of raw bytes, held as a
 * Vert.x {@link Buffer}.
 *
 * <p>A header value is bytes, not text, and may be {@code null}. This record keeps the three cases
 * apart: a {@code null} value, an empty value (a zero-length buffer) and a value with content.
 *
 * <p>The record is an immutable snapshot. A {@code Buffer} is mutable, so the buffer is copied when
 * the header is created and again on every {@link #value()} call: neither the caller's buffer nor a
 * returned buffer can change the header.
 *
 * <p>{@link #equals(Object)} and {@link #hashCode()} compare the key and the content of the value.
 * {@link #toString()} shows the key and the length of the value, never the bytes, so a header can be
 * logged without leaking a credential it may carry.
 *
 * @param key   the header key as received; never {@code null}; not trimmed and not case-folded
 * @param value the header value, or {@code null} when the header has none
 */
public record KafkaRecordHeader(String key, @Nullable Buffer value) {

    /**
     * Validates the key and copies the value buffer.
     *
     * @param key   the header key; must not be {@code null}
     * @param value the header value, or {@code null}; copied
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public KafkaRecordHeader {
        Objects.requireNonNull(key, "key");
        value = value == null ? null : value.copy();
    }

    /**
     * Creates a header whose value is the UTF-8 encoding of a string.
     *
     * @param key   the header key; must not be {@code null}
     * @param value the text value, or {@code null} for a header without a value; an empty string
     *              gives an empty value, not a {@code null} one
     * @return the header; never {@code null}
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public static KafkaRecordHeader ofUtf8(String key, @Nullable String value) {
        return new KafkaRecordHeader(key, value == null ? null : Buffer.buffer(value.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Returns a copy of the header value.
     *
     * @return a new buffer holding the value bytes, which the caller owns, or {@code null} when the
     *     header has no value
     */
    @Override
    @Nullable
    public Buffer value() {
        return value == null ? null : value.copy();
    }

    /**
     * Decodes the value as text in the given charset, strictly: input that is not valid in that
     * charset is rejected rather than replaced.
     *
     * @param charset the charset to decode with; must not be {@code null}
     * @return the decoded text; {@code null} when the header has no value; an empty string when the
     *     value is empty
     * @throws IllegalArgumentException if the value is not valid in {@code charset}; the message
     *     names the key, the charset and the value length, never the bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    @Nullable
    public String valueAsString(Charset charset) {
        Objects.requireNonNull(charset, "charset");
        if (value == null) {
            return null;
        }
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value.getBytes()))
                    .toString();
        } catch (CharacterCodingException e) {
            // The cause is left out: it adds nothing but the offset of the first bad byte.
            throw new IllegalArgumentException(
                    "Header '" + key + "' is not valid " + charset.name() + " (" + value.length() + " bytes)");
        }
    }

    /**
     * Decodes the value as UTF-8 text, strictly. Same as {@link #valueAsString(Charset)} with UTF-8.
     *
     * @return the decoded text; {@code null} when the header has no value; an empty string when the
     *     value is empty
     * @throws IllegalArgumentException if the value is not valid UTF-8
     */
    @Nullable
    public String valueAsUtf8() {
        return valueAsString(StandardCharsets.UTF_8);
    }

    /**
     * Decodes the value as UTF-8 text leniently, substituting the replacement character for malformed
     * input, reading the held buffer without copying it. This is the decoding of the lossy text
     * projection.
     *
     * @return the decoded text, or {@code null} when the header has no value
     */
    @Nullable
    String valueAsLenientUtf8() {
        return value == null ? null : value.toString(StandardCharsets.UTF_8);
    }

    /**
     * Compares the key and the content of the value.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a header with an equal key and equal value bytes; a
     *     {@code null} value equals only a {@code null} value
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof KafkaRecordHeader that && key.equals(that.key) && Objects.equals(value, that.value);
    }

    /**
     * Returns a hash code built from the key and the content of the value.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return 31 * key.hashCode() + Objects.hashCode(value);
    }

    /**
     * Returns the key and the length of the value. The bytes are never shown.
     *
     * @return a description that is safe to log
     */
    @Override
    public String toString() {
        return "KafkaRecordHeader[key=" + key + ", value=" + (value == null ? "null" : value.length() + " bytes") + "]";
    }
}
