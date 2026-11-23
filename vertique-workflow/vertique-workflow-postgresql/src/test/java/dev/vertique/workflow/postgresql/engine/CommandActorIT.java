// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ActorScenario;
import dev.vertique.workflow.postgresql.executor.ActorScenario.Attributed;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that every command records who asked for it and that the executor, the cancellation and the reassignment
 * copy the right actor onto the history entries they write: the requester for the entries of a command, the canceller
 * and the reassigner for their own entries, while a cancelled command keeps the actor that recorded it.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
public class CommandActorIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_command_actor_it")
            .withMigration("classpath:db/migration/workflow");

    private static SecurityIdentity user(String id) {
        return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, id, Map.of()));
    }

    private static SecurityIdentity service(String id) {
        return SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, id, Map.of()));
    }

    private static boolean isActorAbsent(WorkflowExecutorEvent event) {
        return event instanceof WorkflowExecutorEvent.ActorAbsent;
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a start by a service principal is logged as that service and a payload cannot override the actor")
    void startByServicePrincipalIsLoggedWithItsActorAndPayloadCannotOverrideIt() {
        try (ActorScenario scenario = ActorScenario.timeoutWait(db.toPoolConfig())) {
            // Given a security context of the service billing and a start payload that names another actor
            SecurityIdentity billing = service("billing");
            Map<String, Object> forged = Map.of("actor", Map.of("kind", "USER", "id", "mallory"));

            // When the start is recorded and run to idle, then a second start is recorded with no security context
            WorkflowInstanceId first = scenario.start(billing, "billing-start", forged);
            scenario.runToIdle(first);
            WorkflowInstanceId second = scenario.start(null, "anonymous-start", Map.of());
            scenario.runToIdle(second);

            // Then the first START row and its history entries carry SERVICE billing
            List<Attributed> firstCommands = scenario.commands(first);
            assertThat(firstCommands)
                    .filteredOn(row -> row.label().equals("START"))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.actorKind()).isEqualTo("SERVICE");
                        assertThat(row.actorId()).isEqualTo("billing");
                        assertThat(row.actorReason()).isNull();
                    });
            assertThat(scenario.history(first)).isNotEmpty().allSatisfy(entry -> {
                assertThat(entry.actorKind()).as(entry.label()).isEqualTo("SERVICE");
                assertThat(entry.actorId()).as(entry.label()).isEqualTo("billing");
            });

            // And nothing the payload named reached any row
            assertThat(firstCommands).extracting(Attributed::actorId).doesNotContain("mallory");
            assertThat(scenario.history(first)).extracting(Attributed::actorId).doesNotContain("mallory");

            // And the timer command the first segment recorded carries the system
            assertThat(firstCommands)
                    .filteredOn(row -> row.label().equals("TIMER"))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.actorKind()).isEqualTo("SYSTEM");
                    });

            // And the second START row and its entries carry NONE
            assertThat(scenario.commands(second))
                    .filteredOn(row -> row.label().equals("START"))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.actorKind()).isEqualTo("NONE");
                        assertThat(row.actorId()).isNull();
                        assertThat(row.actorReason()).isNull();
                    });
            assertThat(scenario.history(second)).isNotEmpty().allSatisfy(entry -> {
                assertThat(entry.actorKind()).as(entry.label()).isEqualTo("NONE");
                assertThat(entry.actorId()).as(entry.label()).isNull();
            });

            // And the test observer received one actor-absent event, for the second start only
            scenario.awaitEvents(1, CommandActorIT::isActorAbsent);
            assertThat(scenario.events())
                    .filteredOn(CommandActorIT::isActorAbsent)
                    .containsExactly(new WorkflowExecutorEvent.ActorAbsent("START"));
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName(
            "the reassignment and cancellation entries carry their own actors and a cancelled command keeps its own")
    void cancellationAndReassignmentEntriesCarryTheirActors() {
        try (ActorScenario scenario = ActorScenario.dueDateTask(db.toPoolConfig())) {
            // Given an instance at a task with a pending signal recorded by user u-1
            WorkflowInstanceId id = scenario.start(user("u-0"), "task-actors", Map.of());
            scenario.runToIdle(id);
            scenario.signal(user("u-1"), id, "note", "k-note");
            long signalCommand = scenario.commands(id).stream()
                    .filter(row -> row.label().equals("SIGNAL"))
                    .findFirst()
                    .orElseThrow()
                    .commandId();

            // When user u-2 reassigns the task, then service ops cancels the instance
            scenario.reassignTask(user("u-2"), id, "u-3", "u-2", "reassign-key");
            scenario.cancel(service("ops"), id);

            // Then the reassignment entry carries USER u-2
            List<Attributed> history = scenario.history(id);
            assertThat(history)
                    .filteredOn(entry -> entry.label().equals("TASK_REASSIGNED"))
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.actorKind()).isEqualTo("USER");
                        assertThat(entry.actorId()).isEqualTo("u-2");
                    });

            // And the cancellation entry and every command-cancelled entry, the signal's among them, carry SERVICE ops
            assertThat(history)
                    .filteredOn(entry -> entry.label().equals("CANCELLED"))
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.is("SERVICE", "ops"))
                            .as(entry.toString())
                            .isTrue());
            assertThat(history)
                    .filteredOn(entry -> entry.label().equals("COMMAND_CANCELLED"))
                    .isNotEmpty()
                    .allSatisfy(entry -> assertThat(entry.is("SERVICE", "ops"))
                            .as(entry.toString())
                            .isTrue());
            assertThat(history)
                    .filteredOn(entry -> entry.label().equals("COMMAND_CANCELLED")
                            && Long.valueOf(signalCommand).equals(entry.commandId()))
                    .hasSize(1);

            // And the cancelled signal's own row keeps USER u-1, the one that recorded it
            assertThat(scenario.commands(id))
                    .filteredOn(row -> row.commandId() == signalCommand)
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.actorKind()).isEqualTo("USER");
                        assertThat(row.actorId()).isEqualTo("u-1");
                    });

            // And the cancelled due-date timer command keeps the system, not the canceller
            assertThat(scenario.commands(id))
                    .filteredOn(row -> row.label().equals("TIMER"))
                    .isNotEmpty()
                    .allSatisfy(row -> assertThat(row.actorKind()).isEqualTo("SYSTEM"));

            // And the task the cancellation cancelled records the canceller in its row, its history payload, its
            // event and its history entry
            JsonObject canceller = new JsonObject().put("kind", "SERVICE").put("value", "ops");
            assertThat(scenario.taskActor(id, "cancelled_by")).isEqualTo(canceller);
            assertThat(scenario.payloadActor(id, "TASK_CANCELLED", "cancelledBy"))
                    .isEqualTo(new JsonObject().put("serviceId", "ops"));
            assertThat(scenario.eventActor(id, WorkflowEventType.TASK_CANCELLED, "cancelledBy"))
                    .isEqualTo(Map.of("kind", "SERVICE", "value", "ops"));
            assertThat(history)
                    .filteredOn(entry -> entry.label().equals("TASK_CANCELLED"))
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.is("SERVICE", "ops"))
                            .as(entry.toString())
                            .isTrue());
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName(
            "a cancellation with no caller cancels the task as the system and logs nobody on the instance's own entries")
    void cancellationWithNoCallerCancelsTheTaskAsTheSystemAndLogsNobody() {
        try (ActorScenario scenario = ActorScenario.dueDateTask(db.toPoolConfig())) {
            // Given an instance at a task with a pending signal recorded by user u-1
            WorkflowInstanceId id = scenario.start(user("u-0"), "task-no-caller", Map.of());
            scenario.runToIdle(id);
            scenario.signal(user("u-1"), id, "note", "k-note");

            // When it is cancelled with no security context
            scenario.cancel(null, id);

            // Then the task is cancelled as the system with the reason workflow-cancelled, in its row, payload and
            // event
            assertThat(scenario.taskActor(id, "cancelled_by"))
                    .isEqualTo(new JsonObject().put("kind", "SYSTEM").put("value", "workflow-cancelled"));
            assertThat(scenario.payloadActor(id, "TASK_CANCELLED", "cancelledBy"))
                    .isEqualTo(new JsonObject().put("reason", "workflow-cancelled"));
            assertThat(scenario.eventActor(id, WorkflowEventType.TASK_CANCELLED, "cancelledBy"))
                    .isEqualTo(Map.of("kind", "SYSTEM", "value", "workflow-cancelled"));

            // And the cancellation entry and every command-cancelled entry carry nobody, though user u-1 recorded the
            // signal whose command was cancelled
            List<Attributed> history = scenario.history(id);
            assertThat(history)
                    .filteredOn(entry -> entry.label().equals("CANCELLED"))
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.actorKind()).isEqualTo("NONE"));
            assertThat(history)
                    .filteredOn(entry -> entry.label().equals("COMMAND_CANCELLED"))
                    .isNotEmpty()
                    .allSatisfy(entry -> {
                        assertThat(entry.actorKind()).as(entry.toString()).isEqualTo("NONE");
                        assertThat(entry.actorId()).as(entry.toString()).isNull();
                    });

            // And the task's own history entry names the same system actor
            assertThat(history)
                    .filteredOn(entry -> entry.label().equals("TASK_CANCELLED"))
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.actorKind()).as(entry.toString()).isEqualTo("SYSTEM");
                        assertThat(entry.actorReason()).as(entry.toString()).isEqualTo("workflow-cancelled");
                    });

            // And the signal's own row keeps USER u-1, the one that recorded it
            assertThat(scenario.commands(id))
                    .filteredOn(row -> row.label().equals("SIGNAL"))
                    .singleElement()
                    .satisfies(row -> assertThat(row.is("USER", "u-1")).isTrue());
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a completion and a reassignment record the actor the command names, not the caller")
    void completionAndReassignmentRecordTheActorTheCommandNames() {
        try (ActorScenario scenario = ActorScenario.dueDateTask(db.toPoolConfig())) {
            // Given an instance at a task, and a caller X (user u-x) that supplies the actor u-y in the commands
            WorkflowInstanceId reassigned = scenario.start(user("u-0"), "task-reassign-y", Map.of());
            scenario.runToIdle(reassigned);
            WorkflowInstanceId completed = scenario.start(user("u-0"), "task-complete-y", Map.of());
            scenario.runToIdle(completed);
            JsonObject y = new JsonObject().put("kind", "USER").put("value", "u-y");

            // When X reassigns the one task naming u-y, and completes the other naming u-y, and the executor applies it
            scenario.reassignTask(user("u-x"), reassigned, "u-3", "u-y", "reassign-y");
            scenario.completeTask(user("u-x"), completed, "complete-y", new WorkflowActor.User("u-y"));
            scenario.runToIdle(completed);

            // Then the reassignment names u-y in the task row, the history payload, the event and the history entry
            assertThat(scenario.taskActor(reassigned, "reassigned_by")).isEqualTo(y);
            assertThat(scenario.payloadActor(reassigned, "TASK_REASSIGNED", "reassignedBy"))
                    .isEqualTo(new JsonObject().put("userId", "u-y"));
            assertThat(scenario.eventActor(reassigned, WorkflowEventType.TASK_REASSIGNED, "reassignedBy"))
                    .isEqualTo(Map.of("kind", "USER", "value", "u-y"));
            assertThat(scenario.history(reassigned))
                    .filteredOn(entry -> entry.label().equals("TASK_REASSIGNED"))
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.is("USER", "u-y"))
                            .as(entry.toString())
                            .isTrue());

            // And the completion names u-y in the command row, the task row, the history payload, the event and the
            // history entry
            assertThat(scenario.commands(completed))
                    .filteredOn(row -> row.label().equals("COMPLETION"))
                    .singleElement()
                    .satisfies(row -> assertThat(row.is("USER", "u-y")).isTrue());
            assertThat(scenario.taskActor(completed, "completed_by")).isEqualTo(y);
            assertThat(scenario.payloadActor(completed, "TASK_COMPLETED", "completedBy"))
                    .isEqualTo(new JsonObject().put("userId", "u-y"));
            assertThat(scenario.eventActor(completed, WorkflowEventType.TASK_COMPLETED, "completedBy"))
                    .isEqualTo(Map.of("kind", "USER", "value", "u-y"));
            assertThat(scenario.history(completed))
                    .filteredOn(entry -> entry.label().equals("TASK_COMPLETED"))
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.is("USER", "u-y"))
                            .as(entry.toString())
                            .isTrue());

            // And nothing of either names the caller u-x
            assertThat(scenario.history(reassigned))
                    .extracting(Attributed::actorId)
                    .doesNotContain("u-x");
            assertThat(scenario.history(completed))
                    .extracting(Attributed::actorId)
                    .doesNotContain("u-x");
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName(
            "every cancellation of a deferred, parked, timer or dead-lettered command is logged with the canceller")
    void cancellationOfEveryCommandStateIsLoggedWithTheCancellersActor() {
        // Given instance A with a deferred signal, a parked signal and a pending timer command
        WorkflowInstanceId a;
        long parked;
        long deferred;
        long timer;
        try (ActorScenario scenario = ActorScenario.timeoutWait(db.toPoolConfig())) {
            a = scenario.start(user("u-0"), "cancel-states-a", Map.of());
            scenario.runToIdle(a);
            scenario.signal(user("u-1"), a, "note", "k-note");
            scenario.runToIdle(a);
            scenario.signal(user("u-2"), a, "approve", "k-approve");
            scenario.deferSignal(a, "k-approve");
            parked = commandOf(scenario, a, "SIGNAL", 0);
            deferred = commandOf(scenario, a, "SIGNAL", 1);
            timer = commandOf(scenario, a, "TIMER", 0);
            assertThat(scenario.commandStates(a))
                    .containsEntry(parked, "PARKED")
                    .containsEntry(deferred, "DEFERRED")
                    .containsEntry(timer, "PENDING");

            // When service ops cancels A
            scenario.cancel(service("ops"), a);

            // Then every command is cancelled, and the cancellation entry and every command-cancelled entry carry
            // SERVICE ops
            assertThat(scenario.commandStates(a))
                    .containsEntry(parked, "CANCELLED")
                    .containsEntry(deferred, "CANCELLED")
                    .containsEntry(timer, "CANCELLED");
            List<Attributed> history = scenario.history(a);
            assertThat(history)
                    .filteredOn(entry -> entry.label().equals("CANCELLED"))
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.is("SERVICE", "ops"))
                            .as(entry.toString())
                            .isTrue());
            assertThat(history)
                    .filteredOn(entry -> entry.label().equals("COMMAND_CANCELLED"))
                    .extracting(Attributed::commandId)
                    .containsExactlyInAnyOrder(parked, deferred, timer);
            assertThat(history)
                    .filteredOn(entry -> entry.label().equals("COMMAND_CANCELLED"))
                    .allSatisfy(entry -> assertThat(entry.is("SERVICE", "ops"))
                            .as(entry.toString())
                            .isTrue());

            // And each cancelled command row keeps the actor that recorded it
            assertThat(rowOf(scenario, a, parked).is("USER", "u-1")).isTrue();
            assertThat(rowOf(scenario, a, deferred).is("USER", "u-2")).isTrue();
            assertThat(rowOf(scenario, a, timer).actorKind()).isEqualTo("SYSTEM");

            // And the parked signal's receipt entry keeps the user who recorded the signal
            assertThat(history)
                    .filteredOn(entry -> entry.label().equals("SIGNAL_PARKED")
                            && Long.valueOf(parked).equals(entry.commandId()))
                    .isNotEmpty()
                    .allSatisfy(entry -> assertThat(entry.is("USER", "u-1"))
                            .as(entry.toString())
                            .isTrue());
        }

        // And given instance B with a dead-lettered signal and a dead-lettered completion
        try (ActorScenario scenario = ActorScenario.deadLetteringTask(db.toPoolConfig())) {
            WorkflowInstanceId b = scenario.start(user("u-0"), "cancel-states-b", Map.of());
            scenario.runToIdle(b);
            scenario.signal(user("u-1"), b, "note", "k-dead-signal");
            scenario.completeTask(user("u-4"), b, "k-dead-completion", new WorkflowActor.User("u-4"));
            scenario.deadLetter(b, "k-dead-signal", "k-dead-completion");
            long deadSignal = commandOf(scenario, b, "SIGNAL", 0);
            long deadCompletion = commandOf(scenario, b, "COMPLETION", 0);
            assertThat(scenario.commandStates(b))
                    .containsEntry(deadSignal, "DEAD")
                    .containsEntry(deadCompletion, "DEAD");

            // When service ops cancels B
            scenario.cancel(service("ops"), b);

            // Then both are cancelled and logged with SERVICE ops, as is the cancellation
            assertThat(scenario.commandStates(b))
                    .containsEntry(deadSignal, "CANCELLED")
                    .containsEntry(deadCompletion, "CANCELLED");
            List<Attributed> history = scenario.history(b);
            assertThat(history)
                    .filteredOn(entry -> entry.label().equals("CANCELLED"))
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.is("SERVICE", "ops"))
                            .as(entry.toString())
                            .isTrue());
            assertThat(history)
                    .filteredOn(entry -> entry.label().equals("COMMAND_CANCELLED"))
                    .extracting(Attributed::commandId)
                    .contains(deadSignal, deadCompletion);
            assertThat(history)
                    .filteredOn(entry -> entry.label().equals("COMMAND_CANCELLED"))
                    .allSatisfy(entry -> assertThat(entry.is("SERVICE", "ops"))
                            .as(entry.toString())
                            .isTrue());

            // And each cancelled command row keeps the actor that recorded it
            assertThat(rowOf(scenario, b, deadSignal).is("USER", "u-1")).isTrue();
            assertThat(rowOf(scenario, b, deadCompletion).is("USER", "u-4")).isTrue();
        }
    }

    /** The id of the n-th command of the kind, in queue order. */
    private static long commandOf(ActorScenario scenario, WorkflowInstanceId id, String kind, int index) {
        return scenario.commands(id).stream()
                .filter(row -> row.label().equals(kind))
                .skip(index)
                .findFirst()
                .orElseThrow()
                .commandId();
    }

    private static Attributed rowOf(ActorScenario scenario, WorkflowInstanceId id, long commandId) {
        return scenario.commands(id).stream()
                .filter(row -> row.commandId() == commandId)
                .findFirst()
                .orElseThrow();
    }
}
