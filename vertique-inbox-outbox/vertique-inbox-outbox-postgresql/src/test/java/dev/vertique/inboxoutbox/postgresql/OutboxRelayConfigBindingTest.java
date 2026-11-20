// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.exception.InboxOutboxConfigurationException;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The startup binding of {@code inboxOutbox.relay} fails on a configuration that cannot work. */
class OutboxRelayConfigBindingTest {

    private static OutboxRelayConfig bind(JsonObject config) {
        return TransactionalMessagingPostgresqlModule.outboxRelayConfig(
                config, new DefaultConfigParser(DefaultConfigMapper.lenient()));
    }

    private static JsonObject relay(int maxAttempts) {
        return new JsonObject()
                .put("inboxOutbox", new JsonObject().put("relay", new JsonObject().put("maxAttempts", maxAttempts)));
    }

    @Test
    @DisplayName("maxAttempts below 1 fails startup naming the key")
    void shouldFailStartupBelowOne() {
        for (int value : new int[] {0, -1}) {
            InboxOutboxConfigurationException failure =
                    assertThrows(InboxOutboxConfigurationException.class, () -> bind(relay(value)));
            assertEquals("inboxOutbox.relay.maxAttempts must be at least 1", failure.getMessage());
        }
    }

    @Test
    @DisplayName("maxAttempts of 1, and an absent section, bind")
    void shouldBindValidConfiguration() {
        assertEquals(1, bind(relay(1)).maxAttempts());
        assertEquals(20, bind(new JsonObject()).maxAttempts());
    }
}
