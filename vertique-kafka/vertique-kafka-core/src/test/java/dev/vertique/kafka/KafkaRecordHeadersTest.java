// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link KafkaRecordHeaders}: order and duplicates are kept, lookups distinguish an absent
 * key from a null or empty value, the collection is an immutable snapshot, and {@code asMap()}
 * yields the lossy text map the consumer has always handed to filters and deserializers.
 */
class KafkaRecordHeadersTest {

    /** Two bytes that are never valid in UTF-8. */
    private static final byte[] NOT_UTF8 = {(byte) 0xFF, (byte) 0xFE};

    /** The Unicode replacement character, which lenient decoding substitutes for a malformed byte. */
    private static final String REPLACEMENT = String.valueOf((char) 0xFFFD);

    private static KafkaRecordHeader text(String key, String value) {
        return KafkaRecordHeader.ofUtf8(key, value);
    }

    @Test
    @DisplayName("entries keep their order, including interleaved duplicates")
    void keepsOrderAndDuplicates() {
        List<KafkaRecordHeader> entries = List.of(text("a", "1"), text("b", "2"), text("a", "3"), text("b", "4"));

        KafkaRecordHeaders headers = new KafkaRecordHeaders(entries);

        assertEquals(entries, headers.entries());
        List<KafkaRecordHeader> iterated = new ArrayList<>();
        headers.forEach(iterated::add);
        assertEquals(entries, iterated);
    }

    @Test
    @DisplayName("headers(key) returns every match in order")
    void headersByKey() {
        KafkaRecordHeaders headers =
                new KafkaRecordHeaders(List.of(text("a", "1"), text("b", "2"), text("a", "3"), text("a", null)));

        assertEquals(List.of(text("a", "1"), text("a", "3"), text("a", null)), headers.headers("a"));
        assertEquals(List.of(text("b", "2")), headers.headers("b"));
        assertEquals(List.of(), headers.headers("absent"));
        assertThrows(
                UnsupportedOperationException.class, () -> headers.headers("a").add(text("a", "9")));
        assertThrows(UnsupportedOperationException.class, () -> headers.headers("absent")
                .add(text("a", "9")));
    }

    @Test
    @DisplayName("keys are matched exactly: no case folding and no trimming")
    void keysAreNotNormalized() {
        KafkaRecordHeaders headers = new KafkaRecordHeaders(List.of(text("Trace-Id", "1"), text("trace-id", "2")));

        assertEquals(List.of(text("Trace-Id", "1")), headers.headers("Trace-Id"));
        assertEquals(List.of(text("trace-id", "2")), headers.headers("trace-id"));
        assertEquals(List.of(), headers.headers("TRACE-ID"));
        assertTrue(headers.lastHeader(" trace-id").isEmpty());
    }

    @Test
    @DisplayName("lastHeader returns the last match and is empty only when the key is absent")
    void lastHeader() {
        KafkaRecordHeaders headers = new KafkaRecordHeaders(List.of(
                text("a", "1"),
                text("a", "2"),
                text("nulled", "first"),
                text("nulled", null),
                text("empty", ""),
                new KafkaRecordHeader("binary", NOT_UTF8)));

        assertEquals(text("a", "2"), headers.lastHeader("a").orElseThrow());
        assertTrue(headers.lastHeader("absent").isEmpty());

        // The last match has a null value: the header is still returned, not the earlier one.
        KafkaRecordHeader nulled = headers.lastHeader("nulled").orElseThrow();
        assertNull(nulled.value());

        assertArrayEquals(new byte[0], headers.lastHeader("empty").orElseThrow().value());
        assertArrayEquals(NOT_UTF8, headers.lastHeader("binary").orElseThrow().value());
    }

    @Test
    @DisplayName("a null list and a null entry are rejected")
    void rejectsNulls() {
        assertThrows(NullPointerException.class, () -> new KafkaRecordHeaders(null));
        assertThrows(NullPointerException.class, () -> new KafkaRecordHeaders(Arrays.asList(text("a", "1"), null)));
    }

    @Test
    @DisplayName("the collection is a snapshot: later changes to the source list are not seen")
    void snapshotOfTheSourceList() {
        List<KafkaRecordHeader> source = new ArrayList<>(List.of(text("a", "1")));
        KafkaRecordHeaders headers = new KafkaRecordHeaders(source);

        source.add(text("b", "2"));
        source.set(0, text("a", "changed"));

        assertEquals(List.of(text("a", "1")), headers.entries());
        assertThrows(
                UnsupportedOperationException.class, () -> headers.entries().add(text("c", "3")));
        Iterator<KafkaRecordHeader> iterator = headers.iterator();
        iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
    }

    @Test
    @DisplayName("changing a source or returned value array does not change the collection")
    void snapshotOfTheValueArrays() {
        byte[] source = {1, 2, 3};
        KafkaRecordHeaders headers = new KafkaRecordHeaders(List.of(new KafkaRecordHeader("k", source)));

        source[0] = 9;
        headers.entries().get(0).value()[1] = 9;
        headers.lastHeader("k").orElseThrow().value()[2] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, headers.entries().get(0).value());
    }

    @Test
    @DisplayName("equality and hash code compare content and are sensitive to order")
    void equalityByContentAndOrder() {
        KafkaRecordHeaders headers = new KafkaRecordHeaders(List.of(text("a", "1"), text("b", "2")));

        assertEquals(headers, new KafkaRecordHeaders(List.of(text("a", "1"), text("b", "2"))));
        assertEquals(headers.hashCode(), new KafkaRecordHeaders(List.of(text("a", "1"), text("b", "2"))).hashCode());
        assertNotEquals(headers, new KafkaRecordHeaders(List.of(text("b", "2"), text("a", "1"))));
        assertNotEquals(headers, new KafkaRecordHeaders(List.of(text("a", "1"))));
        assertNotEquals(headers, new KafkaRecordHeaders(List.of(text("a", "1"), text("b", "2"), text("b", "2"))));
    }

    @Test
    @DisplayName("empty() is one shared instance with no entries")
    void emptyInstance() {
        assertSame(KafkaRecordHeaders.empty(), KafkaRecordHeaders.empty());
        assertEquals(List.of(), KafkaRecordHeaders.empty().entries());
        assertEquals(Map.of(), KafkaRecordHeaders.empty().asMap());
        assertFalse(KafkaRecordHeaders.empty().iterator().hasNext());
        assertEquals(KafkaRecordHeaders.empty(), new KafkaRecordHeaders(List.of()));
    }

    @Test
    @DisplayName("asMap keeps the last non-null value of a repeated key")
    void asMapCollapsesDuplicates() {
        KafkaRecordHeaders headers =
                new KafkaRecordHeaders(List.of(text("a", "1"), text("b", "2"), text("a", "3"), text("b", "4")));

        Map<String, String> expected = new HashMap<>();
        expected.put("a", "3");
        expected.put("b", "4");
        assertEquals(expected, headers.asMap());
    }

    @Test
    @DisplayName("asMap skips null values: a null neither appears nor replaces an earlier value")
    void asMapSkipsNullValues() {
        KafkaRecordHeaders headers = new KafkaRecordHeaders(
                List.of(text("only-null", null), text("a", "1"), text("a", null), text("empty", "")));

        Map<String, String> expected = new HashMap<>();
        expected.put("a", "1");
        expected.put("empty", "");
        assertEquals(expected, headers.asMap());
        assertFalse(headers.asMap().containsKey("only-null"));
    }

    @Test
    @DisplayName("asMap decodes malformed UTF-8 leniently, one replacement character per bad byte")
    void asMapReplacesMalformedInput() {
        KafkaRecordHeaders headers = new KafkaRecordHeaders(List.of(
                new KafkaRecordHeader("binary", NOT_UTF8),
                new KafkaRecordHeader("mixed", new byte[] {'o', 'k', (byte) 0xFF}),
                text("text", "ä")));

        Map<String, String> expected = new HashMap<>();
        expected.put("binary", REPLACEMENT + REPLACEMENT);
        expected.put("mixed", "ok" + REPLACEMENT);
        expected.put("text", "ä");
        assertEquals(expected, headers.asMap());
    }

    @Test
    @DisplayName("asMap keeps the only non-null value of a key whichever side the null is on")
    void asMapNullBeforeOrAfterAValue() {
        assertEquals(Map.of("a", "1"), new KafkaRecordHeaders(List.of(text("a", null), text("a", "1"))).asMap());
        assertEquals(Map.of("a", "1"), new KafkaRecordHeaders(List.of(text("a", "1"), text("a", null))).asMap());
    }

    @Test
    @DisplayName("lookups reject a null key")
    void lookupsRejectNullKey() {
        KafkaRecordHeaders headers = new KafkaRecordHeaders(List.of(text("a", "1")));

        assertThrows(NullPointerException.class, () -> headers.headers(null));
        assertThrows(NullPointerException.class, () -> headers.lastHeader(null));
    }

    @Test
    @DisplayName("toString shows keys and value lengths, never a header value")
    void toStringHidesTheValues() {
        String text = new KafkaRecordHeaders(
                        List.of(text("authorization", "s3cret"), new KafkaRecordHeader("bin", new byte[] {77, 78})))
                .toString();

        assertEquals(
                "KafkaRecordHeaders[entries=[KafkaRecordHeader[key=authorization, value=6 bytes],"
                        + " KafkaRecordHeader[key=bin, value=2 bytes]]]",
                text);
        assertFalse(text.contains("s3cret"));
        assertFalse(text.contains("77"));
        assertFalse(text.contains("MN"));
    }

    @Test
    @DisplayName("asMap is unmodifiable")
    void asMapIsUnmodifiable() {
        Map<String, String> map = new KafkaRecordHeaders(List.of(text("a", "1"))).asMap();

        assertThrows(UnsupportedOperationException.class, () -> map.put("b", "2"));
        assertThrows(UnsupportedOperationException.class, () -> map.remove("a"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> KafkaRecordHeaders.empty().asMap().put("b", "2"));
    }
}
