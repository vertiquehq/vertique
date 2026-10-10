// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vertx.core.buffer.Buffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link KafkaRecordHeader}: the value is a buffer snapshot, equality is by content, the
 * text form never shows the bytes, and text decoding is strict.
 */
class KafkaRecordHeaderTest {

    /** Two bytes that are never valid in UTF-8. */
    private static final byte[] NOT_UTF8 = {(byte) 0xFF, (byte) 0xFE};

    private static Buffer buffer(int... bytes) {
        Buffer buffer = Buffer.buffer(bytes.length);
        for (int b : bytes) {
            buffer.appendByte((byte) b);
        }
        return buffer;
    }

    @Test
    @DisplayName("the key must not be null")
    void rejectsNullKey() {
        assertThrows(NullPointerException.class, () -> new KafkaRecordHeader(null, buffer(1)));
    }

    @Test
    @DisplayName("null, empty and non-empty values stay distinguishable")
    void nullEmptyAndPresentValues() {
        assertNull(new KafkaRecordHeader("k", null).value());
        assertEquals(Buffer.buffer(), new KafkaRecordHeader("k", Buffer.buffer()).value());
        assertEquals(0, new KafkaRecordHeader("k", Buffer.buffer()).value().length());
        assertEquals(buffer(1, 2), new KafkaRecordHeader("k", buffer(1, 2)).value());
    }

    @Test
    @DisplayName("the value is a buffer with the content of the input")
    void valueHasTheContentOfTheInput() {
        Buffer input = buffer(1, 2, 3);

        Buffer value = new KafkaRecordHeader("k", input).value();

        assertEquals(input, value);
        assertNotSame(input, value);
        assertEquals(3, value.length());
        assertEquals(
                List.of((byte) 1, (byte) 2, (byte) 3), List.of(value.getByte(0), value.getByte(1), value.getByte(2)));
    }

    @Test
    @DisplayName("bytes that are not UTF-8 are kept byte for byte")
    void binaryValueIsKept() {
        assertEquals(Buffer.buffer(NOT_UTF8), new KafkaRecordHeader("k", Buffer.buffer(NOT_UTF8)).value());
    }

    @Test
    @DisplayName("changing the source buffer after construction does not change the header")
    void sourceBufferIsCopied() {
        Buffer source = buffer(1, 2, 3);
        KafkaRecordHeader header = new KafkaRecordHeader("k", source);

        source.setByte(0, (byte) 9);
        source.appendByte((byte) 4);

        assertEquals(buffer(1, 2, 3), header.value());
        assertEquals("KafkaRecordHeader[key=k, value=3 bytes]", header.toString());
        assertEquals(new KafkaRecordHeader("k", buffer(1, 2, 3)), header);
    }

    @Test
    @DisplayName("changing a returned buffer does not change the header")
    void returnedBufferIsCopied() {
        KafkaRecordHeader header = new KafkaRecordHeader("k", buffer(1, 2, 3));

        Buffer first = header.value();
        first.setByte(0, (byte) 9);
        first.appendByte((byte) 4);

        assertNotSame(first, header.value());
        assertEquals(buffer(1, 2, 3), header.value());
        assertEquals(new KafkaRecordHeader("k", buffer(1, 2, 3)), header);
        assertEquals(new KafkaRecordHeader("k", buffer(1, 2, 3)).hashCode(), header.hashCode());
    }

    @Test
    @DisplayName("equality and hash code compare the key and the bytes")
    void equalityByContent() {
        KafkaRecordHeader header = new KafkaRecordHeader("k", buffer(1, 2));

        assertEquals(header, new KafkaRecordHeader("k", buffer(1, 2)));
        assertEquals(header.hashCode(), new KafkaRecordHeader("k", buffer(1, 2)).hashCode());
        assertEquals(new KafkaRecordHeader("k", null), new KafkaRecordHeader("k", null));
        assertEquals(new KafkaRecordHeader("k", null).hashCode(), new KafkaRecordHeader("k", null).hashCode());

        assertNotEquals(header, new KafkaRecordHeader("other", buffer(1, 2)));
        assertNotEquals(header, new KafkaRecordHeader("k", buffer(1, 3)));
        assertNotEquals(header, new KafkaRecordHeader("K", buffer(1, 2)));
        assertNotEquals(new KafkaRecordHeader("k", null), new KafkaRecordHeader("k", Buffer.buffer()));
        assertNotEquals(header, "k");
    }

    @Test
    @DisplayName("toString shows the key and the value length, never the bytes")
    void toStringHidesTheValue() {
        String text = new KafkaRecordHeader("authorization", Buffer.buffer("s3cret")).toString();

        assertEquals("KafkaRecordHeader[key=authorization, value=6 bytes]", text);
        assertFalse(text.contains("s3cret"));
        assertEquals("KafkaRecordHeader[key=k, value=null]", new KafkaRecordHeader("k", null).toString());
    }

    @Test
    @DisplayName("ofUtf8 encodes text as UTF-8 and keeps null and empty apart")
    void ofUtf8() {
        assertEquals(buffer(0xC3, 0xA4), KafkaRecordHeader.ofUtf8("k", "ä").value());
        assertEquals(Buffer.buffer(), KafkaRecordHeader.ofUtf8("k", "").value());
        assertNull(KafkaRecordHeader.ofUtf8("k", null).value());
        assertThrows(NullPointerException.class, () -> KafkaRecordHeader.ofUtf8(null, "v"));
    }

    @Test
    @DisplayName("text decoding returns null for a null value and an empty string for empty bytes")
    void decodesNullAndEmpty() {
        assertNull(new KafkaRecordHeader("k", null).valueAsUtf8());
        assertNull(new KafkaRecordHeader("k", null).valueAsString(StandardCharsets.ISO_8859_1));
        assertEquals("", new KafkaRecordHeader("k", Buffer.buffer()).valueAsUtf8());
        assertEquals("", new KafkaRecordHeader("k", Buffer.buffer()).valueAsString(StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("text decoding rejects a null charset, whatever the value")
    void rejectsNullCharset() {
        assertThrows(NullPointerException.class, () -> KafkaRecordHeader.ofUtf8("k", "v")
                .valueAsString(null));
        assertThrows(NullPointerException.class, () -> new KafkaRecordHeader("k", null).valueAsString(null));
    }

    @Test
    @DisplayName("text decoding uses the requested charset")
    void decodesWithCharset() {
        Buffer latin1 = buffer(0xE4);

        assertEquals("ä", new KafkaRecordHeader("k", latin1).valueAsString(StandardCharsets.ISO_8859_1));
        assertEquals("ä", KafkaRecordHeader.ofUtf8("k", "ä").valueAsUtf8());
        assertEquals("ä", KafkaRecordHeader.ofUtf8("k", "ä").valueAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("malformed input is rejected, and the message does not show the bytes")
    void rejectsMalformedInput() {
        KafkaRecordHeader header = new KafkaRecordHeader("trace", Buffer.buffer(NOT_UTF8));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, header::valueAsUtf8);

        assertEquals("Header 'trace' is not valid UTF-8 (2 bytes)", thrown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> header.valueAsString(StandardCharsets.UTF_8));
        // A byte above 0x7F cannot be decoded as US-ASCII.
        assertThrows(IllegalArgumentException.class, () -> header.valueAsString(StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("a truncated multi-byte sequence is rejected rather than replaced")
    void rejectsTruncatedSequence() {
        KafkaRecordHeader header = new KafkaRecordHeader("k", buffer('a', 0xC3));

        assertThrows(IllegalArgumentException.class, header::valueAsUtf8);
    }
}
