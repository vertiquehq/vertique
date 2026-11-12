// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the input half of the secret rule: {@link ConfigParser} refuses to bind a secret field whose
 * value is the literal mask placeholder {@code "***"}, at any depth and in keyed entries, naming the
 * path and never a value, while the same literal under a non-secret key still parses.
 */
@DisplayName("DefaultConfigParser mask placeholder rejection")
class DefaultConfigParserMaskPlaceholderTest {

    record Credentials(String user, String password) {}

    record Server(String host, Credentials credentials, JsonObject properties, List<Credentials> fallbacks) {}

    record Consumer(String name, String saslPassword, int threads) {}

    private final ConfigParser parser = new DefaultConfigParser(DefaultConfigMapper.lenient());

    @Test
    @DisplayName("a top-level secret equal to *** is rejected with the path in the message")
    void topLevelPlaceholderRejected() {
        JsonObject section = new JsonObject().put("user", "app").put("password", "***");

        ConfigurationException failure =
                assertThrows(ConfigurationException.class, () -> parser.parse(section, Credentials.class));

        assertTrue(failure.getMessage().contains("'password'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("***"), failure.getMessage());
        assertFalse(failure.getMessage().contains("app"), "the message must name no other value");
    }

    @Test
    @DisplayName("nested record fields, bag entries and array elements are scanned by dotted path")
    void nestedPlaceholdersRejected() {
        JsonObject inRecord = new JsonObject()
                .put("host", "h")
                .put("credentials", new JsonObject().put("user", "u").put("password", "***"));
        JsonObject inBag = new JsonObject()
                .put("host", "h")
                .put("properties", new JsonObject().put("nested", new JsonObject().put("api-key", "***")));
        JsonObject inArray = new JsonObject()
                .put("host", "h")
                .put(
                        "fallbacks",
                        new JsonArray().add(new JsonObject().put("user", "u").put("password", "***")));

        assertTrue(assertThrows(ConfigurationException.class, () -> parser.parse(inRecord, Server.class))
                .getMessage()
                .contains("'credentials.password'"));
        assertTrue(assertThrows(ConfigurationException.class, () -> parser.parse(inBag, Server.class))
                .getMessage()
                .contains("'properties.nested.api-key'"));
        assertTrue(assertThrows(ConfigurationException.class, () -> parser.parse(inArray, Server.class))
                .getMessage()
                .contains("'fallbacks.password'"));
    }

    @Test
    @DisplayName("*** under a non-secret key is an ordinary value")
    void placeholderUnderNonSecretKeyAccepted() {
        JsonObject section =
                new JsonObject().put("host", "***").put("properties", new JsonObject().put("region", "***"));

        Server parsed = parser.parse(section, Server.class);

        assertEquals("***", parsed.host());
        assertEquals("***", parsed.properties().getString("region"));
    }

    @Test
    @DisplayName("a real secret and a masked-looking but longer value both bind")
    void realSecretsBind() {
        Credentials parsed =
                parser.parse(new JsonObject().put("user", "app").put("password", "****"), Credentials.class);

        assertEquals("****", parsed.password());
        assertEquals(
                "real",
                parser.parse(new JsonObject().put("password", "real"), Credentials.class)
                        .password());
    }

    @Test
    @DisplayName("keyed entries are scanned relative to the entry and the entry is named")
    void keyedEntryPlaceholderRejected() {
        JsonObject section = new JsonObject()
                .put("orders", new JsonObject().put("saslPassword", "pw").put("threads", 1))
                .put("tokens-feed", new JsonObject().put("saslPassword", "***").put("threads", 1));

        ConfigurationException failure = assertThrows(
                ConfigurationException.class, () -> parser.parseKeyedObject(section, "name", Consumer.class));

        assertTrue(failure.getMessage().contains("'saslPassword'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("'tokens-feed'"), failure.getMessage());
        assertFalse(failure.getMessage().contains("pw"), failure.getMessage());
    }

    @Test
    @DisplayName("keyed entries without a placeholder parse as before")
    void keyedEntriesWithoutPlaceholderBind() {
        JsonObject section = new JsonObject()
                .put("orders", new JsonObject().put("saslPassword", "pw").put("threads", 2));

        List<Consumer> parsed = parser.parseKeyedObject(section, "name", Consumer.class);

        assertEquals(1, parsed.size());
        assertEquals("orders", parsed.get(0).name());
        assertEquals("pw", parsed.get(0).saslPassword());
    }

    @Test
    @DisplayName("a dump produced by the config mapper cannot be parsed back")
    void roundTripRejected() throws Exception {
        var mapper = DefaultConfigMapper.lenient();
        JsonObject dump = new JsonObject(mapper.writeValueAsString(new Credentials("app", "live-secret")));
        assertEquals("***", dump.getString("password"));

        assertThrows(ConfigurationException.class, () -> parser.parse(dump, Credentials.class));
    }
}
