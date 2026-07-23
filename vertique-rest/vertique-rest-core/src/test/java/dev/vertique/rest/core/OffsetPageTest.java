// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.core.pagination.OffsetPage;
import io.vertx.core.json.jackson.DatabindCodec;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OffsetPage} — the {@link OffsetPage#of} factory method, first/last
 * detection, totalPages calculation, and Jackson round-trip serialization.
 */
class OffsetPageTest {

    // --- of() factory ---

    @Test
    @DisplayName("Should populate items and totalItems from arguments")
    void shouldPopulateItemsAndTotalItems() {
        List<String> items = List.of("a", "b", "c");

        OffsetPage<String> page = OffsetPage.of(items, 100L, 0, 10);

        assertEquals(items, page.items());
        assertEquals(100L, page.totalItems());
    }

    @Test
    @DisplayName("Should compute totalPages via ceiling division")
    void shouldComputeTotalPagesWithCeiling() {
        OffsetPage<String> page = OffsetPage.of(List.of(), 25L, 0, 10);

        assertEquals(3, page.totalPages()); // ceil(25/10) = 3
    }

    @Test
    @DisplayName("Should compute totalPages as 1 when totalItems equals pageSize")
    void shouldComputeOneTotalPageWhenExact() {
        OffsetPage<String> page = OffsetPage.of(List.of(), 10L, 0, 10);

        assertEquals(1, page.totalPages());
    }

    @Test
    @DisplayName("Should compute totalPages as 0 when totalItems is 0")
    void shouldComputeZeroTotalPagesWhenEmpty() {
        OffsetPage<String> page = OffsetPage.of(List.of(), 0L, 0, 10);

        assertEquals(0, page.totalPages());
    }

    @Test
    @DisplayName("Should reflect page number and pageSize from arguments")
    void shouldReflectPageNumberAndPageSize() {
        OffsetPage<String> page = OffsetPage.of(List.of(), 100L, 2, 15);

        assertEquals(2, page.page());
        assertEquals(15, page.pageSize());
    }

    // --- first / last ---

    @Test
    @DisplayName("Should mark first=true for page 0")
    void shouldMarkFirstForPageZero() {
        OffsetPage<String> page = OffsetPage.of(List.of(), 50L, 0, 10);

        assertTrue(page.first());
        assertFalse(page.last());
    }

    @Test
    @DisplayName("Should mark last=true for the final page")
    void shouldMarkLastForFinalPage() {
        // 50 elements / 10 per page = 5 pages; last page is index 4
        OffsetPage<String> page = OffsetPage.of(List.of(), 50L, 4, 10);

        assertFalse(page.first());
        assertTrue(page.last());
    }

    @Test
    @DisplayName("Should mark both first=true and last=true when only one page")
    void shouldMarkBothFirstAndLastForSinglePage() {
        OffsetPage<String> page = OffsetPage.of(List.of(), 5L, 0, 10);

        assertTrue(page.first());
        assertTrue(page.last());
    }

    @Test
    @DisplayName("Should mark last=true when page number exceeds total pages")
    void shouldMarkLastWhenPageExceedsTotalPages() {
        // Requesting page 99 when only 3 pages exist
        OffsetPage<String> page = OffsetPage.of(List.of(), 25L, 99, 10);

        assertTrue(page.last());
    }

    @Test
    @DisplayName("Should mark neither first nor last for a middle page")
    void shouldMarkNeitherFirstNorLastForMiddlePage() {
        OffsetPage<String> page = OffsetPage.of(List.of(), 50L, 2, 10);

        assertFalse(page.first());
        assertFalse(page.last());
    }

    // --- Defensive copy ---

    @Test
    @DisplayName("Should make a defensive copy of items list")
    void shouldDefensivelyCopyItems() {
        List<String> mutable = new java.util.ArrayList<>(List.of("a", "b"));
        OffsetPage<String> page = OffsetPage.of(mutable, 100L, 0, 10);
        mutable.add("c");

        assertEquals(2, page.items().size(), "Items list should not reflect mutations after construction");
    }

    // --- Direct construction ---

    @Test
    @DisplayName("Should construct OffsetPage directly")
    void shouldConstructOffsetPageDirectly() {
        OffsetPage<String> page = new OffsetPage<>(List.of("x"), 1L, 1, 0, 10, true, true);

        assertEquals(List.of("x"), page.items());
        assertEquals(1L, page.totalItems());
        assertTrue(page.first());
        assertTrue(page.last());
    }

    // --- Jackson round-trip ---

    @Test
    @DisplayName("Should serialize OffsetPage to JSON with correct field names")
    void shouldSerializeToJsonWithCorrectFieldNames() throws Exception {
        ObjectMapper mapper = DatabindCodec.mapper();
        OffsetPage<String> page = OffsetPage.of(List.of("a", "b"), 25L, 1, 10);

        String json = mapper.writeValueAsString(page);
        JsonNode node = mapper.readTree(json);

        assertTrue(node.has("items"), "JSON should contain 'items' field");
        assertTrue(node.has("totalItems"), "JSON should contain 'totalItems' field");
        assertTrue(node.has("totalPages"), "JSON should contain 'totalPages' field");
        assertTrue(node.has("page"), "JSON should contain 'page' field");
        assertTrue(node.has("pageSize"), "JSON should contain 'pageSize' field");
        assertTrue(node.has("first"), "JSON should contain 'first' field");
        assertTrue(node.has("last"), "JSON should contain 'last' field");

        assertEquals(2, node.get("items").size());
        assertEquals(25L, node.get("totalItems").longValue());
        assertEquals(3, node.get("totalPages").intValue()); // ceil(25/10)
        assertEquals(1, node.get("page").intValue());
        assertEquals(10, node.get("pageSize").intValue());
        assertFalse(node.get("first").booleanValue());
        assertFalse(node.get("last").booleanValue());
    }

    @Test
    @DisplayName("Should deserialize OffsetPage from JSON (round-trip)")
    void shouldDeserializeFromJson() throws Exception {
        ObjectMapper mapper = DatabindCodec.mapper();
        OffsetPage<String> original = OffsetPage.of(List.of("x", "y"), 10L, 0, 5);

        String json = mapper.writeValueAsString(original);

        OffsetPage<String> deserialized =
                mapper.readValue(json, mapper.getTypeFactory().constructParametricType(OffsetPage.class, String.class));

        assertEquals(original.items(), deserialized.items());
        assertEquals(original.totalItems(), deserialized.totalItems());
        assertEquals(original.totalPages(), deserialized.totalPages());
        assertEquals(original.page(), deserialized.page());
        assertEquals(original.pageSize(), deserialized.pageSize());
        assertEquals(original.first(), deserialized.first());
        assertEquals(original.last(), deserialized.last());
    }
}
