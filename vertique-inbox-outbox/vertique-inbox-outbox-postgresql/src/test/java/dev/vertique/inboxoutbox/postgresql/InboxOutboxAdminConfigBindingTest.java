// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.inboxoutbox.admin.InboxOutboxAdminConfig;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proves how the module binds {@code inboxOutbox.admin}, including the defaults of an absent section. */
@DisplayName("InboxOutboxAdminConfig binding")
class InboxOutboxAdminConfigBindingTest {

    private static final ConfigParser PARSER = new DefaultConfigParser(DefaultConfigMapper.lenient());

    @Test
    @DisplayName("an absent inboxOutbox section yields the defaults")
    void absentInboxOutboxSection() {
        InboxOutboxAdminConfig config =
                TransactionalMessagingPostgresqlModule.inboxOutboxAdminConfig(new JsonObject(), PARSER);

        assertEquals(90, config.operationRetentionDays());
        assertEquals(1_048_576, config.maxContentBytes());
    }

    @Test
    @DisplayName("an inboxOutbox section without an admin section yields the defaults")
    void absentAdminSection() {
        JsonObject root = new JsonObject().put("inboxOutbox", new JsonObject().put("cleanup", new JsonObject()));

        InboxOutboxAdminConfig config = TransactionalMessagingPostgresqlModule.inboxOutboxAdminConfig(root, PARSER);

        assertEquals(90, config.operationRetentionDays());
        assertEquals(1_048_576, config.maxContentBytes());
    }

    @Test
    @DisplayName("configured keys override the defaults independently")
    void configuredKeys() {
        JsonObject root = new JsonObject()
                .put("inboxOutbox", new JsonObject().put("admin", new JsonObject().put("operationRetentionDays", 14)));

        InboxOutboxAdminConfig config = TransactionalMessagingPostgresqlModule.inboxOutboxAdminConfig(root, PARSER);

        assertEquals(14, config.operationRetentionDays());
        assertEquals(1_048_576, config.maxContentBytes());
    }

    @Test
    @DisplayName("a non-positive value is rejected")
    void nonPositiveRejected() {
        JsonObject root = new JsonObject()
                .put("inboxOutbox", new JsonObject().put("admin", new JsonObject().put("maxContentBytes", 0)));

        assertThrows(
                RuntimeException.class,
                () -> TransactionalMessagingPostgresqlModule.inboxOutboxAdminConfig(root, PARSER));
    }
}
