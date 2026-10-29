// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.engine.spi.CommittedHistoryEntry;
import dev.vertique.workflow.history.CommandKind;
import dev.vertique.workflow.history.HistoryAttribution;
import dev.vertique.workflow.history.WorkflowHistoryEvent;
import dev.vertique.workflow.history.WorkflowHistoryEvent.BranchChange;
import dev.vertique.workflow.history.WorkflowHistoryEvent.CommandDisposition;
import dev.vertique.workflow.history.WorkflowHistoryEvent.CompensationChange;
import dev.vertique.workflow.history.WorkflowHistoryEvent.DecisionKind;
import dev.vertique.workflow.history.WorkflowHistoryEvent.FanInChange;
import dev.vertique.workflow.history.WorkflowHistoryEvent.TimerChange;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.state.HistoryActor;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.tasks.TaskAssignment;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the mapping of committed history entries to semantic events. */
class HistoryEventMapperTest {

    private static final WorkflowInstanceId ID = new WorkflowInstanceId(UUID.randomUUID());
    private static final Instant AT = Instant.parse("2026-01-02T03:04:05Z");
    private static final UUID TASK = UUID.randomUUID();
    private static final String TASK_PAYLOAD = "{\"taskId\":\"" + TASK + "\",\"decisionName\":\"approve\","
            + "\"assignment\":{\"userId\":\"u1\"},"
            + "\"oldAssignment\":{\"roleId\":\"r1\"},\"newAssignment\":{\"queueName\":\"q1\"}}";

    /** The entry types that map to no event. */
    private static final Set<WorkflowEntryType> EXCLUDED = EnumSet.of(WorkflowEntryType.STATE_BASELINE);

    private static WorkflowHistoryEntry entry(WorkflowEntryType type, String payload, String outcome) {
        return new WorkflowHistoryEntry(
                ID, 7L, type, payload, AT, 2, null, null, null, null, null, null, outcome, null, null, null, null,
                null);
    }

    private static Optional<WorkflowHistoryEvent> map(WorkflowEntryType type) {
        return HistoryEventMapper.map(new CommittedHistoryEntry(entry(type, TASK_PAYLOAD, null), null));
    }

    private static WorkflowHistoryEvent mapOutcome(String outcome) {
        return HistoryEventMapper.map(new CommittedHistoryEntry(entry(WorkflowEntryType.DECISION, "{}", outcome), null))
                .orElseThrow();
    }

    private static WorkflowHistoryEvent mapPayload(WorkflowEntryType type, String payload) {
        return HistoryEventMapper.map(new CommittedHistoryEntry(entry(type, payload, null), null))
                .orElseThrow();
    }

    @Test
    @DisplayName("every entry type maps to a typed event except the excluded ones")
    void everyEntryTypeIsMappedOrExcluded() {
        for (WorkflowEntryType type : WorkflowEntryType.values()) {
            // Given a valid entry of the type
            // When it is mapped
            Optional<WorkflowHistoryEvent> event = map(type);

            // Then it is a typed event, unless the type is explicitly excluded
            if (EXCLUDED.contains(type)) {
                assertThat(event).as(type.name()).isEmpty();
            } else {
                assertThat(event).as(type.name()).isPresent();
                assertThat(event.get()).as(type.name()).isNotInstanceOf(WorkflowHistoryEvent.Other.class);
            }
        }
    }

    @Test
    @DisplayName("instance, signal and plain task entries map to their variants")
    void lifecycleSignalAndTaskFamilies() {
        // Given entries of the instance lifecycle, signal and payload-free task families
        // When they are mapped
        // Then each reads as its variant
        assertThat(map(WorkflowEntryType.START).orElseThrow()).isInstanceOf(WorkflowHistoryEvent.InstanceStarted.class);
        assertThat(map(WorkflowEntryType.RETRIED).orElseThrow())
                .isInstanceOf(WorkflowHistoryEvent.InstanceRetried.class);
        assertThat(map(WorkflowEntryType.CANCELLED).orElseThrow())
                .isInstanceOf(WorkflowHistoryEvent.InstanceCancelled.class);
        assertThat(map(WorkflowEntryType.COMPLETED).orElseThrow())
                .isInstanceOf(WorkflowHistoryEvent.InstanceCompleted.class);
        assertThat(map(WorkflowEntryType.FAILED).orElseThrow()).isInstanceOf(WorkflowHistoryEvent.InstanceFailed.class);
        assertThat(map(WorkflowEntryType.WORKFLOW_MIGRATED).orElseThrow())
                .isInstanceOf(WorkflowHistoryEvent.InstanceMigrated.class);
        assertThat(map(WorkflowEntryType.SIGNAL_RECEIVED).orElseThrow())
                .isInstanceOf(WorkflowHistoryEvent.SignalReceived.class);
        assertThat(map(WorkflowEntryType.SIGNAL_PARKED).orElseThrow())
                .isInstanceOf(WorkflowHistoryEvent.SignalParked.class);
        assertThat(map(WorkflowEntryType.SIGNAL_APPLIED).orElseThrow())
                .isInstanceOf(WorkflowHistoryEvent.SignalApplied.class);
        assertThat(map(WorkflowEntryType.TASK_EXPIRED).orElseThrow())
                .isInstanceOf(WorkflowHistoryEvent.TaskExpired.class);
        assertThat(map(WorkflowEntryType.TASK_REMINDER_FIRED).orElseThrow())
                .isInstanceOf(WorkflowHistoryEvent.TaskReminderFired.class);
        assertThat(map(WorkflowEntryType.SIDE_EFFECT_RECORDED).orElseThrow())
                .isInstanceOf(WorkflowHistoryEvent.SideEffectRecorded.class);
        assertThat(map(WorkflowEntryType.FORK_DISPATCHED).orElseThrow())
                .isInstanceOf(WorkflowHistoryEvent.ForkDispatched.class);
    }

    @Test
    @DisplayName("timer, compensation, fan-in and command entries carry their change")
    void changeFamilies() {
        // Given the change-carrying families
        // When their entries are mapped
        // Then the change is the one the entry type names
        Map<WorkflowEntryType, TimerChange> timers = Map.of(
                WorkflowEntryType.TIMER_SCHEDULED, TimerChange.SCHEDULED,
                WorkflowEntryType.TIMER_FIRED, TimerChange.FIRED,
                WorkflowEntryType.TIMEOUT, TimerChange.TIMED_OUT,
                WorkflowEntryType.TIMER_CANCELLED, TimerChange.CANCELLED,
                WorkflowEntryType.TIMER_FAILED, TimerChange.FAILED);
        timers.forEach((type, change) -> assertThat(map(type).orElseThrow())
                .as(type.name())
                .isInstanceOfSatisfying(WorkflowHistoryEvent.TimerChanged.class, e -> assertThat(e.change())
                        .isEqualTo(change)));

        Map<WorkflowEntryType, CompensationChange> compensation = Map.of(
                WorkflowEntryType.COMPENSATING_START, CompensationChange.STARTED,
                WorkflowEntryType.COMPENSATING_STEP, CompensationChange.STEP_RECORDED,
                WorkflowEntryType.COMPENSATED, CompensationChange.COMPENSATED);
        compensation.forEach((type, change) -> assertThat(map(type).orElseThrow())
                .as(type.name())
                .isInstanceOfSatisfying(WorkflowHistoryEvent.CompensationChanged.class, e -> assertThat(e.change())
                        .isEqualTo(change)));

        Map<WorkflowEntryType, FanInChange> fanIn = Map.of(
                WorkflowEntryType.FAN_IN_EVALUATED, FanInChange.EVALUATED,
                WorkflowEntryType.FAN_IN_COMPLETED, FanInChange.COMPLETED,
                WorkflowEntryType.FAN_IN_FAILED, FanInChange.FAILED);
        fanIn.forEach((type, change) -> assertThat(map(type).orElseThrow())
                .as(type.name())
                .isInstanceOfSatisfying(WorkflowHistoryEvent.FanInChanged.class, e -> assertThat(e.change())
                        .isEqualTo(change)));

        Map<WorkflowEntryType, CommandDisposition> commands = Map.of(
                WorkflowEntryType.COMMAND_RECORDED, CommandDisposition.RECORDED,
                WorkflowEntryType.COMMAND_RECEIVED, CommandDisposition.RECEIVED,
                WorkflowEntryType.COMMAND_COMPLETED, CommandDisposition.COMPLETED,
                WorkflowEntryType.COMMAND_REJECTED, CommandDisposition.REJECTED,
                WorkflowEntryType.COMMAND_STALE, CommandDisposition.STALE,
                WorkflowEntryType.COMMAND_DEFERRED, CommandDisposition.DEFERRED,
                WorkflowEntryType.COMMAND_DEAD_LETTERED, CommandDisposition.DEAD_LETTERED,
                WorkflowEntryType.COMMAND_CANCELLED, CommandDisposition.CANCELLED,
                WorkflowEntryType.COMMAND_SUPERSEDED, CommandDisposition.SUPERSEDED);
        assertThat(commands).hasSize(9);
        commands.forEach((type, disposition) -> assertThat(map(type).orElseThrow())
                .as(type.name())
                .isInstanceOfSatisfying(WorkflowHistoryEvent.CommandChanged.class, e -> assertThat(e.disposition())
                        .isEqualTo(disposition)));
    }

    @Test
    @DisplayName("every branch entry type maps to its branch change, all 22 changes used once")
    void branchFamily() {
        // Given the 22 branch entry types
        // When they are mapped
        Set<BranchChange> seen = EnumSet.noneOf(BranchChange.class);
        for (WorkflowEntryType type : WorkflowEntryType.values()) {
            if (!type.name().startsWith("BRANCH_")) {
                continue;
            }
            WorkflowHistoryEvent event = map(type).orElseThrow();
            assertThat(event).as(type.name()).isInstanceOf(WorkflowHistoryEvent.BranchChanged.class);
            seen.add(((WorkflowHistoryEvent.BranchChanged) event).change());
        }

        // Then each reaches a distinct change
        assertThat(seen).containsExactlyInAnyOrder(BranchChange.values());
        assertThat(((WorkflowHistoryEvent.BranchChanged)
                                map(WorkflowEntryType.BRANCH_WAIT).orElseThrow())
                        .change())
                .isEqualTo(BranchChange.WAITING);
        assertThat(((WorkflowHistoryEvent.BranchChanged)
                                map(WorkflowEntryType.BRANCH_COMPENSATING_START).orElseThrow())
                        .change())
                .isEqualTo(BranchChange.COMPENSATING_STARTED);
        assertThat(((WorkflowHistoryEvent.BranchChanged)
                                map(WorkflowEntryType.BRANCH_COMPENSATING_STEP).orElseThrow())
                        .change())
                .isEqualTo(BranchChange.COMPENSATING_STEP_RECORDED);
    }

    @Test
    @DisplayName("a state baseline maps to nothing")
    void baselineMapsToNothing() {
        // Given a baseline entry
        // When it is mapped
        // Then there is no event
        assertThat(map(WorkflowEntryType.STATE_BASELINE)).isEmpty();
    }

    @Test
    @DisplayName("each engine decision word maps to its kind")
    void decisionWords() {
        // Given the words the engine writes as decision outcomes
        Map<String, DecisionKind> words = Map.ofEntries(
                Map.entry("intent", DecisionKind.INTENT),
                Map.entry("wait-signal", DecisionKind.WAIT_SIGNAL),
                Map.entry("wait-timer", DecisionKind.WAIT_TIMER),
                Map.entry("wait-task", DecisionKind.WAIT_TASK),
                Map.entry("signal-applied", DecisionKind.SIGNAL_APPLIED),
                Map.entry("task-decided", DecisionKind.TASK_DECIDED),
                Map.entry("task-expired", DecisionKind.TASK_EXPIRED),
                Map.entry("timer-fired", DecisionKind.TIMER_FIRED),
                Map.entry("timeout", DecisionKind.TIMEOUT),
                Map.entry("fork-dispatched", DecisionKind.FORK_DISPATCHED),
                Map.entry("joined", DecisionKind.JOINED),
                Map.entry("join-failed", DecisionKind.JOIN_FAILED),
                Map.entry("completed", DecisionKind.COMPLETED),
                Map.entry("failed", DecisionKind.FAILED),
                Map.entry("compensating", DecisionKind.COMPENSATING),
                Map.entry("compensation-step", DecisionKind.COMPENSATION_STEP),
                Map.entry("compensated", DecisionKind.COMPENSATED),
                Map.entry("retried", DecisionKind.RETRIED),
                Map.entry("migrated", DecisionKind.MIGRATED));

        // When each is mapped
        // Then it is its kind with no chosen step, and every kind except BRANCH is covered
        words.forEach((word, kind) -> assertThat(mapOutcome(word))
                .as(word)
                .isInstanceOfSatisfying(WorkflowHistoryEvent.StepDecided.class, e -> {
                    assertThat(e.kind()).isEqualTo(kind);
                    assertThat(e.chosenStepId()).isNull();
                }));
        assertThat(words.values())
                .containsExactlyInAnyOrder(java.util.Arrays.stream(DecisionKind.values())
                        .filter(kind -> kind != DecisionKind.BRANCH)
                        .toArray(DecisionKind[]::new));
    }

    @Test
    @DisplayName("any other outcome is a branch with the chosen step, cut at 40 characters, and null is tolerated")
    void branchFallback() {
        // Given an author-defined step id as outcome
        // When it is mapped
        // Then it is a BRANCH decision naming the step
        assertThat(mapOutcome("approve-path")).isInstanceOfSatisfying(WorkflowHistoryEvent.StepDecided.class, e -> {
            assertThat(e.kind()).isEqualTo(DecisionKind.BRANCH);
            assertThat(e.chosenStepId()).isEqualTo("approve-path");
        });

        // Given an outcome longer than 40 characters
        // When it is mapped
        // Then the chosen step is cut
        String longStep = "s".repeat(55);
        assertThat(((WorkflowHistoryEvent.StepDecided) mapOutcome(longStep)).chosenStepId())
                .hasSize(40);

        // Given a case that differs from an engine word, and a missing outcome
        // When they are mapped
        // Then both are branches
        assertThat(((WorkflowHistoryEvent.StepDecided) mapOutcome("Joined")).kind())
                .isEqualTo(DecisionKind.BRANCH);
        WorkflowHistoryEvent.StepDecided missing = (WorkflowHistoryEvent.StepDecided) mapOutcome(null);
        assertThat(missing.kind()).isEqualTo(DecisionKind.BRANCH);
        assertThat(missing.chosenStepId()).isNull();
    }

    @Test
    @DisplayName("entry info comes from the row and the command kind, with safe defaults")
    void entryInfo() {
        // Given a fully attributed row with a command kind
        DefinitionKey key = new DefinitionKey("scope", "order", 3L);
        WorkflowHistoryEntry row = new WorkflowHistoryEntry(
                ID,
                9L,
                WorkflowEntryType.START,
                "{}",
                AT,
                1,
                new HistoryActor(HistoryActor.Kind.USER, "alice", null),
                "bob",
                "VERIFIED",
                "node-1",
                42L,
                "step-a",
                null,
                key,
                null,
                null,
                null,
                null);

        // When it is mapped
        WorkflowHistoryEvent.EntryInfo info = HistoryEventMapper.map(new CommittedHistoryEntry(row, "SIGNAL"))
                .orElseThrow()
                .entryInfo();

        // Then every field is taken from the row
        assertThat(info.instanceId()).isEqualTo(ID);
        assertThat(info.sequence()).isEqualTo(9L);
        assertThat(info.definition()).isEqualTo(key);
        assertThat(info.stepId()).isEqualTo("step-a");
        assertThat(info.commandId()).isEqualTo(42L);
        assertThat(info.commandKind()).isEqualTo(CommandKind.SIGNAL);
        assertThat(info.actor()).isEqualTo(new HistoryActor(HistoryActor.Kind.USER, "alice", null));
        assertThat(info.onBehalfOf()).isEqualTo("bob");
        assertThat(info.attribution()).isEqualTo(HistoryAttribution.VERIFIED);
        assertThat(info.recordedAt()).isEqualTo(AT);

        // Given a bare row with an unknown command kind and attribution
        WorkflowHistoryEntry bare = new WorkflowHistoryEntry(
                ID,
                1L,
                WorkflowEntryType.START,
                "{}",
                AT,
                1,
                null,
                null,
                "SOMETHING",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        // When it is mapped
        WorkflowHistoryEvent.EntryInfo defaults = HistoryEventMapper.map(new CommittedHistoryEntry(bare, "NEW_KIND"))
                .orElseThrow()
                .entryInfo();

        // Then the actor is NONE, the attribution ABSENT and the kind null
        assertThat(defaults.actor()).isEqualTo(new HistoryActor(HistoryActor.Kind.NONE, null, null));
        assertThat(defaults.attribution()).isEqualTo(HistoryAttribution.ABSENT);
        assertThat(defaults.commandKind()).isNull();
        assertThat(defaults.definition()).isNull();
        assertThat(HistoryEventMapper.map(new CommittedHistoryEntry(bare, null))
                        .orElseThrow()
                        .entryInfo()
                        .commandKind())
                .isNull();
    }

    @Test
    @DisplayName("task events read the task id, the decision name and the assignments from the payload")
    void taskPayloads() {
        // Given a payload holding a task id, a decision name and the three assignment shapes
        // When the task entries are mapped
        // Then the curated values are read
        assertThat(mapPayload(WorkflowEntryType.TASK_CREATED, TASK_PAYLOAD))
                .isInstanceOfSatisfying(WorkflowHistoryEvent.TaskCreated.class, e -> {
                    assertThat(e.taskId()).isEqualTo(TASK);
                    assertThat(e.assignment()).isEqualTo(new TaskAssignment.User("u1"));
                });
        assertThat(mapPayload(WorkflowEntryType.TASK_COMPLETED, TASK_PAYLOAD))
                .isInstanceOfSatisfying(WorkflowHistoryEvent.TaskDecided.class, e -> {
                    assertThat(e.taskId()).isEqualTo(TASK);
                    assertThat(e.decisionName()).isEqualTo("approve");
                });
        assertThat(mapPayload(WorkflowEntryType.TASK_REASSIGNED, TASK_PAYLOAD))
                .isInstanceOfSatisfying(WorkflowHistoryEvent.TaskReassigned.class, e -> {
                    assertThat(e.from()).isEqualTo(new TaskAssignment.Role("r1"));
                    assertThat(e.to()).isEqualTo(new TaskAssignment.Queue("q1"));
                });
        assertThat(mapPayload(WorkflowEntryType.TASK_CANCELLED, TASK_PAYLOAD))
                .isInstanceOfSatisfying(WorkflowHistoryEvent.TaskCancelled.class, e -> assertThat(e.taskId())
                        .isEqualTo(TASK));
    }

    @Test
    @DisplayName("a task entry whose payload cannot be read maps to Other and never throws")
    void unreadableTaskPayloadsFallBackToOther() {
        String noTask = "{\"decisionName\":\"approve\",\"assignment\":{\"userId\":\"u1\"}}";
        String badTask = "{\"taskId\":\"not-a-uuid\",\"decisionName\":\"approve\"}";
        String noDecision = "{\"taskId\":\"" + TASK + "\"}";
        String twoKeys = "{\"taskId\":\"" + TASK + "\",\"assignment\":{\"userId\":\"u1\",\"roleId\":\"r1\"}}";
        String unknownShape = "{\"taskId\":\"" + TASK + "\",\"assignment\":{\"group\":\"g\"}}";
        String blankUser = "{\"taskId\":\"" + TASK + "\",\"assignment\":{\"userId\":\" \"}}";
        String notObject = "{\"taskId\":\"" + TASK + "\",\"assignment\":\"u1\"}";
        String oneSideOnly = "{\"taskId\":\"" + TASK + "\",\"oldAssignment\":{\"userId\":\"u1\"}}";

        // Given payloads that are missing, unparseable or of an unknown shape
        // When the task entries are mapped
        // Then the closest typed fallback is Other with the entry's type
        assertOther(WorkflowEntryType.TASK_CREATED, noTask);
        assertOther(WorkflowEntryType.TASK_CREATED, twoKeys);
        assertOther(WorkflowEntryType.TASK_CREATED, unknownShape);
        assertOther(WorkflowEntryType.TASK_CREATED, blankUser);
        assertOther(WorkflowEntryType.TASK_CREATED, notObject);
        assertOther(WorkflowEntryType.TASK_COMPLETED, badTask);
        assertOther(WorkflowEntryType.TASK_COMPLETED, noDecision);
        assertOther(WorkflowEntryType.TASK_REASSIGNED, oneSideOnly);
        assertOther(WorkflowEntryType.TASK_CANCELLED, "{}");
        assertOther(WorkflowEntryType.TASK_CANCELLED, "not json");
        assertOther(WorkflowEntryType.TASK_CANCELLED, "[1,2]");
        assertOther(WorkflowEntryType.TASK_CANCELLED, "");
        assertOther(WorkflowEntryType.TASK_CANCELLED, null);
    }

    private static void assertOther(WorkflowEntryType type, String payload) {
        assertThat(mapPayload(type, payload))
                .as(type + " " + payload)
                .isInstanceOfSatisfying(WorkflowHistoryEvent.Other.class, e -> {
                    assertThat(e.type()).isEqualTo(type);
                    assertThat(e.entryInfo().sequence()).isEqualTo(7L);
                });
    }
}
