// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link KafkaRecordHeader}: the value is a snapshot, equality is by content, the text
 * form never shows the bytes, and text decoding is strict.
 */
class KafkaRecordHeaderTest {

    /** Two bytes that are never valid in UTF-8. */
    private static final byte[] NOT_UTF8 = {(byte) 0xFF, (byte) 0xFE};

    @Test
    @DisplayName("the key must not be null")
    void rejectsNullKey() {
        assertThrows(NullPointerException.class, () -> new KafkaRecordHeader(null, new byte[] {1}));
    }

    @Test
    @DisplayName("null, empty and non-empty values stay distinguishable")
    void nullEmptyAndPresentValues() {
        assertNull(new KafkaRecordHeader("k", null).value());
        assertArrayEquals(new byte[0], new KafkaRecordHeader("k", new byte[0]).value());
        assertArrayEquals(new byte[] {1, 2}, new KafkaRecordHeader("k", new byte[] {1, 2}).value());
    }

    @Test
    @DisplayName("bytes that are not UTF-8 are kept byte for byte")
    void binaryValueIsKept() {
        assertArrayEquals(NOT_UTF8, new KafkaRecordHeader("k", NOT_UTF8.clone()).value());
    }

    @Test
    @DisplayName("changing the source array after construction does not change the header")
    void sourceArrayIsCopied() {
        byte[] source = {1, 2, 3};
        KafkaRecordHeader header = new KafkaRecordHeader("k", source);

        source[0] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, header.value());
    }

    @Test
    @DisplayName("changing a returned array does not change the header")
    void returnedArrayIsCopied() {
        KafkaRecordHeader header = new KafkaRecordHeader("k", new byte[] {1, 2, 3});

        byte[] first = header.value();
        first[0] = 9;

        assertNotSame(first, header.value());
        assertArrayEquals(new byte[] {1, 2, 3}, header.value());
    }

    @Test
    @DisplayName("equality and hash code compare the key and the bytes")
    void equalityByContent() {
        KafkaRecordHeader header = new KafkaRecordHeader("k", new byte[] {1, 2});

        assertEquals(header, new KafkaRecordHeader("k", new byte[] {1, 2}));
        assertEquals(header.hashCode(), new KafkaRecordHeader("k", new byte[] {1, 2}).hashCode());
        assertEquals(new KafkaRecordHeader("k", null), new KafkaRecordHeader("k", null));
        assertEquals(new KafkaRecordHeader("k", null).hashCode(), new KafkaRecordHeader("k", null).hashCode());

        assertNotEquals(header, new KafkaRecordHeader("other", new byte[] {1, 2}));
        assertNotEquals(header, new KafkaRecordHeader("k", new byte[] {1, 3}));
        assertNotEquals(header, new KafkaRecordHeader("K", new byte[] {1, 2}));
        assertNotEquals(new KafkaRecordHeader("k", null), new KafkaRecordHeader("k", new byte[0]));
        assertNotEquals(header, "k");
    }

    @Test
    @DisplayName("toString shows the key and the value length, never the bytes")
    void toStringHidesTheValue() {
        String text = new KafkaRecordHeader("authorization", "s3cret".getBytes(StandardCharsets.UTF_8)).toString();

        assertEquals("KafkaRecordHeader[key=authorization, value=6 bytes]", text);
        assertFalse(text.contains("s3cret"));
        assertEquals("KafkaRecordHeader[key=k, value=null]", new KafkaRecordHeader("k", null).toString());
    }

    @Test
    @DisplayName("ofUtf8 encodes text as UTF-8 and keeps null and empty apart")
    void ofUtf8() {
        assertArrayEquals(
                new byte[] {(byte) 0xC3, (byte) 0xA4},
                KafkaRecordHeader.ofUtf8("k", "ä").value());
        assertArrayEquals(new byte[0], KafkaRecordHeader.ofUtf8("k", "").value());
        assertNull(KafkaRecordHeader.ofUtf8("k", null).value());
        assertThrows(NullPointerException.class, () -> KafkaRecordHeader.ofUtf8(null, "v"));
    }

    @Test
    @DisplayName("text decoding returns null for a null value and an empty string for empty bytes")
    void decodesNullAndEmpty() {
        assertNull(new KafkaRecordHeader("k", null).valueAsUtf8());
        assertNull(new KafkaRecordHeader("k", null).valueAsString(StandardCharsets.ISO_8859_1));
        assertEquals("", new KafkaRecordHeader("k", new byte[0]).valueAsUtf8());
        assertEquals("", new KafkaRecordHeader("k", new byte[0]).valueAsString(StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("text decoding uses the requested charset")
    void decodesWithCharset() {
        byte[] latin1 = {(byte) 0xE4};

        assertEquals("ä", new KafkaRecordHeader("k", latin1).valueAsString(StandardCharsets.ISO_8859_1));
        assertEquals("ä", KafkaRecordHeader.ofUtf8("k", "ä").valueAsUtf8());
        assertEquals("ä", KafkaRecordHeader.ofUtf8("k", "ä").valueAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("malformed input is rejected, and the message does not show the bytes")
    void rejectsMalformedInput() {
        KafkaRecordHeader header = new KafkaRecordHeader("trace", NOT_UTF8.clone());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, header::valueAsUtf8);

        assertEquals("Header 'trace' is not valid UTF-8 (2 bytes)", thrown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> header.valueAsString(StandardCharsets.UTF_8));
        // A byte above 0x7F cannot be decoded as US-ASCII.
        assertThrows(IllegalArgumentException.class, () -> header.valueAsString(StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("a truncated multi-byte sequence is rejected rather than replaced")
    void rejectsTruncatedSequence() {
        KafkaRecordHeader header = new KafkaRecordHeader("k", new byte[] {'a', (byte) 0xC3});

        assertThrows(IllegalArgumentException.class, header::valueAsUtf8);
    }
}
