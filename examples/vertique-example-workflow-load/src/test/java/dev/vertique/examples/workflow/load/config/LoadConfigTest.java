// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that the harness settings are strict: a key the harness does not know fails, whatever its value. */
class LoadConfigTest {

    private final ConfigParser parser = new DefaultConfigParser(DefaultConfigMapper.lenient());

    @Test
    @DisplayName("an absent section yields the documented defaults")
    void absentSectionYieldsDefaults() {
        LoadConfig config = LoadConfig.parse(new JsonObject(), parser);

        assertEquals(20L, config.handlerDelayMs());
        assertEquals(1_000L, config.timerDelayMs());
        assertEquals("127.0.0.1", config.ingress().host());
        assertEquals(0, config.ingress().port());
        assertNull(config.ingress().portFile());
        assertEquals(LoadConfig.defaults(), config);
    }

    @Test
    @DisplayName("every setting is read, also when it arrives as a string")
    void readsEverySetting() {
        JsonObject section = new JsonObject()
                .put("handlerDelayMs", "0")
                .put("timerDelayMs", 250)
                .put(
                        "ingress",
                        new JsonObject()
                                .put("host", "127.0.0.2")
                                .put("port", 8123)
                                .put("portFile", "/tmp/n.port"));

        LoadConfig config = LoadConfig.parse(section, parser);

        assertEquals(0L, config.handlerDelayMs());
        assertEquals(250L, config.timerDelayMs());
        assertEquals("127.0.0.2", config.ingress().host());
        assertEquals(8123, config.ingress().port());
        assertEquals("/tmp/n.port", config.ingress().portFile());
    }

    @Test
    @DisplayName("an unknown key fails even when its intended value equals the default")
    void unknownKeyFailsEvenAtTheDefaultValue() {
        JsonObject typo = new JsonObject().put("handlerDelayMS", 20);

        ConfigurationException failure =
                assertThrows(ConfigurationException.class, () -> LoadConfig.parse(typo, parser));

        assertTrue(failure.getMessage().contains("load.handlerDelayMS"), failure.getMessage());
        assertTrue(failure.getMessage().contains("handlerDelayMs"), "the message lists the known settings");
    }

    @Test
    @DisplayName("an unknown ingress key fails and names its path")
    void unknownIngressKeyFails() {
        JsonObject typo = new JsonObject().put("ingress", new JsonObject().put("hostname", "127.0.0.1"));

        ConfigurationException failure =
                assertThrows(ConfigurationException.class, () -> LoadConfig.parse(typo, parser));

        assertTrue(failure.getMessage().contains("load.ingress.hostname"), failure.getMessage());
    }

    @Test
    @DisplayName("values outside their bounds fail with the key in the message")
    void outOfBoundsValuesFail() {
        assertBound("load.handlerDelayMs", new JsonObject().put("handlerDelayMs", -1));
        assertBound("load.handlerDelayMs", new JsonObject().put("handlerDelayMs", 10_001));
        assertBound("load.timerDelayMs", new JsonObject().put("timerDelayMs", 0));
        assertBound("load.ingress.port", new JsonObject().put("ingress", new JsonObject().put("port", 65_536)));
        assertBound("load.ingress.host", new JsonObject().put("ingress", new JsonObject().put("host", " ")));
        assertBound("load.ingress.portFile", new JsonObject().put("ingress", new JsonObject().put("portFile", "")));
    }

    @Test
    @DisplayName("a zero handler delay is accepted")
    void zeroHandlerDelayIsAccepted() {
        assertEquals(
                0L,
                LoadConfig.parse(new JsonObject().put("handlerDelayMs", 0), parser)
                        .handlerDelayMs());
    }

    private void assertBound(String key, JsonObject section) {
        ConfigurationException failure =
                assertThrows(ConfigurationException.class, () -> LoadConfig.parse(section, parser));
        assertTrue(failure.getMessage().contains(key), failure.getMessage());
    }
}
