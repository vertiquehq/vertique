// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.exception.ValidationException;
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
    @DisplayName("the discarded retention limit is 3650 days")
    void limitConstant() {
        assertEquals(3650, InboxOutboxCleanupConfig.MAX_DISCARDED_RETENTION_DAYS);
    }

    @Test
    @DisplayName("the builder accepts 1 and 3650 days and rejects 0, negatives and 3651")
    void builderBounds() {
        assertEquals(
                1,
                InboxOutboxCleanupConfig.builder()
                        .discardedRetentionDays(1)
                        .build()
                        .discardedRetentionDays());
        assertEquals(
                3650,
                InboxOutboxCleanupConfig.builder()
                        .discardedRetentionDays(3650)
                        .build()
                        .discardedRetentionDays());
        for (int value : new int[] {0, -1, Integer.MIN_VALUE, 3651, Integer.MAX_VALUE}) {
            ValidationException failure = assertThrows(
                    ValidationException.class,
                    () -> InboxOutboxCleanupConfig.builder()
                            .discardedRetentionDays(value)
                            .build(),
                    String.valueOf(value));

            assertEquals("discardedRetentionDays must be between 1 and 3650", failure.getMessage());
        }
    }

    @Test
    @DisplayName("Jackson accepts 1 and 3650 and rejects out-of-range values with the validation failure as the cause")
    void deserializationBounds() throws Exception {
        assertEquals(
                1,
                mapper.readValue("{\"discardedRetentionDays\": 1}", InboxOutboxCleanupConfig.class)
                        .discardedRetentionDays());
        assertEquals(
                3650,
                mapper.readValue("{\"discardedRetentionDays\": 3650}", InboxOutboxCleanupConfig.class)
                        .discardedRetentionDays());
        for (String json : new String[] {
            "{\"discardedRetentionDays\": 0}", "{\"discardedRetentionDays\": -1}", "{\"discardedRetentionDays\": 3651}"
        }) {
            Exception failure =
                    assertThrows(Exception.class, () -> mapper.readValue(json, InboxOutboxCleanupConfig.class), json);
            Throwable root = failure;
            while (root.getCause() != null) {
                root = root.getCause();
            }

            assertInstanceOf(ValidationException.class, root, json);
            assertEquals("discardedRetentionDays must be between 1 and 3650", root.getMessage(), json);
        }
    }

    @Test
    @DisplayName("unknown properties are still ignored, so a misspelled key keeps the default")
    void unknownPropertiesIgnored() throws Exception {
        InboxOutboxCleanupConfig config = mapper.readValue(
                "{\"discardedRetentionDay\": 0, \"somethingElse\": true}", InboxOutboxCleanupConfig.class);

        assertEquals(7, config.discardedRetentionDays());
    }

    @Test
    @DisplayName("the other fields are not validated and are passed through, whatever their value")
    void otherFieldsPassThrough() throws Exception {
        InboxOutboxCleanupConfig built = InboxOutboxCleanupConfig.builder()
                .publishedRetentionDays(0)
                .deadLetterRetentionDays(-5)
                .inboxRetentionDays(100_000)
                .cleanupBatchSize(0)
                .build();
        InboxOutboxCleanupConfig read = mapper.readValue(
                "{\"publishedRetentionDays\": 0, \"deadLetterRetentionDays\": -5,"
                        + " \"inboxRetentionDays\": 100000, \"cleanupBatchSize\": 0}",
                InboxOutboxCleanupConfig.class);

        for (InboxOutboxCleanupConfig config : List.of(built, read)) {
            assertEquals(0, config.publishedRetentionDays());
            assertEquals(-5, config.deadLetterRetentionDays());
            assertEquals(7, config.discardedRetentionDays());
            assertEquals(100_000, config.inboxRetentionDays());
            assertEquals(0, config.cleanupBatchSize());
        }
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
