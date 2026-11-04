// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ValidationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Pages")
class PagesTest {

    @Test
    @DisplayName("dead-letter page copies its items and is unmodifiable")
    void deadLetterPageCopies() {
        List<OutboxDeadLetterSummary> source = new ArrayList<>(List.of(Fixtures.summary()));
        OutboxDeadLetterPage page = new OutboxDeadLetterPage(source, Optional.empty());
        source.clear();
        assertEquals(1, page.items().size());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
        assertTrue(page.next().isEmpty());
    }

    @Test
    @DisplayName("dead-letter page rejects null list and null elements, reads null next as empty")
    void deadLetterPageValidation() {
        assertThrows(ValidationException.class, () -> new OutboxDeadLetterPage(null, Optional.empty()));
        assertThrows(
                ValidationException.class,
                () -> new OutboxDeadLetterPage(Arrays.asList((OutboxDeadLetterSummary) null), Optional.empty()));
        assertTrue(new OutboxDeadLetterPage(List.of(), Optional.empty()).next().isEmpty());
        OutboxDeadLetterKey key = new OutboxDeadLetterKey(Instant.EPOCH, 1L);
        assertEquals(Optional.of(key), new OutboxDeadLetterPage(List.of(), Optional.of(key)).next());
    }

    @Test
    @DisplayName("history page copies its items and is unmodifiable")
    void historyPageCopies() {
        List<OutboxAdminHistoryEntry> source = new ArrayList<>(List.of(Fixtures.historyEntry()));
        OutboxAdminHistoryPage page = new OutboxAdminHistoryPage(source, Optional.empty());
        source.clear();
        assertEquals(1, page.items().size());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
    }

    @Test
    @DisplayName("history page rejects null list and null elements, reads null next as empty")
    void historyPageValidation() {
        assertThrows(ValidationException.class, () -> new OutboxAdminHistoryPage(null, Optional.empty()));
        assertThrows(
                ValidationException.class,
                () -> new OutboxAdminHistoryPage(Arrays.asList((OutboxAdminHistoryEntry) null), Optional.empty()));
        assertTrue(
                new OutboxAdminHistoryPage(List.of(), Optional.empty()).next().isEmpty());
        OutboxAdminHistoryKey key = new OutboxAdminHistoryKey(Instant.EPOCH, UUID.randomUUID());
        assertEquals(Optional.of(key), new OutboxAdminHistoryPage(List.of(), Optional.of(key)).next());
    }
}
