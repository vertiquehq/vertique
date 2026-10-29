// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The history reader that compensation uses names the corrupt entry type when it fails. A recorded signal whose
 * application reaches compensation is rejected with a sanitized reason, so the entry type is visible only through the
 * reader's own failure and the executor's log; this test pins the reader's side.
 */
class MalformedHistoryReaderTest {

    private static WorkflowHistoryEntry entry(WorkflowEntryType type, String payload) {
        return new WorkflowHistoryEntry(new WorkflowInstanceId(UUID.randomUUID()), 7L, type, payload, Instant.EPOCH);
    }

    @Test
    @DisplayName("a signal-received payload that cannot be read fails naming SIGNAL_RECEIVED")
    void unreadablePayloadNamesTheEntryType() {
        WorkflowHistoryEntry entry = entry(WorkflowEntryType.SIGNAL_RECEIVED, "{\"not\":\"valid\"}");

        assertThatThrownBy(() -> WorkflowPayloads.decodeOrFail(
                        entry.payloadJson(), SignalReceivedHistoryPayload.class, entry, "SIGNAL_RECEIVED"))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("SIGNAL_RECEIVED");
    }

    @Test
    @DisplayName("a side-effect payload with a wrong field type fails naming SIDE_EFFECT_RECORDED")
    void wrongFieldTypeNamesTheEntryType() {
        WorkflowHistoryEntry entry = entry(
                WorkflowEntryType.SIDE_EFFECT_RECORDED, "{\"stepId\":[\"wrong\",\"type\"],\"targetId\":\"svc.run\"}");

        assertThatThrownBy(() -> WorkflowPayloads.decodeOrFail(
                        entry.payloadJson(), SideEffectRecordedHistoryPayload.class, entry, "SIDE_EFFECT_RECORDED"))
                .isInstanceOf(WorkflowDefinitionException.class)
                .satisfies(failure -> assertThat(failure.getMessage()).contains("SIDE_EFFECT_RECORDED"));
    }
}
