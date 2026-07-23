// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.sqlclient.SqlClient;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkflowReminderComposeValidator}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Passes when the registry has no registered plans at all.</li>
 *   <li>Passes when plans have {@link dev.vertique.workflow.plan.HumanTaskNode}s with
 *       {@code reminders=null} (no reminder spec).</li>
 *   <li>Passes when plans have no {@code HumanTaskNode}s at all.</li>
 *   <li>Passes when reminders are declared and at least one recorder declares
 *       {@link IntentKind#WORKFLOW_EVENT}.</li>
 *   <li>Throws {@link IllegalStateException} from the constructor when reminders are declared but
 *       no {@code WORKFLOW_EVENT} recorder is registered. The error message names the offending
 *       (definitionId, version, stepId) triples.</li>
 * </ul>
 */
class WorkflowReminderComposeValidatorTest {

    // --- Domain stubs ---

    /** Minimal workflow state record for test plans. */
    record State(String id) {}

    // --- Contract marker interfaces (each unique so the registry accepts all) ---

    /** Contract marker for no-reminder task plans. */
    interface NoReminderContract {}

    /** Contract marker for no-task (complete-only) plans. */
    interface NoTaskContract {}

    /** Contract marker for recurring-reminder plans. */
    interface ReminderContract {}

    /** Contract marker for the second no-reminder plan in multi-plan tests. */
    interface CleanMultiContract {}

    /** Contract marker for the reminder plan in multi-plan tests. */
    interface DirtyMultiContract {}

    // --- Helpers ---

    /**
     * Creates a mock recorder that declares the given {@link IntentKind}.
     *
     * @param kind the kind to declare
     * @return a configured mock recorder
     */
    @SuppressWarnings("unchecked")
    private static WorkflowSideEffectRecorder<SqlClient> recorderFor(IntentKind kind) {
        WorkflowSideEffectRecorder<SqlClient> recorder = mock(WorkflowSideEffectRecorder.class);
        when(recorder.kind()).thenReturn(kind);
        return recorder;
    }

    /**
     * Builds a simple workflow definition with a human-task step but no reminder spec.
     *
     * @param defId the definition id
     * @return a workflow definition whose task has no reminders
     */
    private static WorkflowDefinition<State, NoReminderContract> noReminderDef(String defId) {
        return new WorkflowDefinition<>() {
            @Override
            public Class<NoReminderContract> contract() {
                return NoReminderContract.class;
            }

            @Override
            public Class<State> stateType() {
                return State.class;
            }

            @Override
            public String definitionId() {
                return defId;
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<State> wf) {
                wf.init(State.class, s -> s)
                        .initialStep("task-step")
                        .task("task-step")
                        .assignToRole("testers")
                        .decision("done", Void.class)
                        .onDecision((s, p) -> s)
                        .toStep("end")
                        .build()
                        .complete("end");
            }
        };
    }

    /**
     * Builds a simple workflow definition with a complete-only path (no HumanTaskNode).
     *
     * @param defId the definition id
     * @return a workflow definition with no task nodes
     */
    private static WorkflowDefinition<State, NoTaskContract> noTaskDef(String defId) {
        return new WorkflowDefinition<>() {
            @Override
            public Class<NoTaskContract> contract() {
                return NoTaskContract.class;
            }

            @Override
            public Class<State> stateType() {
                return State.class;
            }

            @Override
            public String definitionId() {
                return defId;
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<State> wf) {
                wf.init(State.class, s -> s).initialStep("end").complete("end");
            }
        };
    }

    /**
     * Builds a workflow definition with a recurring-reminder task.
     *
     * @param defId the definition id
     * @return a workflow definition whose task declares a 1-hour recurring reminder
     */
    private static WorkflowDefinition<State, ReminderContract> reminderDef(String defId) {
        return new WorkflowDefinition<>() {
            @Override
            public Class<ReminderContract> contract() {
                return ReminderContract.class;
            }

            @Override
            public Class<State> stateType() {
                return State.class;
            }

            @Override
            public String definitionId() {
                return defId;
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<State> wf) {
                wf.init(State.class, s -> s)
                        .initialStep("task-step")
                        .task("task-step")
                        .assignToRole("testers")
                        .decision("done", Void.class)
                        .onDecision((s, p) -> s)
                        .toStep("end")
                        .reminderEvery(Duration.ofHours(1))
                        .build()
                        .complete("end");
            }
        };
    }

    /**
     * Builds a no-reminder task workflow with the {@link CleanMultiContract} marker (distinct from
     * {@link NoReminderContract}) so it can coexist with other definitions in the same registry.
     *
     * @param defId the definition id
     * @return a workflow definition whose task has no reminders
     */
    private static WorkflowDefinition<State, CleanMultiContract> cleanMultiDef(String defId) {
        return new WorkflowDefinition<>() {
            @Override
            public Class<CleanMultiContract> contract() {
                return CleanMultiContract.class;
            }

            @Override
            public Class<State> stateType() {
                return State.class;
            }

            @Override
            public String definitionId() {
                return defId;
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<State> wf) {
                wf.init(State.class, s -> s)
                        .initialStep("task-step")
                        .task("task-step")
                        .assignToRole("testers")
                        .decision("done", Void.class)
                        .onDecision((s, p) -> s)
                        .toStep("end")
                        .build()
                        .complete("end");
            }
        };
    }

    /**
     * Builds a reminder workflow with the {@link DirtyMultiContract} marker (distinct from
     * {@link ReminderContract}) so it can coexist with other definitions in the same registry.
     *
     * @param defId the definition id
     * @return a workflow definition whose task declares a 1-hour recurring reminder
     */
    private static WorkflowDefinition<State, DirtyMultiContract> dirtyMultiDef(String defId) {
        return new WorkflowDefinition<>() {
            @Override
            public Class<DirtyMultiContract> contract() {
                return DirtyMultiContract.class;
            }

            @Override
            public Class<State> stateType() {
                return State.class;
            }

            @Override
            public String definitionId() {
                return defId;
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<State> wf) {
                wf.init(State.class, s -> s)
                        .initialStep("task-step")
                        .task("task-step")
                        .assignToRole("testers")
                        .decision("done", Void.class)
                        .onDecision((s, p) -> s)
                        .toStep("end")
                        .reminderEvery(Duration.ofHours(1))
                        .build()
                        .complete("end");
            }
        };
    }

    // --- Tests ---

    @Nested
    @DisplayName("empty registry (no plans registered)")
    class EmptyRegistry {

        @Test
        @DisplayName("empty registry + no recorders: validation passes (nothing to check)")
        void emptyRegistryNoRecordersPassesValidation() {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
            assertDoesNotThrow(() -> new WorkflowReminderComposeValidator(registry, Set.of()));
        }

        @Test
        @DisplayName("empty registry + WORKFLOW_EVENT recorder: validation passes")
        void emptyRegistryWithEventRecorderPassesValidation() {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
            assertDoesNotThrow(() ->
                    new WorkflowReminderComposeValidator(registry, Set.of(recorderFor(IntentKind.WORKFLOW_EVENT))));
        }
    }

    @Nested
    @DisplayName("plans with no reminder spec")
    class NoReminderPlans {

        @Test
        @DisplayName("task node with null reminders + no WORKFLOW_EVENT recorder: passes")
        void taskNodeNullRemindersNoEventRecorderPasses() {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
            registry.register(noReminderDef("no-reminder-def"));
            assertDoesNotThrow(() -> new WorkflowReminderComposeValidator(registry, Set.of()));
        }

        @Test
        @DisplayName("no task nodes at all + no WORKFLOW_EVENT recorder: passes")
        void noTaskNodesNoEventRecorderPasses() {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
            registry.register(noTaskDef("no-task-def"));
            assertDoesNotThrow(() -> new WorkflowReminderComposeValidator(registry, Set.of()));
        }
    }

    @Nested
    @DisplayName("plans with reminder spec")
    class ReminderPlans {

        @Test
        @DisplayName("reminder declared + WORKFLOW_EVENT recorder registered: passes")
        void reminderDeclaredWithEventRecorderPasses() {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
            registry.register(reminderDef("reminder-ok-def"));
            WorkflowSideEffectRecorder<SqlClient> eventRecorder = recorderFor(IntentKind.WORKFLOW_EVENT);
            assertDoesNotThrow(() -> new WorkflowReminderComposeValidator(registry, Set.of(eventRecorder)));
        }

        @Test
        @DisplayName("reminder declared + only SERVICE recorder: throws IllegalStateException naming offender")
        void reminderDeclaredNoEventRecorderThrows() {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
            registry.register(reminderDef("reminder-bad-def"));
            WorkflowSideEffectRecorder<SqlClient> serviceRecorder = recorderFor(IntentKind.SERVICE);

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> new WorkflowReminderComposeValidator(registry, Set.of(serviceRecorder)));

            assertTrue(
                    ex.getMessage().contains("reminder-bad-def"),
                    "Error message must contain the offending definitionId; was: " + ex.getMessage());
            assertTrue(
                    ex.getMessage().contains("task-step"),
                    "Error message must contain the offending stepId; was: " + ex.getMessage());
            assertTrue(
                    ex.getMessage().contains("WORKFLOW_EVENT"),
                    "Error message must mention WORKFLOW_EVENT; was: " + ex.getMessage());
        }

        @Test
        @DisplayName("reminder declared + no recorders at all: throws IllegalStateException")
        void reminderDeclaredEmptyRecordersThrows() {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
            registry.register(reminderDef("reminder-no-recorders-def"));

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class, () -> new WorkflowReminderComposeValidator(registry, Set.of()));

            assertTrue(
                    ex.getMessage().contains("reminder-no-recorders-def"),
                    "Error message must contain the offending definitionId; was: " + ex.getMessage());
        }

        @Test
        @DisplayName(
                "multiple plans — only one has reminders, no WORKFLOW_EVENT recorder: throws naming only that plan")
        void multiplePlansOnlyOneWithRemindersThrows() {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
            registry.register(cleanMultiDef("clean-def-multi"));
            registry.register(dirtyMultiDef("dirty-def-multi"));

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class, () -> new WorkflowReminderComposeValidator(registry, Set.of()));

            assertTrue(
                    ex.getMessage().contains("dirty-def-multi"),
                    "Error must name dirty-def-multi; was: " + ex.getMessage());
            assertTrue(
                    !ex.getMessage().contains("clean-def-multi"),
                    "Error must NOT name clean-def-multi; was: " + ex.getMessage());
        }
    }
}
