// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.core.pagination.OffsetPageRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OffsetPageRequest} — page number, page size, sort parsing,
 * clamping behaviour, and Jackson {@code convertValue} compatibility (used by
 * {@code @BeanParam} extraction).
 */
class OffsetPageRequestTest {

    // --- page(defaultValue) ---

    @Test
    @DisplayName("Should return default when page param is null")
    void shouldReturnDefaultWhenPageIsNull() {
        OffsetPageRequest req = new OffsetPageRequest(null, null, null);
        assertEquals(0, req.page(0));
        assertEquals(1, req.page(1));
    }

    @Test
    @DisplayName("Should return page param when set to a positive value")
    void shouldReturnPageParamWhenPositive() {
        OffsetPageRequest req = new OffsetPageRequest(3, null, null);
        assertEquals(3, req.page(0));
    }

    @Test
    @DisplayName("Should clamp negative page to 0")
    void shouldClampNegativePageToZero() {
        OffsetPageRequest req = new OffsetPageRequest(-5, null, null);
        assertEquals(0, req.page(0));
    }

    // --- pageSize(defaultValue) ---

    @Test
    @DisplayName("Should return default when pageSize param is null")
    void shouldReturnDefaultWhenPageSizeIsNull() {
        OffsetPageRequest req = new OffsetPageRequest(null, null, null);
        assertEquals(20, req.pageSize(20));
    }

    @Test
    @DisplayName("Should return pageSize param when set to a positive value")
    void shouldReturnPageSizeWhenPositive() {
        OffsetPageRequest req = new OffsetPageRequest(null, 50, null);
        assertEquals(50, req.pageSize(20));
    }

    @Test
    @DisplayName("Should clamp zero pageSize to 1")
    void shouldClampZeroPageSizeToOne() {
        OffsetPageRequest req = new OffsetPageRequest(null, 0, null);
        assertEquals(1, req.pageSize(20));
    }

    @Test
    @DisplayName("Should clamp negative pageSize to 1")
    void shouldClampNegativePageSizeToOne() {
        OffsetPageRequest req = new OffsetPageRequest(null, -10, null);
        assertEquals(1, req.pageSize(20));
    }

    // --- pageSize(defaultValue, maxValue) ---

    @Test
    @DisplayName("Should return default when pageSize param is null (with max)")
    void shouldReturnDefaultWhenPageSizeNullWithMax() {
        OffsetPageRequest req = new OffsetPageRequest(null, null, null);
        assertEquals(20, req.pageSize(20, 100));
    }

    @Test
    @DisplayName("Should return pageSize when within range")
    void shouldReturnPageSizeWhenWithinRange() {
        OffsetPageRequest req = new OffsetPageRequest(null, 50, null);
        assertEquals(50, req.pageSize(20, 100));
    }

    @Test
    @DisplayName("Should clamp pageSize to maxValue when above max")
    void shouldClampPageSizeToMaxWhenAbove() {
        OffsetPageRequest req = new OffsetPageRequest(null, 200, null);
        assertEquals(100, req.pageSize(20, 100));
    }

    @Test
    @DisplayName("Should clamp zero pageSize to 1 (with max)")
    void shouldClampZeroPageSizeToOneWithMax() {
        OffsetPageRequest req = new OffsetPageRequest(null, 0, null);
        assertEquals(1, req.pageSize(20, 100));
    }

    @Test
    @DisplayName("Should clamp negative pageSize to 1 (with max)")
    void shouldClampNegativePageSizeToOneWithMax() {
        OffsetPageRequest req = new OffsetPageRequest(null, -5, null);
        assertEquals(1, req.pageSize(20, 100));
    }

    // --- sortOrders() ---

    @Test
    @DisplayName("Should return empty list when sort param is null")
    void shouldReturnEmptyListWhenSortNull() {
        OffsetPageRequest req = new OffsetPageRequest(null, null, null);
        assertTrue(req.sortOrders().isEmpty());
    }

    @Test
    @DisplayName("Should return empty list when sort param is blank")
    void shouldReturnEmptyListWhenSortBlank() {
        OffsetPageRequest req = new OffsetPageRequest(null, null, "   ");
        assertTrue(req.sortOrders().isEmpty());
    }

    @Test
    @DisplayName("Should parse single sort field with no direction (defaults to ASC)")
    void shouldParseSingleFieldWithNoDirection() {
        OffsetPageRequest req = new OffsetPageRequest(null, null, "name");
        List<OffsetPageRequest.SortOrder> orders = req.sortOrders();

        assertEquals(1, orders.size());
        assertEquals("name", orders.get(0).field());
        assertEquals(OffsetPageRequest.SortOrder.Direction.ASC, orders.get(0).direction());
    }

    @Test
    @DisplayName("Should parse single field with explicit asc direction")
    void shouldParseSingleFieldWithAscDirection() {
        OffsetPageRequest req = new OffsetPageRequest(null, null, "name,asc");
        List<OffsetPageRequest.SortOrder> orders = req.sortOrders();

        assertEquals(1, orders.size());
        assertEquals("name", orders.get(0).field());
        assertEquals(OffsetPageRequest.SortOrder.Direction.ASC, orders.get(0).direction());
    }

    @Test
    @DisplayName("Should parse single field with desc direction")
    void shouldParseSingleFieldWithDescDirection() {
        OffsetPageRequest req = new OffsetPageRequest(null, null, "createdAt,desc");
        List<OffsetPageRequest.SortOrder> orders = req.sortOrders();

        assertEquals(1, orders.size());
        assertEquals("createdAt", orders.get(0).field());
        assertEquals(OffsetPageRequest.SortOrder.Direction.DESC, orders.get(0).direction());
    }

    @Test
    @DisplayName("Should parse multiple sort fields")
    void shouldParseMultipleSortFields() {
        OffsetPageRequest req = new OffsetPageRequest(null, null, "name,asc,age,desc");
        List<OffsetPageRequest.SortOrder> orders = req.sortOrders();

        assertEquals(2, orders.size());
        assertEquals("name", orders.get(0).field());
        assertEquals(OffsetPageRequest.SortOrder.Direction.ASC, orders.get(0).direction());
        assertEquals("age", orders.get(1).field());
        assertEquals(OffsetPageRequest.SortOrder.Direction.DESC, orders.get(1).direction());
    }

    @Test
    @DisplayName("Should treat non-direction token as a second field name")
    void shouldTreatNonDirectionTokenAsFieldName() {
        // "name,email" — "email" is not a direction keyword so treated as a second field
        OffsetPageRequest req = new OffsetPageRequest(null, null, "name,email");
        List<OffsetPageRequest.SortOrder> orders = req.sortOrders();

        assertEquals(2, orders.size());
        assertEquals("name", orders.get(0).field());
        assertEquals("email", orders.get(1).field());
        assertEquals(OffsetPageRequest.SortOrder.Direction.ASC, orders.get(1).direction());
    }

    // --- Jackson convertValue compatibility (@BeanParam extraction path) ---

    @Test
    @DisplayName("Should populate via Jackson convertValue (mimics @BeanParam extraction)")
    void shouldPopulateViaJacksonConvertValue() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> values = Map.of("page", 2, "pageSize", 15, "sort", "name,asc");

        OffsetPageRequest req = mapper.convertValue(values, OffsetPageRequest.class);

        assertEquals(2, req.page(0));
        assertEquals(15, req.pageSize(20));
        assertEquals(1, req.sortOrders().size());
    }

    @Test
    @DisplayName("Should handle partial map via Jackson convertValue (page only)")
    void shouldHandlePartialMapViaConvertValue() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> values = Map.of("page", 3);

        OffsetPageRequest req = mapper.convertValue(values, OffsetPageRequest.class);

        assertEquals(3, req.page(0));
        assertEquals(20, req.pageSize(20));
        assertTrue(req.sortOrders().isEmpty());
    }

    @Test
    @DisplayName("Should handle empty map via Jackson convertValue")
    void shouldHandleEmptyMapViaConvertValue() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> values = Map.of();

        OffsetPageRequest req = mapper.convertValue(values, OffsetPageRequest.class);

        assertEquals(0, req.page(0));
        assertEquals(20, req.pageSize(20));
        assertTrue(req.sortOrders().isEmpty());
    }

    @Test
    @DisplayName("Should ignore unknown properties via Jackson convertValue")
    void shouldIgnoreUnknownPropertiesViaConvertValue() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> values = Map.of("page", 1, "size", 10, "unknownField", "ignored");

        assertDoesNotThrow(() -> mapper.convertValue(values, OffsetPageRequest.class));
    }
}
