// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ValidationException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OutboxAdminHistoryQuery")
class OutboxAdminHistoryQueryTest {

    @Test
    @DisplayName("of(entryId) uses limit 50 and no position")
    void defaults() {
        OutboxAdminHistoryQuery query = OutboxAdminHistoryQuery.of(3L);
        assertEquals(3L, query.entryId());
        assertEquals(50, query.limit());
        assertTrue(query.after().isEmpty());
    }

    @Test
    @DisplayName("limit accepts 1 and 200, rejects 0 and 201")
    void limitBounds() {
        assertDoesNotThrow(() -> new OutboxAdminHistoryQuery(1L, 1, Optional.empty()));
        assertDoesNotThrow(() -> new OutboxAdminHistoryQuery(1L, 200, Optional.empty()));
        assertThrows(ValidationException.class, () -> new OutboxAdminHistoryQuery(1L, 0, Optional.empty()));
        assertThrows(ValidationException.class, () -> new OutboxAdminHistoryQuery(1L, 201, Optional.empty()));
    }

    @Test
    @DisplayName("entry id must be positive")
    void entryIdBounds() {
        assertDoesNotThrow(() -> OutboxAdminHistoryQuery.of(1L));
        assertThrows(ValidationException.class, () -> OutboxAdminHistoryQuery.of(0L));
        assertThrows(ValidationException.class, () -> OutboxAdminHistoryQuery.of(-1L));
    }

    @Test
    @DisplayName("a null position reads as empty and a given one is kept")
    void after() {
        assertTrue(new OutboxAdminHistoryQuery(1L, 10, Optional.empty()).after().isEmpty());
        OutboxAdminHistoryKey key = new OutboxAdminHistoryKey(Instant.EPOCH, UUID.randomUUID());
        assertEquals(Optional.of(key), new OutboxAdminHistoryQuery(1L, 10, Optional.of(key)).after());
    }
}
