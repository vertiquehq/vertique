// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.dagger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code RestCoreModule.jaxRsConfig} validates {@code jaxrs.fileContentVerifierDeadlineMs}
 * immediately after parsing: non-positive values fail naming the setting; a positive value and the
 * absent-key default are accepted.
 */
class FileContentVerifierDeadlineProviderTest {

    private static final String DEADLINE = "jaxrs.fileContentVerifierDeadlineMs";

    @Test
    @DisplayName("zero fileContentVerifierDeadlineMs fails naming the setting")
    void zeroDeadlineFailsAtProvider() {
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> RestCoreModule.jaxRsConfig(
                        new JsonObject("{\"jaxrs\":{\"fileContentVerifierDeadlineMs\":0}}"), parser()));
        assertTrue(failure.getMessage().contains(DEADLINE), failure.getMessage());
    }

    @Test
    @DisplayName("negative fileContentVerifierDeadlineMs fails naming the setting")
    void negativeDeadlineFailsAtProvider() {
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> RestCoreModule.jaxRsConfig(
                        new JsonObject("{\"jaxrs\":{\"fileContentVerifierDeadlineMs\":-1}}"), parser()));
        assertTrue(failure.getMessage().contains(DEADLINE), failure.getMessage());
    }

    @Test
    @DisplayName("a positive fileContentVerifierDeadlineMs is returned as configured")
    void positiveDeadlineIsHonored() {
        JaxRsConfig config = assertDoesNotThrow(() -> RestCoreModule.jaxRsConfig(
                new JsonObject("{\"jaxrs\":{\"fileContentVerifierDeadlineMs\":75}}"), parser()));
        assertEquals(75L, config.fileContentVerifierDeadlineMs());
    }

    @Test
    @DisplayName("an absent jaxrs section yields the 5000 ms default deadline")
    void absentSectionYieldsDefault() {
        JaxRsConfig config = assertDoesNotThrow(() -> RestCoreModule.jaxRsConfig(new JsonObject("{}"), parser()));
        assertEquals(5_000L, config.fileContentVerifierDeadlineMs());
    }

    private static ConfigParser parser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }
}
