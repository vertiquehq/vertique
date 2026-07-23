// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.ReminderSpec;
import dev.vertique.workflow.plan.WorkflowPlan;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the cycle-4 reminder DSL methods on {@link WorkflowBuilder.TaskBuilder}:
 * {@code reminderAt}, {@code reminderEvery}, mutual exclusion between the two, and the guarantee
 * that a task built without any reminder call leaves {@code reminders=null} on the resulting
 * {@link HumanTaskNode}.
 *
 * <p>Validation that is delegated to {@link ReminderSpec} (positive durations, maxFires &ge; 1) is
 * exercised here at the DSL layer to confirm the builder passes its arguments through to the spec
 * constructors without silently dropping the error.
 */
class WorkflowBuilderReminderTest {

    record State(String value) {}

    record Cmd(String seed) {}

    private static WorkflowBuilder<State> baseBuilder() {
        WorkflowBuilder<State> wf = new WorkflowBuilder<>();
        wf.init(Cmd.class, cmd -> new State(cmd.seed())).initialStep("start");
        return wf;
    }

    /** Builds a plan with one task step and returns the resulting {@link HumanTaskNode}. */
    private static HumanTaskNode buildTaskNode(
            java.util.function.Consumer<WorkflowBuilder<State>.TaskBuilder> configure) {
        WorkflowBuilder<State> wf = baseBuilder();
        WorkflowBuilder<State>.TaskBuilder taskBuilder = wf.task("start")
                .assignToRole("approvers")
                .decision("approve", String.class)
                .onDecision((s, p) -> s)
                .toStep("done");
        configure.accept(taskBuilder);
        WorkflowPlan plan = taskBuilder.build().complete("done").build("def", 1L);
        return (HumanTaskNode) plan.nodes().stream()
                .filter(n -> n.stepId().equals("start"))
                .findFirst()
                .orElseThrow();
    }

    // --- No reminder (baseline) ---

    @Nested
    @DisplayName("no reminder call leaves reminders=null")
    class NoReminderCall {

        @Test
        @DisplayName("task built with no reminderAt/reminderEvery call has reminders=null")
        void noReminderCallLeavesNull() {
            HumanTaskNode node = buildTaskNode(tb -> {
                // no reminder call
            });

            assertThat(node.reminders()).isNull();
        }
    }

    // --- reminderAt ---

    @Nested
    @DisplayName("reminderAt — one-shot offsets")
    class ReminderAt {

        @Test
        @DisplayName("reminderAt(1h) produces OneShotOffsets with one offset")
        void singleOffset() {
            HumanTaskNode node = buildTaskNode(tb -> tb.reminderAt(Duration.ofHours(1)));

            assertThat(node.reminders()).isInstanceOf(ReminderSpec.OneShotOffsets.class);
            ReminderSpec.OneShotOffsets spec = (ReminderSpec.OneShotOffsets) node.reminders();
            assertThat(spec.offsetsFromTaskCreation()).containsExactly(Duration.ofHours(1));
        }

        @Test
        @DisplayName("reminderAt(1h, 2h, 3h) produces OneShotOffsets with three offsets in order")
        void multipleOffsets() {
            HumanTaskNode node =
                    buildTaskNode(tb -> tb.reminderAt(Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(3)));

            assertThat(node.reminders()).isInstanceOf(ReminderSpec.OneShotOffsets.class);
            ReminderSpec.OneShotOffsets spec = (ReminderSpec.OneShotOffsets) node.reminders();
            assertThat(spec.offsetsFromTaskCreation())
                    .containsExactly(Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(3));
        }

        @Test
        @DisplayName("reminderAt with zero duration delegates to ReminderSpec validation and throws")
        void zeroDurationRejected() {
            assertThatIllegalArgumentException().isThrownBy(() -> buildTaskNode(tb -> tb.reminderAt(Duration.ZERO)));
        }

        @Test
        @DisplayName("reminderAt with negative duration delegates to ReminderSpec validation and throws")
        void negativeDurationRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> buildTaskNode(tb -> tb.reminderAt(Duration.ofHours(-1))));
        }
    }

    // --- reminderEvery (bounded) ---

    @Nested
    @DisplayName("reminderEvery — bounded recurring interval")
    class ReminderEveryBounded {

        @Test
        @DisplayName("reminderEvery(2h, 5) produces RecurringInterval(2h, 5)")
        void boundedRecurring() {
            HumanTaskNode node = buildTaskNode(tb -> tb.reminderEvery(Duration.ofHours(2), 5));

            assertThat(node.reminders()).isInstanceOf(ReminderSpec.RecurringInterval.class);
            ReminderSpec.RecurringInterval spec = (ReminderSpec.RecurringInterval) node.reminders();
            assertThat(spec.interval()).isEqualTo(Duration.ofHours(2));
            assertThat(spec.maxFires()).isEqualTo(5);
        }

        @Test
        @DisplayName("reminderEvery with zero interval throws")
        void zeroIntervalRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> buildTaskNode(tb -> tb.reminderEvery(Duration.ZERO, 3)));
        }

        @Test
        @DisplayName("reminderEvery with maxFires=0 throws")
        void zeroMaxFiresRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> buildTaskNode(tb -> tb.reminderEvery(Duration.ofHours(1), 0)));
        }

        @Test
        @DisplayName("reminderEvery with maxFires=-1 throws")
        void negativeMaxFiresRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> buildTaskNode(tb -> tb.reminderEvery(Duration.ofHours(1), -1)));
        }
    }

    // --- reminderEvery (unbounded) ---

    @Nested
    @DisplayName("reminderEvery — unbounded recurring interval")
    class ReminderEveryUnbounded {

        @Test
        @DisplayName("reminderEvery(2h) produces RecurringInterval(2h, null)")
        void unboundedRecurring() {
            HumanTaskNode node = buildTaskNode(tb -> tb.reminderEvery(Duration.ofHours(2)));

            assertThat(node.reminders()).isInstanceOf(ReminderSpec.RecurringInterval.class);
            ReminderSpec.RecurringInterval spec = (ReminderSpec.RecurringInterval) node.reminders();
            assertThat(spec.interval()).isEqualTo(Duration.ofHours(2));
            assertThat(spec.maxFires()).isNull();
        }

        @Test
        @DisplayName("reminderEvery(2h) and reminderEvery(2h, null) produce equal spec")
        void unboundedEqualsExplicitNull() {
            HumanTaskNode unbounded = buildTaskNode(tb -> tb.reminderEvery(Duration.ofHours(2)));
            // Build a second plan with an explicit null maxFires via direct ReminderSpec construction
            // to verify the null-maxFires shape is the same.
            ReminderSpec directSpec = new ReminderSpec.RecurringInterval(Duration.ofHours(2), null);
            assertThat(unbounded.reminders()).isEqualTo(directSpec);
        }
    }

    // --- Mutual exclusion ---

    @Nested
    @DisplayName("reminderAt / reminderEvery mutual exclusion")
    class MutualExclusion {

        @Test
        @DisplayName("calling reminderAt after reminderAt throws IllegalStateException")
        void reminderAtThenReminderAtThrows() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> buildTaskNode(
                            tb -> tb.reminderAt(Duration.ofHours(1)).reminderAt(Duration.ofHours(2))))
                    .withMessageContaining("reminders already configured");
        }

        @Test
        @DisplayName("calling reminderEvery after reminderAt throws IllegalStateException")
        void reminderAtThenReminderEveryThrows() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> buildTaskNode(
                            tb -> tb.reminderAt(Duration.ofHours(1)).reminderEvery(Duration.ofHours(2))))
                    .withMessageContaining("reminders already configured");
        }

        @Test
        @DisplayName("calling reminderAt after reminderEvery throws IllegalStateException")
        void reminderEveryThenReminderAtThrows() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> buildTaskNode(
                            tb -> tb.reminderEvery(Duration.ofHours(2)).reminderAt(Duration.ofHours(1))))
                    .withMessageContaining("reminders already configured");
        }

        @Test
        @DisplayName("calling reminderEvery(bounded) after reminderEvery(bounded) throws")
        void reminderEveryThenReminderEveryThrows() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> buildTaskNode(
                            tb -> tb.reminderEvery(Duration.ofHours(2), 3).reminderEvery(Duration.ofHours(1), 5)))
                    .withMessageContaining("reminders already configured");
        }
    }
}
