// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that each arm sets exactly the trigger state and configuration of its definition. */
class LoadArmTest {

    @Test
    @DisplayName("ON keeps both triggers and the shipped polling")
    void onKeepsBothTriggers() {
        assertTrue(LoadArm.ON.workflowTriggerEnabled());
        assertTrue(LoadArm.ON.outboxTriggerEnabled());
        assertOverlay(LoadArm.ON, 5_000L, "LISTEN_NOTIFY", 1_000L);
    }

    @Test
    @DisplayName("WF_OFF disables only the workflow trigger")
    void workflowOff() {
        assertFalse(LoadArm.WF_OFF.workflowTriggerEnabled());
        assertTrue(LoadArm.WF_OFF.outboxTriggerEnabled());
        assertOverlay(LoadArm.WF_OFF, 100L, "LISTEN_NOTIFY", 1_000L);
    }

    @Test
    @DisplayName("OUT_OFF disables only the outbox trigger")
    void outboxOff() {
        assertTrue(LoadArm.OUT_OFF.workflowTriggerEnabled());
        assertFalse(LoadArm.OUT_OFF.outboxTriggerEnabled());
        assertOverlay(LoadArm.OUT_OFF, 5_000L, "POLLING", 50L);
    }

    @Test
    @DisplayName("OFF disables both triggers")
    void bothOff() {
        assertFalse(LoadArm.OFF.workflowTriggerEnabled());
        assertFalse(LoadArm.OFF.outboxTriggerEnabled());
        assertOverlay(LoadArm.OFF, 100L, "POLLING", 50L);
    }

    @Test
    @DisplayName("ON_MATCHED keeps both triggers with the polling of OFF")
    void matched() {
        assertTrue(LoadArm.ON_MATCHED.workflowTriggerEnabled());
        assertTrue(LoadArm.ON_MATCHED.outboxTriggerEnabled());
        assertEquals(LoadArm.OFF.configOverlay(), LoadArm.ON_MATCHED.configOverlay());
    }

    @Test
    @DisplayName("trigger statements name the table and the trigger and follow the arm")
    void triggerStatements() {
        assertEquals(
                List.of(
                        "ALTER TABLE workflow_commands DISABLE TRIGGER trg_workflow_commands_notify",
                        "ALTER TABLE outbox ENABLE TRIGGER transactional_outbox_notify"),
                LoadArm.WF_OFF.triggerStatements());
        assertEquals(
                List.of(
                        "ALTER TABLE workflow_commands ENABLE TRIGGER trg_workflow_commands_notify",
                        "ALTER TABLE outbox DISABLE TRIGGER transactional_outbox_notify"),
                LoadArm.OUT_OFF.triggerStatements());
    }

    @Test
    @DisplayName("arm names parse case-insensitively with a hyphen or an underscore")
    void parsesNames() {
        assertEquals(LoadArm.WF_OFF, LoadArm.parse("WF-OFF"));
        assertEquals(LoadArm.WF_OFF, LoadArm.parse("wf_off"));
        assertEquals(LoadArm.ON_MATCHED, LoadArm.parse(" on-matched "));
        assertEquals(LoadArm.OFF, LoadArm.parse("off"));
    }

    @Test
    @DisplayName("an unknown or blank arm name is rejected and the known arms are listed")
    void rejectsUnknownNames() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> LoadArm.parse("half"));
        assertTrue(unknown.getMessage().contains("OUT_OFF"), unknown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> LoadArm.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> LoadArm.parse(null));
    }

    private static void assertOverlay(LoadArm arm, long executorPollMs, String strategy, long relayPollMs) {
        JsonObject overlay = arm.configOverlay();
        assertEquals(
                executorPollMs,
                overlay.getJsonObject("workflow").getJsonObject("executor").getLong("pollIntervalMs"));
        JsonObject relay = overlay.getJsonObject("inboxOutbox").getJsonObject("relay");
        assertEquals(strategy, relay.getString("strategy"));
        assertEquals(relayPollMs, relay.getLong("pollingIntervalMs"));
    }
}
