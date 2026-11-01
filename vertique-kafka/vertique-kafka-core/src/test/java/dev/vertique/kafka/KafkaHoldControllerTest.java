// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static dev.vertique.kafka.SettlementRig.P0;
import static dev.vertique.kafka.SettlementRig.P1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.kafka.KafkaHoldController.Reason;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that a partition fetches only while no reason holds it, that each reason is added and removed alone, and
 * that the pause and resume reach the client in the order the state changed, also when a call fails or the partition
 * changes hands meanwhile.
 */
class KafkaHoldControllerTest {

    private static KafkaHoldController controller(SettlementRig rig) {
        return new KafkaHoldController("test", rig.port);
    }

    private static SettlementRig rig() {
        return new SettlementRig(SettlementRig.settings(1, ErrorStrategy.SKIP, false));
    }

    @Test
    @DisplayName("a partition is paused on its first reason and resumed when its last reason is removed")
    void pausesOnFirstAndResumesOnLast() {
        SettlementRig rig = rig();
        KafkaHoldController holds = controller(rig);
        holds.assigned(P0);

        holds.hold(P0, Reason.RETENTION);
        holds.hold(P0, Reason.BACKOFF);
        assertTrue(rig.clientPaused(P0));
        assertEquals(1, rig.port.partitionCalls.size(), "one pause for two reasons");

        holds.release(P0, Reason.RETENTION);
        assertTrue(rig.clientPaused(P0), "the backoff still holds the partition");

        holds.release(P0, Reason.BACKOFF);
        assertFalse(rig.clientPaused(P0));
        assertEquals(2, rig.port.partitionCalls.size());
    }

    @Test
    @DisplayName("a reason is held once however often it is added, and one removal clears it")
    void reasonsAreNotCounted() {
        SettlementRig rig = rig();
        KafkaHoldController holds = controller(rig);
        holds.assigned(P0);

        holds.hold(P0, Reason.BACKOFF);
        holds.hold(P0, Reason.BACKOFF);
        holds.release(P0, Reason.BACKOFF);

        assertFalse(holds.isHeld(P0, Reason.BACKOFF));
        assertFalse(rig.clientPaused(P0));
    }

    @Test
    @DisplayName("a removal of a reason that is not held releases no other reason")
    void surplusRemovalIsIgnored() {
        SettlementRig rig = rig();
        KafkaHoldController holds = controller(rig);
        holds.assigned(P0);
        holds.hold(P0, Reason.RETENTION);

        holds.release(P0, Reason.BACKOFF);

        assertTrue(rig.clientPaused(P0));
        assertEquals(Set.of(Reason.RETENTION), holds.reasons(P0));
    }

    @Test
    @DisplayName("reasons are kept per partition")
    void perPartition() {
        SettlementRig rig = rig();
        KafkaHoldController holds = controller(rig);
        holds.assigned(P0);
        holds.assigned(P1);

        holds.hold(P0, Reason.BACKOFF);

        assertTrue(rig.clientPaused(P0));
        assertFalse(rig.clientPaused(P1));
    }

    @Test
    @DisplayName("a reason held everywhere also holds a partition assigned afterwards, and is applied to it")
    void heldEverywhereReachesNewAssignments() {
        SettlementRig rig = rig();
        KafkaHoldController holds = controller(rig);
        holds.assigned(P0);
        holds.holdAll(Reason.INTAKE);
        assertTrue(rig.clientPaused(P0));

        holds.assigned(P1);
        assertTrue(rig.clientPaused(P1));

        holds.releaseAll(Reason.INTAKE);
        assertFalse(rig.clientPaused(P0));
        assertFalse(rig.clientPaused(P1));
    }

    @Test
    @DisplayName("a partition that is assigned again starts unpaused in the client and is paused again if held")
    void reappliedOnAssignment() {
        SettlementRig rig = rig();
        KafkaHoldController holds = controller(rig);
        holds.assigned(P0);
        holds.hold(P0, Reason.SHUTDOWN);
        holds.holdAll(Reason.INTAKE);
        int calls = rig.port.partitionCalls.size();

        holds.revoked(P0);
        holds.assigned(P0);

        assertEquals(calls + 1, rig.port.partitionCalls.size(), "the client is told to pause the new assignment");
        assertTrue(rig.port.partitionCalls.get(calls).pause);
        assertEquals(Set.of(Reason.INTAKE), holds.reasons(P0), "only what is held everywhere carries over");
    }

    @Test
    @DisplayName("a call that fails is applied again")
    void failedCallIsApplied() {
        SettlementRig rig = rig();
        rig.port.autoComplete = false;
        KafkaHoldController holds = controller(rig);
        holds.assigned(P0);

        holds.hold(P0, Reason.RETENTION);
        rig.port.partitionCalls.get(0).result.fail("no current assignment");
        assertFalse(rig.clientPaused(P0));
        assertEquals(1, rig.pendingTimers().size());

        rig.pendingTimers().get(0).fire();
        assertEquals(2, rig.port.partitionCalls.size());
        rig.port.partitionCalls.get(1).result.complete();
        assertTrue(rig.clientPaused(P0));
    }

    @Test
    @DisplayName("a change while a call is pending is applied when the call ends, one call at a time")
    void changeDuringACall() {
        SettlementRig rig = rig();
        rig.port.autoComplete = false;
        KafkaHoldController holds = controller(rig);
        holds.assigned(P0);

        holds.hold(P0, Reason.RETENTION);
        holds.release(P0, Reason.RETENTION);
        assertEquals(1, rig.port.partitionCalls.size(), "the pause is still pending");

        rig.port.partitionCalls.get(0).result.complete();
        assertEquals(2, rig.port.partitionCalls.size());
        assertFalse(rig.port.partitionCalls.get(1).pause, "the resume follows the pause");
    }

    @Test
    @DisplayName("a call that ends after the partition was revoked changes nothing")
    void staleCallIsIgnored() {
        SettlementRig rig = rig();
        rig.port.autoComplete = false;
        KafkaHoldController holds = controller(rig);
        holds.assigned(P0);
        holds.hold(P0, Reason.RETENTION);
        SettlementRig.PartitionCall stale = rig.port.partitionCalls.get(0);

        holds.revoked(P0);
        holds.assigned(P0);
        stale.result.fail("revoked");

        assertEquals(List.of(), rig.pendingTimers(), "no retry for a call of an ended assignment");
        assertEquals(1, rig.port.partitionCalls.size());
        assertFalse(holds.isHeld(P0, Reason.RETENTION));
    }
}
