// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link InboxOutboxCleanupConfig}, in particular the discarded retention. */
@DisplayName("InboxOutboxCleanupConfig")
class InboxOutboxCleanupConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("the defaults are 7, 30, 7 and 30 days with a batch of 1000")
    void defaults() throws Exception {
        for (InboxOutboxCleanupConfig config : List.of(
                InboxOutboxCleanupConfig.builder().build(), mapper.readValue("{}", InboxOutboxCleanupConfig.class))) {
            assertEquals(7, config.publishedRetentionDays());
            assertEquals(30, config.deadLetterRetentionDays());
            assertEquals(7, config.discardedRetentionDays());
            assertEquals(30, config.inboxRetentionDays());
            assertEquals(1_000, config.cleanupBatchSize());
        }
    }

    @Test
    @DisplayName("discardedRetentionDays is read independently of the other retention windows")
    void discardedRetentionDaysConfigured() throws Exception {
        InboxOutboxCleanupConfig config =
                mapper.readValue("{\"discardedRetentionDays\": 3}", InboxOutboxCleanupConfig.class);
        InboxOutboxCleanupConfig built =
                InboxOutboxCleanupConfig.builder().discardedRetentionDays(11).build();

        assertEquals(3, config.discardedRetentionDays());
        assertEquals(7, config.publishedRetentionDays());
        assertEquals(30, config.deadLetterRetentionDays());
        assertEquals(11, built.discardedRetentionDays());
    }

    @Test
    @DisplayName("the outbox entry states end with DISCARDED, after DEAD_LETTER")
    void discardedState() {
        assertEquals(
                List.of(
                        OutboxEntryState.PENDING,
                        OutboxEntryState.PROCESSING,
                        OutboxEntryState.PUBLISHED,
                        OutboxEntryState.DEAD_LETTER,
                        OutboxEntryState.DISCARDED),
                List.of(OutboxEntryState.values()));
        assertEquals(OutboxEntryState.DISCARDED, OutboxEntryState.valueOf("DISCARDED"));
    }
}
