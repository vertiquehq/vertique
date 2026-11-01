// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static dev.vertique.kafka.SettlementRig.P0;
import static dev.vertique.kafka.SettlementRig.P1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.kafka.KafkaHoldController.Reason;
import io.vertx.kafka.client.common.TopicPartition;
import java.util.List;
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

    /** A new assignment of a partition, assigned to the controller. */
    private static KafkaPartitionLedger assign(KafkaHoldController holds, TopicPartition partition) {
        KafkaPartitionLedger ledger = new KafkaPartitionLedger(partition);
        holds.assigned(ledger);
        return ledger;
    }

    private static SettlementRig rig() {
        return new SettlementRig(SettlementRig.settings(1, ErrorStrategy.SKIP, false));
    }

    @Test
    @DisplayName("a partition is paused on its first reason and resumed when its last reason is removed")
    void pausesOnFirstAndResumesOnLast() {
        SettlementRig rig = rig();
        KafkaHoldController holds = controller(rig);
        KafkaPartitionLedger ledger = assign(holds, P0);
        int assignmentCalls = rig.port.partitionCalls.size();

        holds.hold(ledger, Reason.RETENTION);
        holds.hold(ledger, Reason.BACKOFF);
        assertTrue(rig.clientPaused(P0));
        assertEquals(assignmentCalls + 1, rig.port.partitionCalls.size(), "one pause for two reasons");

        holds.release(ledger, Reason.RETENTION);
        assertTrue(rig.clientPaused(P0), "the backoff still holds the partition");

        holds.release(ledger, Reason.BACKOFF);
        assertFalse(rig.clientPaused(P0));
        assertEquals(assignmentCalls + 2, rig.port.partitionCalls.size());
    }

    @Test
    @DisplayName("a reason is held once however often it is added, and one removal clears it")
    void reasonsAreNotCounted() {
        SettlementRig rig = rig();
        KafkaHoldController holds = controller(rig);
        KafkaPartitionLedger ledger = assign(holds, P0);

        holds.hold(ledger, Reason.BACKOFF);
        holds.hold(ledger, Reason.BACKOFF);
        holds.release(ledger, Reason.BACKOFF);

        assertFalse(rig.clientPaused(P0));
    }

    @Test
    @DisplayName("a removal of a reason that is not held releases no other reason")
    void surplusRemovalIsIgnored() {
        SettlementRig rig = rig();
        KafkaHoldController holds = controller(rig);
        KafkaPartitionLedger ledger = assign(holds, P0);
        holds.hold(ledger, Reason.RETENTION);

        holds.release(ledger, Reason.BACKOFF);

        assertTrue(rig.clientPaused(P0));
        holds.release(ledger, Reason.RETENTION);
        assertFalse(rig.clientPaused(P0), "the retention was the reason, and nothing else was held");
    }

    @Test
    @DisplayName("reasons are kept per partition")
    void perPartition() {
        SettlementRig rig = rig();
        KafkaHoldController holds = controller(rig);
        KafkaPartitionLedger first = assign(holds, P0);
        assign(holds, P1);

        holds.hold(first, Reason.BACKOFF);

        assertTrue(rig.clientPaused(P0));
        assertFalse(rig.clientPaused(P1));
    }

    @Test
    @DisplayName("a reason held everywhere also holds a partition assigned afterwards, and is applied to it")
    void heldEverywhereReachesNewAssignments() {
        SettlementRig rig = rig();
        KafkaHoldController holds = controller(rig);
        KafkaPartitionLedger first = assign(holds, P0);
        holds.holdAll(Reason.INTAKE, List.of(first));
        assertTrue(rig.clientPaused(P0));

        KafkaPartitionLedger second = assign(holds, P1);
        assertTrue(rig.clientPaused(P1));

        holds.releaseAll(Reason.INTAKE, List.of(first, second));
        assertFalse(rig.clientPaused(P0));
        assertFalse(rig.clientPaused(P1));
    }

    @Test
    @DisplayName("a partition that is assigned again starts unpaused in the client and is paused again if held")
    void reappliedOnAssignment() {
        SettlementRig rig = rig();
        KafkaHoldController holds = controller(rig);
        KafkaPartitionLedger old = assign(holds, P0);
        holds.hold(old, Reason.SHUTDOWN);
        holds.holdAll(Reason.INTAKE, List.of(old));
        int calls = rig.port.partitionCalls.size();

        old.end();
        KafkaPartitionLedger fresh = assign(holds, P0);

        assertEquals(calls + 1, rig.port.partitionCalls.size(), "the client is told to pause the new assignment");
        assertTrue(rig.port.partitionCalls.get(calls).pause);
        holds.releaseAll(Reason.INTAKE, List.of(fresh));
        assertFalse(rig.clientPaused(P0), "only what is held everywhere carries over");
    }

    @Test
    @DisplayName("a call that fails is applied again")
    void failedCallIsApplied() {
        SettlementRig rig = rig();
        rig.port.autoComplete = false;
        KafkaHoldController holds = controller(rig);
        KafkaPartitionLedger ledger = assign(holds, P0);
        rig.port.partitionCalls.get(0).result.complete();
        int calls = rig.port.partitionCalls.size();

        holds.hold(ledger, Reason.RETENTION);
        rig.port.partitionCalls.get(calls).result.fail("no current assignment");
        assertFalse(rig.clientPaused(P0));
        assertEquals(1, rig.pendingTimers().size());

        rig.pendingTimers().get(0).fire();
        assertEquals(calls + 2, rig.port.partitionCalls.size());
        rig.port.partitionCalls.get(calls + 1).result.complete();
        assertTrue(rig.clientPaused(P0));
    }

    @Test
    @DisplayName("a change while a call is pending is applied when the call ends, one call at a time")
    void changeDuringACall() {
        SettlementRig rig = rig();
        rig.port.autoComplete = false;
        KafkaHoldController holds = controller(rig);
        KafkaPartitionLedger ledger = assign(holds, P0);
        rig.port.partitionCalls.get(0).result.complete();
        int calls = rig.port.partitionCalls.size();

        holds.hold(ledger, Reason.RETENTION);
        holds.release(ledger, Reason.RETENTION);
        assertEquals(calls, rig.port.partitionCalls.size() - 1, "the pause is still pending");

        rig.port.partitionCalls.get(calls).result.complete();
        assertEquals(calls + 2, rig.port.partitionCalls.size());
        assertFalse(rig.port.partitionCalls.get(calls + 1).pause, "the resume follows the pause");
    }

    @Test
    @DisplayName("a call that ends after the partition was revoked changes nothing")
    void staleCallIsIgnored() {
        SettlementRig rig = rig();
        rig.port.autoComplete = false;
        KafkaHoldController holds = controller(rig);
        KafkaPartitionLedger old = assign(holds, P0);
        rig.port.partitionCalls.get(0).result.complete();
        holds.hold(old, Reason.RETENTION);
        SettlementRig.PartitionCall stale = rig.port.partitionCalls.get(1);

        old.end();
        assign(holds, P0);
        int calls = rig.port.partitionCalls.size();
        stale.result.fail("revoked");

        assertEquals(List.of(), rig.pendingTimers(), "no retry for a call of an ended assignment");
        assertEquals(calls, rig.port.partitionCalls.size());
    }

    @Test
    @DisplayName("a pause queued for the old assignment that succeeds after the partition was reassigned is undone")
    void stalePauseThatSucceedsIsResumed() {
        // Given a pause that was queued for the first assignment, ahead of the poll that revoked the partition and gave
        // it back
        SettlementRig rig = rig();
        rig.port.autoComplete = false;
        KafkaHoldController holds = controller(rig);
        KafkaPartitionLedger old = assign(holds, P0);
        rig.port.partitionCalls.get(0).result.complete();
        holds.hold(old, Reason.BACKOFF);
        SettlementRig.PartitionCall stalePause = rig.port.partitionCalls.get(1);
        assertTrue(stalePause.pause);
        old.end();

        // When the new assignment holds nothing, and the old pause lands on the client's new assignment
        assign(holds, P0);
        SettlementRig.PartitionCall explicitResume = rig.port.partitionCalls.get(2);
        stalePause.result.complete();
        explicitResume.result.complete();

        // Then the client was told to resume after it, though the new assignment holds no reason
        assertFalse(explicitResume.pause, "the new assignment resumes, whatever the client holds");
        assertFalse(rig.clientPaused(P0), "the last call that reached the client is a resume");
    }

    @Test
    @DisplayName("a new assignment makes its first call even when it holds nothing")
    void firstCallIsAlwaysMade() {
        SettlementRig rig = rig();

        assign(new KafkaHoldController("test", rig.port), P1);

        assertEquals(1, rig.port.partitionCalls.size());
        assertFalse(rig.port.partitionCalls.get(0).pause);
    }
}
