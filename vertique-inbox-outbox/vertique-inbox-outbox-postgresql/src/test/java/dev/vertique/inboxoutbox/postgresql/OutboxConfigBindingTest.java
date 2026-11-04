// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.inboxoutbox.InboxOutboxCleanupConfig;
import dev.vertique.inboxoutbox.query.OutboxQueryConfig;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Proves how the module binds {@code inboxOutbox.query} and {@code inboxOutbox.cleanup}, including absent sections. */
@DisplayName("inbox/outbox configuration binding")
class OutboxConfigBindingTest {

    private static final ConfigParser PARSER = new DefaultConfigParser(DefaultConfigMapper.lenient());

    private static JsonObject inboxOutbox(String section, JsonObject values) {
        return new JsonObject().put("inboxOutbox", new JsonObject().put(section, values));
    }

    @Nested
    @DisplayName("OutboxQueryConfig")
    class Query {

        @Test
        @DisplayName("an absent inboxOutbox section yields the 1 MiB default")
        void absentInboxOutboxSection() {
            OutboxQueryConfig config =
                    TransactionalMessagingPostgresqlModule.outboxQueryConfig(new JsonObject(), PARSER);

            assertEquals(1_048_576, config.maxPayloadBytes());
        }

        @Test
        @DisplayName("an inboxOutbox section without a query section yields the default")
        void absentQuerySection() {
            JsonObject root = new JsonObject().put("inboxOutbox", new JsonObject().put("cleanup", new JsonObject()));

            assertEquals(
                    1_048_576,
                    TransactionalMessagingPostgresqlModule.outboxQueryConfig(root, PARSER)
                            .maxPayloadBytes());
        }

        @Test
        @DisplayName("an empty query section yields the default")
        void emptyQuerySection() {
            JsonObject root = inboxOutbox("query", new JsonObject());

            assertEquals(
                    1_048_576,
                    TransactionalMessagingPostgresqlModule.outboxQueryConfig(root, PARSER)
                            .maxPayloadBytes());
        }

        @Test
        @DisplayName("inboxOutbox.query.maxPayloadBytes overrides the default")
        void configuredKey() {
            JsonObject root = inboxOutbox("query", new JsonObject().put("maxPayloadBytes", 2048));

            assertEquals(
                    2048,
                    TransactionalMessagingPostgresqlModule.outboxQueryConfig(root, PARSER)
                            .maxPayloadBytes());
        }

        @Test
        @DisplayName("the bounds 1 and 16 MiB are accepted")
        void boundsAccepted() {
            assertEquals(
                    1,
                    TransactionalMessagingPostgresqlModule.outboxQueryConfig(
                                    inboxOutbox("query", new JsonObject().put("maxPayloadBytes", 1)), PARSER)
                            .maxPayloadBytes());
            assertEquals(
                    16_777_216,
                    TransactionalMessagingPostgresqlModule.outboxQueryConfig(
                                    inboxOutbox("query", new JsonObject().put("maxPayloadBytes", 16_777_216)), PARSER)
                            .maxPayloadBytes());
        }

        @Test
        @DisplayName("zero, a negative value and a value above 16 MiB are rejected")
        void outOfRangeRejected() {
            for (int value : new int[] {0, -1, 16_777_217}) {
                JsonObject root = inboxOutbox("query", new JsonObject().put("maxPayloadBytes", value));

                assertThrows(
                        RuntimeException.class,
                        () -> TransactionalMessagingPostgresqlModule.outboxQueryConfig(root, PARSER),
                        String.valueOf(value));
            }
        }
    }

    @Nested
    @DisplayName("InboxOutboxCleanupConfig")
    class Cleanup {

        @Test
        @DisplayName("an absent inboxOutbox section yields a discarded retention of seven days")
        void absentSection() {
            InboxOutboxCleanupConfig config =
                    TransactionalMessagingPostgresqlModule.inboxOutboxCleanupConfig(new JsonObject(), PARSER);

            assertEquals(7, config.discardedRetentionDays());
            assertEquals(7, config.publishedRetentionDays());
            assertEquals(30, config.deadLetterRetentionDays());
            assertEquals(30, config.inboxRetentionDays());
            assertEquals(1_000, config.cleanupBatchSize());
        }

        @Test
        @DisplayName("inboxOutbox.cleanup.discardedRetentionDays overrides only itself")
        void configuredKey() {
            JsonObject root = inboxOutbox("cleanup", new JsonObject().put("discardedRetentionDays", 3));

            InboxOutboxCleanupConfig config =
                    TransactionalMessagingPostgresqlModule.inboxOutboxCleanupConfig(root, PARSER);

            assertEquals(3, config.discardedRetentionDays());
            assertEquals(7, config.publishedRetentionDays());
            assertEquals(30, config.deadLetterRetentionDays());
            assertEquals(30, config.inboxRetentionDays());
        }

        @Test
        @DisplayName("the retention keys are independent of each other")
        void independentKeys() {
            JsonObject root = inboxOutbox(
                    "cleanup",
                    new JsonObject()
                            .put("publishedRetentionDays", 1)
                            .put("deadLetterRetentionDays", 2)
                            .put("discardedRetentionDays", 3)
                            .put("inboxRetentionDays", 4)
                            .put("cleanupBatchSize", 5));

            InboxOutboxCleanupConfig config =
                    TransactionalMessagingPostgresqlModule.inboxOutboxCleanupConfig(root, PARSER);

            assertEquals(1, config.publishedRetentionDays());
            assertEquals(2, config.deadLetterRetentionDays());
            assertEquals(3, config.discardedRetentionDays());
            assertEquals(4, config.inboxRetentionDays());
            assertEquals(5, config.cleanupBatchSize());
        }
    }
}
