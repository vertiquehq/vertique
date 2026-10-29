// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WorkflowHistoryEntry} grew by trailing nullable components while its five-argument form keeps
 * working: the old constructor reports entry version 1 and nothing else, the components are in the documented order,
 * and equality covers all of them.
 */
class WorkflowHistoryEntryTest {

    private static final WorkflowInstanceId ID =
            new WorkflowInstanceId(UUID.fromString("00000000-0000-0000-0000-000000000042"));
    private static final Instant RECORDED_AT = Instant.parse("2026-10-01T10:15:30Z");

    private static WorkflowHistoryEntry fiveArguments() {
        return new WorkflowHistoryEntry(ID, 7L, WorkflowEntryType.SIGNAL_RECEIVED, "{\"name\":\"go\"}", RECORDED_AT);
    }

    private static WorkflowHistoryEntry full() {
        return new WorkflowHistoryEntry(
                ID,
                7L,
                WorkflowEntryType.DECISION,
                "{\"name\":\"go\"}",
                RECORDED_AT,
                2,
                new HistoryActor(HistoryActor.Kind.SERVICE, "relay-svc", "post"),
                "u-2",
                "VERIFIED",
                "node-1",
                11L,
                "wait",
                "approve",
                new DefinitionKey("default", "audit-flow", 3L),
                "abc123",
                4L,
                5L,
                "[{\"op\":\"replace\",\"path\":\"/done\",\"value\":true}]");
    }

    @Test
    @DisplayName("the previous-arity constructor keeps entry version one and leaves every new component null")
    void previousArityConstructorKeepsVersionOneAndNullNewComponents() {
        // Given the values of the five-argument constructor
        // When an entry is built with it
        WorkflowHistoryEntry entry = fiveArguments();

        // Then the five original components are as given
        assertThat(entry.instanceId()).isEqualTo(ID);
        assertThat(entry.sequence()).isEqualTo(7L);
        assertThat(entry.entryType()).isEqualTo(WorkflowEntryType.SIGNAL_RECEIVED);
        assertThat(entry.payloadJson()).isEqualTo("{\"name\":\"go\"}");
        assertThat(entry.recordedAt()).isEqualTo(RECORDED_AT);

        // And the entry version is the boxed one
        assertThat(entry.entryVersion()).isEqualTo(Integer.valueOf(1));

        // And every other new component is null, the attribution status included
        assertThat(entry.actor()).isNull();
        assertThat(entry.onBehalfOf()).isNull();
        assertThat(entry.attributionStatus()).isNull();
        assertThat(entry.executorId()).isNull();
        assertThat(entry.commandId()).isNull();
        assertThat(entry.stepId()).isNull();
        assertThat(entry.outcome()).isNull();
        assertThat(entry.definitionKey()).isNull();
        assertThat(entry.planHash()).isNull();
        assertThat(entry.stateVersionBefore()).isNull();
        assertThat(entry.stateVersionAfter()).isNull();
        assertThat(entry.patchJson()).isNull();

        // And the canonical constructor with the same values and the version and nulls is equal to it
        WorkflowHistoryEntry canonical = new WorkflowHistoryEntry(
                ID,
                7L,
                WorkflowEntryType.SIGNAL_RECEIVED,
                "{\"name\":\"go\"}",
                RECORDED_AT,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        assertThat(entry).isEqualTo(canonical).hasSameHashCodeAs(canonical);
    }

    @Test
    @DisplayName("the canonical constructor accepts a null entry version, boxed in the record")
    void canonicalConstructorAcceptsANullEntryVersion() {
        // Given the component list of the record
        RecordComponent[] components = WorkflowHistoryEntry.class.getRecordComponents();

        // When an entry is built with a null entry version
        WorkflowHistoryEntry entry = new WorkflowHistoryEntry(
                ID,
                1L,
                WorkflowEntryType.START,
                "{}",
                RECORDED_AT,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        // Then it is accepted and reports no version, and the component is a boxed integer
        assertThat(entry.entryVersion()).isNull();
        assertThat(components[5].getName()).isEqualTo("entryVersion");
        assertThat(components[5].getType()).isEqualTo(Integer.class);
    }

    @Test
    @DisplayName("the components are in the documented order and types")
    void componentsAreInTheDocumentedOrder() {
        // Given the record components in declaration order
        List<String> described = Arrays.stream(WorkflowHistoryEntry.class.getRecordComponents())
                .map(component -> component.getType().getSimpleName() + " " + component.getName())
                .toList();

        // Then the five original ones come first, then the new ones in the documented order
        assertThat(described)
                .containsExactly(
                        "WorkflowInstanceId instanceId",
                        "long sequence",
                        "WorkflowEntryType entryType",
                        "String payloadJson",
                        "Instant recordedAt",
                        "Integer entryVersion",
                        "HistoryActor actor",
                        "String onBehalfOf",
                        "String attributionStatus",
                        "String executorId",
                        "Long commandId",
                        "String stepId",
                        "String outcome",
                        "DefinitionKey definitionKey",
                        "String planHash",
                        "Long stateVersionBefore",
                        "Long stateVersionAfter",
                        "String patchJson");
    }

    @Test
    @DisplayName("equality covers every component")
    void equalityCoversEveryComponent() {
        // Given an entry with every component set and an equal copy
        WorkflowHistoryEntry base = full();
        assertThat(full()).isEqualTo(base).hasSameHashCodeAs(base);

        // When each new component differs in turn, then the entries differ
        assertThat(base)
                .isNotEqualTo(with(base, 5, 1))
                .isNotEqualTo(with(base, 6, new HistoryActor(HistoryActor.Kind.USER, "u-1", null)))
                .isNotEqualTo(with(base, 7, "u-3"))
                .isNotEqualTo(with(base, 8, "DEGRADED"))
                .isNotEqualTo(with(base, 9, "node-2"))
                .isNotEqualTo(with(base, 10, 12L))
                .isNotEqualTo(with(base, 11, "other-step"))
                .isNotEqualTo(with(base, 12, "reject"))
                .isNotEqualTo(with(base, 13, new DefinitionKey("default", "audit-flow", 4L)))
                .isNotEqualTo(with(base, 14, "def456"))
                .isNotEqualTo(with(base, 15, 40L))
                .isNotEqualTo(with(base, 16, 50L))
                .isNotEqualTo(with(base, 17, "[]"));
    }

    @Test
    @DisplayName("the actor kinds are user, service, system and none, and an actor carries its id and reason")
    void actorKindsAreUserServiceSystemAndNone() {
        // Given the kinds of a logged actor
        // Then they are exactly these four, in this order
        assertThat(HistoryActor.Kind.values())
                .containsExactly(
                        HistoryActor.Kind.USER,
                        HistoryActor.Kind.SERVICE,
                        HistoryActor.Kind.SYSTEM,
                        HistoryActor.Kind.NONE);

        // And an actor keeps its kind, id and reason, which may be null
        HistoryActor none = new HistoryActor(HistoryActor.Kind.NONE, null, null);
        assertThat(none.kind()).isEqualTo(HistoryActor.Kind.NONE);
        assertThat(none.id()).isNull();
        assertThat(none.reason()).isNull();
        assertThat(new HistoryActor(HistoryActor.Kind.SYSTEM, null, "post"))
                .isEqualTo(new HistoryActor(HistoryActor.Kind.SYSTEM, null, "post"))
                .isNotEqualTo(none);
    }

    /** The entry with the component at the index replaced, built through the canonical constructor. */
    private static WorkflowHistoryEntry with(WorkflowHistoryEntry entry, int index, Object value) {
        Object[] values = {
            entry.instanceId(),
            entry.sequence(),
            entry.entryType(),
            entry.payloadJson(),
            entry.recordedAt(),
            entry.entryVersion(),
            entry.actor(),
            entry.onBehalfOf(),
            entry.attributionStatus(),
            entry.executorId(),
            entry.commandId(),
            entry.stepId(),
            entry.outcome(),
            entry.definitionKey(),
            entry.planHash(),
            entry.stateVersionBefore(),
            entry.stateVersionAfter(),
            entry.patchJson()
        };
        values[index] = value;
        return new WorkflowHistoryEntry(
                (WorkflowInstanceId) values[0],
                (Long) values[1],
                (WorkflowEntryType) values[2],
                (String) values[3],
                (Instant) values[4],
                (Integer) values[5],
                (HistoryActor) values[6],
                (String) values[7],
                (String) values[8],
                (String) values[9],
                (Long) values[10],
                (String) values[11],
                (String) values[12],
                (DefinitionKey) values[13],
                (String) values[14],
                (Long) values[15],
                (Long) values[16],
                (String) values[17]);
    }
}
