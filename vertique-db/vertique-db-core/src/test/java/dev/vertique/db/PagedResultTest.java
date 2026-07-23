// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.db.query.PagedResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class PagedResultTest {

    @Test
    void empty_returnsEmptyResult() {
        PagedResult<String> result = PagedResult.empty();
        assertTrue(result.items().isEmpty());
        assertNull(result.nextCursorToken());
        assertNull(result.previousCursorToken());
        assertFalse(result.hasMore());
        assertFalse(result.hasPrevious());
        assertEquals(0, result.size());
    }

    @Test
    void hasMore_trueWhenNextTokenPresent() {
        var result = new PagedResult<>(List.of("a"), "next-token", null);
        assertTrue(result.hasMore());
    }

    @Test
    void hasMore_falseWhenNextTokenNull() {
        var result = new PagedResult<>(List.of("a"), null, null);
        assertFalse(result.hasMore());
    }

    @Test
    void hasPrevious_trueWhenPrevTokenPresent() {
        var result = new PagedResult<>(List.of("a"), null, "prev-token");
        assertTrue(result.hasPrevious());
    }

    @Test
    void hasPrevious_falseWhenPrevTokenNull() {
        var result = new PagedResult<>(List.of("a"), null, null);
        assertFalse(result.hasPrevious());
    }

    @Test
    void size_returnsItemCount() {
        var result = new PagedResult<>(List.of("a", "b", "c"), null, null);
        assertEquals(3, result.size());
    }
}
