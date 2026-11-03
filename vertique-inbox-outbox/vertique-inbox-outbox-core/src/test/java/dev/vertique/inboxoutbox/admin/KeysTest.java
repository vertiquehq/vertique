// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.exception.ValidationException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Keys")
class KeysTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("OutboxDeadLetterKey: entry id positive, time required")
    void deadLetterKey() {
        assertDoesNotThrow(() -> new OutboxDeadLetterKey(NOW, 1L));
        assertThrows(ValidationException.class, () -> new OutboxDeadLetterKey(NOW, 0L));
        assertThrows(ValidationException.class, () -> new OutboxDeadLetterKey(null, 1L));
        OutboxDeadLetterKey key = new OutboxDeadLetterKey(NOW, 4L);
        assertEquals(NOW, key.failedAt());
        assertEquals(4L, key.entryId());
    }

    @Test
    @DisplayName("OutboxAdminHistoryKey: both components required")
    void historyKey() {
        UUID id = UUID.randomUUID();
        assertDoesNotThrow(() -> new OutboxAdminHistoryKey(NOW, id));
        assertThrows(ValidationException.class, () -> new OutboxAdminHistoryKey(null, id));
        assertThrows(ValidationException.class, () -> new OutboxAdminHistoryKey(NOW, null));
        OutboxAdminHistoryKey key = new OutboxAdminHistoryKey(NOW, id);
        assertEquals(NOW, key.recordedAt());
        assertEquals(id, key.operationId());
    }
}
