// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowReminderComposeValidator;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit-style test verifying that {@link WorkflowReminderComposeValidator} throws
 * {@link IllegalStateException} at construction time when any registered plan declares a
 * {@link ReminderSpec} but no {@link IntentKind#WORKFLOW_EVENT} recorder is registered.
 *
 * <p>This test does not need a real database or Vert.x — it exercises the validator's
 * constructor-side-effect check directly. The test is placed in the {@code engine} package so it
 * can participate in the integration test suite and run as part of {@code verify} without needing
 * a dedicated module.
 *
 * <p>The validator is the enforcement point for cycle-4 design point §5: reminders require events.
 * Without a {@code WORKFLOW_EVENT} recorder, reminder fires produce only history entries and never
 * notify downstream consumers — a silent misconfiguration that must be caught at startup.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WorkflowReminderRequiresEventsIT {

    // --- Domain types ---

    /** Minimal state for test plans. */
    record State(String id) {}

    /** Contract for a plan with one-shot reminders. */
    interface OneShotReminderContract {}

    /** Contract for a plan with recurring reminders. */
    interface RecurringReminderContract {}

    /** Contract for a plan without reminders. */
    interface NoReminderContract {}

    // --- Workflow definitions ---

    /**
     * A plan with a single human-task step that declares a one-shot reminder at 200ms.
     * Used to verify the validator rejects plans with reminders when no event recorder is wired.
     */
    static final WorkflowDefinition<State, OneShotReminderContract> ONE_SHOT_REMINDER_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<OneShotReminderContract> contract() {
            return OneShotReminderContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "requires-events-one-shot";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(State.class, s -> s)
                    .initialStep("review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", Void.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .reminderAt(Duration.ofMillis(200))
                    .build()
                    .complete("done");
        }
    };

    /**
     * A plan with a recurring reminder at 150ms interval, max 3 fires.
     */
    static final WorkflowDefinition<State, RecurringReminderContract> RECURRING_REMINDER_DEF =
            new WorkflowDefinition<>() {
                @Override
                public Class<RecurringReminderContract> contract() {
                    return RecurringReminderContract.class;
                }

                @Override
                public Class<State> stateType() {
                    return State.class;
                }

                @Override
                public String definitionId() {
                    return "requires-events-recurring";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<State> wf) {
                    wf.init(State.class, s -> s)
                            .initialStep("review")
                            .task("review")
                            .assignToRole("compliance")
                            .decision("approve", Void.class)
                            .onDecision((s, p) -> s)
                            .toStep("done")
                            .reminderEvery(Duration.ofMillis(150), 3)
                            .build()
                            .complete("done");
                }
            };

    /**
     * A plan without reminders — should be accepted even without an event recorder.
     */
    static final WorkflowDefinition<State, NoReminderContract> NO_REMINDER_DEF = new WorkflowDefinition<>() {
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
            return "requires-events-no-reminder";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(State.class, s -> s)
                    .initialStep("review")
                    .task("review")
                    .assignToRole("compliance")
                    .decision("approve", Void.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done");
        }
    };

    // --- Tests ---

    /**
     * Constructing {@link WorkflowReminderComposeValidator} with a registry that contains a plan
     * with one-shot reminders and an empty recorder set throws {@link IllegalStateException}.
     *
     * <p>The exception message must identify the offending definition id so operators can diagnose
     * the misconfiguration quickly.
     */
    @Test
    @DisplayName("One-shot reminder plan + no event recorder → IllegalStateException at validator construction")
    void oneShotReminderWithoutEventRecorderThrows() {
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(ONE_SHOT_REMINDER_DEF);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> new WorkflowReminderComposeValidator(registry, Set.of()),
                "validator must throw when a plan has reminders but no WORKFLOW_EVENT recorder");

        assertTrue(
                ex.getMessage().contains("requires-events-one-shot"),
                "exception message must name the offending definition id; got: " + ex.getMessage());
    }

    /**
     * Constructing {@link WorkflowReminderComposeValidator} with a registry that contains a plan
     * with recurring reminders and an empty recorder set throws {@link IllegalStateException}.
     */
    @Test
    @DisplayName("Recurring reminder plan + no event recorder → IllegalStateException at validator construction")
    void recurringReminderWithoutEventRecorderThrows() {
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(RECURRING_REMINDER_DEF);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> new WorkflowReminderComposeValidator(registry, Set.of()),
                "validator must throw for recurring reminder plan without event recorder");

        assertTrue(
                ex.getMessage().contains("requires-events-recurring"),
                "exception message must name the offending definition id; got: " + ex.getMessage());
    }

    /**
     * A registry with multiple plans where only one has reminders causes the validator to throw
     * naming the offending plan, even when other plans are clean.
     */
    @Test
    @DisplayName("Mixed registry (one with reminders, one without) + no event recorder → exception names offender")
    void mixedRegistryOnlyNamesOffendingPlan() {
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(NO_REMINDER_DEF);
        registry.register(ONE_SHOT_REMINDER_DEF);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> new WorkflowReminderComposeValidator(registry, Set.of()),
                "validator must throw because one plan has reminders");

        assertTrue(
                ex.getMessage().contains("requires-events-one-shot"),
                "must name the reminder plan; got: " + ex.getMessage());
    }

    /**
     * A registry with only reminder-free plans is accepted without any recorder registered.
     * This is the happy path for the generic lifecycle events use-case where the app
     * does not need reminders.
     */
    @Test
    @DisplayName("Plans without reminders are accepted even with empty recorder set")
    void noReminderPlansPassWithoutEventRecorder() {
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(NO_REMINDER_DEF);

        // Must not throw
        WorkflowReminderComposeValidator validator = new WorkflowReminderComposeValidator(registry, Set.of());

        assertTrue(validator != null, "validator must be constructed successfully");
    }

    /**
     * A registry with a reminder plan IS accepted when a WORKFLOW_EVENT recorder is present,
     * simulated here by providing a stub recorder that declares
     * {@link IntentKind#WORKFLOW_EVENT}.
     */
    @Test
    @DisplayName("Reminder plan + WORKFLOW_EVENT recorder → validator construction succeeds")
    void reminderPlanWithEventRecorderSucceeds() {
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(ONE_SHOT_REMINDER_DEF);

        // Provide a stub recorder that declares WORKFLOW_EVENT
        dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder<io.vertx.sqlclient.SqlClient> stubEventRecorder =
                new dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder<>() {
                    @Override
                    public IntentKind kind() {
                        return IntentKind.WORKFLOW_EVENT;
                    }

                    @Override
                    public io.vertx.core.Future<dev.vertique.workflow.sideeffect.RecorderResult> record(
                            dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent intent,
                            io.vertx.sqlclient.SqlClient tx) {
                        return io.vertx.core.Future.succeededFuture(
                                dev.vertique.workflow.sideeffect.RecorderResult.empty());
                    }
                };

        // Must not throw
        WorkflowReminderComposeValidator validator =
                new WorkflowReminderComposeValidator(registry, Set.of(stubEventRecorder));

        assertTrue(validator != null, "validator must construct when event recorder is present");
    }
}
