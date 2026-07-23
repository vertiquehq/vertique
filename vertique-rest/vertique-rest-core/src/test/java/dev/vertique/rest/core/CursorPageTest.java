// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.core.pagination.CursorCodec;
import dev.vertique.rest.core.pagination.CursorPage;
import io.vertx.core.json.jackson.DatabindCodec;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CursorPage} — factory methods, cursor encoding, and navigation flags.
 */
class CursorPageTest {

    private static final CursorCodec UPPER_CODEC = new CursorCodec() {
        @Override
        public String encode(String raw) {
            return raw.toUpperCase();
        }

        @Override
        public String decode(String token) {
            return token.toLowerCase();
        }
    };

    // --- of(items, next, prev, codec) ---

    @Test
    @DisplayName("Should encode next and previous cursors via codec")
    void shouldEncodeCursorsViaCodec() {
        CursorPage<String> page = CursorPage.of(List.of("a", "b"), "next-raw", "prev-raw", UPPER_CODEC);

        assertEquals("NEXT-RAW", page.nextCursor());
        assertEquals("PREV-RAW", page.previousCursor());
        assertEquals(List.of("a", "b"), page.items());
    }

    @Test
    @DisplayName("Should set nextCursor to null when nextRawCursor is null")
    void shouldSetNextCursorNullWhenAbsent() {
        CursorPage<String> page = CursorPage.of(List.of("a"), null, "prev-raw", UPPER_CODEC);

        assertNull(page.nextCursor());
        assertEquals("PREV-RAW", page.previousCursor());
    }

    @Test
    @DisplayName("Should set previousCursor to null when prevRawCursor is null")
    void shouldSetPreviousCursorNullWhenAbsent() {
        CursorPage<String> page = CursorPage.of(List.of("a"), "next-raw", null, UPPER_CODEC);

        assertEquals("NEXT-RAW", page.nextCursor());
        assertNull(page.previousCursor());
    }

    @Test
    @DisplayName("Should set both cursors to null on single-page result")
    void shouldSetBothCursorsNullForSinglePage() {
        CursorPage<String> page = CursorPage.of(List.of("a"), null, null, UPPER_CODEC);

        assertNull(page.nextCursor());
        assertNull(page.previousCursor());
    }

    @Test
    @DisplayName("Should make a defensive copy of items")
    void shouldDefensivelyCopyItems() {
        List<String> mutable = new java.util.ArrayList<>(List.of("a", "b"));
        CursorPage<String> page = CursorPage.of(mutable, null, null, UPPER_CODEC);
        mutable.add("c");

        assertEquals(2, page.items().size(), "Items list should not reflect mutations after construction");
    }

    // --- hasMore / hasPrevious ---

    @Test
    @DisplayName("hasMore() should return true when nextCursor is non-null")
    void hasMoreShouldBeTrueWhenNextCursorPresent() {
        CursorPage<String> page = CursorPage.of(List.of(), "next", null, UPPER_CODEC);
        assertTrue(page.hasMore());
    }

    @Test
    @DisplayName("hasMore() should return false when nextCursor is null")
    void hasMoreShouldBeFalseWhenNextCursorAbsent() {
        CursorPage<String> page = CursorPage.of(List.of(), null, null, UPPER_CODEC);
        assertFalse(page.hasMore());
    }

    @Test
    @DisplayName("hasPrevious() should return true when previousCursor is non-null")
    void hasPreviousShouldBeTrueWhenPreviousCursorPresent() {
        CursorPage<String> page = CursorPage.of(List.of(), null, "prev", UPPER_CODEC);
        assertTrue(page.hasPrevious());
    }

    @Test
    @DisplayName("hasPrevious() should return false when previousCursor is null")
    void hasPreviousShouldBeFalseWhenPreviousCursorAbsent() {
        CursorPage<String> page = CursorPage.of(List.of(), null, null, UPPER_CODEC);
        assertFalse(page.hasPrevious());
    }

    // --- convenience of(items, next, prev) — uses PlainCursorCodec ---

    @Test
    @DisplayName("Convenience of() should pass cursors through unchanged (PlainCursorCodec)")
    void convenienceOfShouldPassCursorsThroughUnchanged() {
        CursorPage<String> page = CursorPage.of(List.of("x"), "next-raw", "prev-raw");

        assertEquals("next-raw", page.nextCursor());
        assertEquals("prev-raw", page.previousCursor());
    }

    // --- Jackson round-trip ---

    @Test
    @DisplayName("Should serialize CursorPage with cursors to JSON with correct field names")
    void shouldSerializeWithCursorsToJson() throws Exception {
        ObjectMapper mapper = DatabindCodec.mapper();
        CursorPage<String> page = CursorPage.of(List.of("a", "b"), "next-raw", "prev-raw");

        String json = mapper.writeValueAsString(page);
        JsonNode node = mapper.readTree(json);

        assertTrue(node.has("items"), "JSON should contain 'items' field");
        assertTrue(node.has("nextCursor"), "JSON should contain 'nextCursor' field");
        assertTrue(node.has("previousCursor"), "JSON should contain 'previousCursor' field");
        assertEquals(2, node.get("items").size());
        assertEquals("next-raw", node.get("nextCursor").textValue());
        assertEquals("prev-raw", node.get("previousCursor").textValue());
    }

    @Test
    @DisplayName("Should omit null cursor fields when serializing to JSON")
    void shouldOmitNullCursorFieldsInJson() throws Exception {
        ObjectMapper mapper = DatabindCodec.mapper();
        CursorPage<String> page = CursorPage.of(List.of("a"), null, null);

        String json = mapper.writeValueAsString(page);
        JsonNode node = mapper.readTree(json);

        assertTrue(node.has("items"), "JSON should contain 'items' field");
        assertFalse(node.has("nextCursor"), "JSON should omit null 'nextCursor'");
        assertFalse(node.has("previousCursor"), "JSON should omit null 'previousCursor'");
    }

    @Test
    @DisplayName("Should not include hasMore and hasPrevious in JSON output")
    void shouldNotIncludeHasMoreAndHasPreviousInJson() throws Exception {
        ObjectMapper mapper = DatabindCodec.mapper();
        CursorPage<String> page = CursorPage.of(List.of("a"), "next", "prev");

        String json = mapper.writeValueAsString(page);
        JsonNode node = mapper.readTree(json);

        assertFalse(node.has("hasMore"), "JSON should not contain 'hasMore' field");
        assertFalse(node.has("hasPrevious"), "JSON should not contain 'hasPrevious' field");
    }

    @Test
    @DisplayName("Should deserialize CursorPage from JSON (round-trip)")
    void shouldDeserializeFromJson() throws Exception {
        ObjectMapper mapper = DatabindCodec.mapper();
        CursorPage<String> original = CursorPage.of(List.of("x", "y"), "next-tok", "prev-tok");

        String json = mapper.writeValueAsString(original);

        CursorPage<String> deserialized =
                mapper.readValue(json, mapper.getTypeFactory().constructParametricType(CursorPage.class, String.class));

        assertEquals(original.items(), deserialized.items());
        assertEquals(original.nextCursor(), deserialized.nextCursor());
        assertEquals(original.previousCursor(), deserialized.previousCursor());
    }
}
