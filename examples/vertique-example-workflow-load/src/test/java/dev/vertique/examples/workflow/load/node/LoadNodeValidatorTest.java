// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.node;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.db.flyway.FlywayMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the two conditions a load node refuses to start without. */
class LoadNodeValidatorTest {

    @Test
    @DisplayName("a node with migrations disabled and a named connection starts")
    void validConfigurationStarts() {
        assertDoesNotThrow(
                () -> new LoadNodeValidator(TestConfigs.db("load-node-3"), TestConfigs.flyway(FlywayMode.DISABLED)));
    }

    @Test
    @DisplayName("a node that would migrate is refused")
    void migratingNodeIsRefused() {
        for (FlywayMode mode : new FlywayMode[] {FlywayMode.MIGRATE, FlywayMode.VALIDATE}) {
            ConfigurationException failure = assertThrows(
                    ConfigurationException.class,
                    () -> new LoadNodeValidator(TestConfigs.db("load-node-3"), TestConfigs.flyway(mode)));
            assertTrue(failure.getMessage().contains("flyway.mode"), failure.getMessage());
        }
    }

    @Test
    @DisplayName("a node without an application name is refused")
    void unnamedNodeIsRefused() {
        for (String name : new String[] {null, "", "  "}) {
            ConfigurationException failure = assertThrows(
                    ConfigurationException.class,
                    () -> new LoadNodeValidator(TestConfigs.db(name), TestConfigs.flyway(FlywayMode.DISABLED)));
            assertTrue(failure.getMessage().contains("application_name"), failure.getMessage());
        }
    }
}
