// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.config.LoadArm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the command line of the admin entry point. */
class AdminArgumentsTest {

    @Test
    @DisplayName("migrate and triggers take no arm and an optional configuration")
    void commandsWithoutArm() {
        AdminArguments migrate = AdminArguments.parse(new String[] {"migrate"});
        assertEquals(AdminArguments.Command.MIGRATE, migrate.command());
        assertNull(migrate.arm());
        assertNull(migrate.conf());

        AdminArguments triggers = AdminArguments.parse(new String[] {"triggers", "--conf", "{\"db\":{}}"});
        assertEquals(AdminArguments.Command.TRIGGERS, triggers.command());
        assertEquals("{\"db\":{}}", triggers.conf());
    }

    @Test
    @DisplayName("arm takes an arm name, in any case, and an optional configuration")
    void armCommand() {
        AdminArguments arm = AdminArguments.parse(new String[] {"--conf=/tmp/c.json", "ARM", "wf-off"});

        assertEquals(AdminArguments.Command.ARM, arm.command());
        assertEquals(LoadArm.WF_OFF, arm.arm());
        assertEquals("/tmp/c.json", arm.conf());
    }

    @Test
    @DisplayName("arm-config takes an arm and no configuration")
    void armConfigCommand() {
        assertEquals(
                LoadArm.OFF,
                AdminArguments.parse(new String[] {"arm-config", "OFF"}).arm());
        assertThrows(
                IllegalArgumentException.class,
                () -> AdminArguments.parse(new String[] {"arm-config", "OFF", "--conf", "{}"}));
    }

    @Test
    @DisplayName("a wrong command line is rejected with the usage text")
    void wrongCommandLines() {
        String[][] wrong = {
            {},
            {"unknown"},
            {"migrate", "OFF"},
            {"arm"},
            {"arm", "OFF", "ON"},
            {"arm", "half"},
            {"migrate", "--conf"},
            {"migrate", "--force"},
        };
        for (String[] args : wrong) {
            IllegalArgumentException failure =
                    assertThrows(IllegalArgumentException.class, () -> AdminArguments.parse(args));
            assertTrue(
                    failure.getMessage().contains("usage:")
                            || failure.getMessage().contains("known arms"),
                    String.join(" ", args) + " -> " + failure.getMessage());
        }
    }
}
