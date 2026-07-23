// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.tasks.TaskAssignment;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link WorkflowCallbackRegistry#decisionApplicator} and
 * {@link WorkflowCallbackRegistry#taskAssignmentResolver} accessors: default-impl UnsupportedOperationException,
 * and the runtime registry returned by {@link WorkflowBuilder#callbackRegistry()} after a
 * {@code .task(...)} build.
 */
class WorkflowCallbackRegistryTaskTest {

    record State(String value) {}

    record Cmd(String seed) {}

    record ApprovalPayload(String note) {}

    // --- Default implementation throws UnsupportedOperationException ---

    @Nested
    @DisplayName("default implementation")
    class DefaultImpl {

        private final WorkflowCallbackRegistry defaultRegistry = new WorkflowCallbackRegistry() {
            @Override
            public <S> Function<S, Object> payloadFactory(CallbackId id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <S> BiFunction<S, Object, S> stateUpdater(CallbackId id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <S> Function<S, String> decisionResolver(CallbackId id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <S> Function<S, String> failMessageFactory(CallbackId id) {
                throw new UnsupportedOperationException();
            }
        };

        @Test
        @DisplayName("decisionApplicator throws UnsupportedOperationException by default")
        void decisionApplicatorDefaultThrows() {
            assertThatThrownBy(() -> defaultRegistry.decisionApplicator(new CallbackId("any")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("taskAssignmentResolver throws UnsupportedOperationException by default")
        void taskAssignmentResolverDefaultThrows() {
            assertThatThrownBy(() -> defaultRegistry.taskAssignmentResolver(new CallbackId("any")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // --- Runtime registry from WorkflowBuilder.callbackRegistry() ---

    @Nested
    @DisplayName("runtime registry populated by WorkflowBuilder")
    class RuntimeRegistry {

        private WorkflowBuilder<State> buildTaskPlan() {
            WorkflowBuilder<State> wf = new WorkflowBuilder<>();
            wf.init(Cmd.class, cmd -> new State(cmd.seed()))
                    .initialStep("start")
                    .dispatch("start", "svc", s -> new Object(), "review")
                    .task("review")
                    .assignToUser((State s) -> s.value())
                    .decision("approve", ApprovalPayload.class)
                    .onDecision((s, p) -> new State(s.value() + ":" + p.note()))
                    .toStep("done")
                    .build()
                    .complete("done");
            return wf;
        }

        @Test
        @DisplayName("decisionApplicator returns the registered BiFunction; invoking it yields updated state")
        void decisionApplicatorRoundTrip() {
            WorkflowBuilder<State> wf = buildTaskPlan();
            // Use the 3-arg public overload (2-arg is package-private to dev.vertique.workflow.dsl)
            wf.build("def", 1L, "");

            WorkflowCallbackRegistry callbacks = wf.callbackRegistry();

            // Locate the applicator callback id from the built plan's node
            dev.vertique.workflow.plan.HumanTaskNode node =
                    (dev.vertique.workflow.plan.HumanTaskNode) wf.build("def", 1L, "").nodes().stream()
                            .filter(n -> n.stepId().equals("review"))
                            .findFirst()
                            .orElseThrow();

            BiFunction<State, ApprovalPayload, State> applicator =
                    callbacks.decisionApplicator(node.decisions().get(0).applicatorCallbackId());
            assertThat(applicator).isNotNull();
            State result = applicator.apply(new State("pending"), new ApprovalPayload("ok"));
            assertThat(result.value()).isEqualTo("pending:ok");
        }

        @Test
        @DisplayName(
                "taskAssignmentResolver returns the registered Function; invoking it yields the expected assignment")
        void taskAssignmentResolverRoundTrip() {
            WorkflowBuilder<State> wf = buildTaskPlan();

            dev.vertique.workflow.plan.HumanTaskNode node =
                    (dev.vertique.workflow.plan.HumanTaskNode) wf.build("def", 1L, "").nodes().stream()
                            .filter(n -> n.stepId().equals("review"))
                            .findFirst()
                            .orElseThrow();

            assertThat(node.assignment())
                    .isInstanceOf(dev.vertique.workflow.plan.HumanTaskNode.AssignmentSpec.UserFromState.class);

            dev.vertique.workflow.plan.HumanTaskNode.AssignmentSpec.UserFromState fromState =
                    (dev.vertique.workflow.plan.HumanTaskNode.AssignmentSpec.UserFromState) node.assignment();

            WorkflowCallbackRegistry callbacks = wf.callbackRegistry();
            Function<State, TaskAssignment> resolver = callbacks.taskAssignmentResolver(fromState.resolverCallbackId());
            assertThat(resolver).isNotNull();
            assertThat(resolver.apply(new State("alice"))).isEqualTo(new TaskAssignment.User("alice"));
        }

        @Test
        @DisplayName("decisionApplicator(unknownCallbackId) throws IllegalArgumentException")
        void unknownDecisionApplicatorThrows() {
            WorkflowBuilder<State> wf = buildTaskPlan();
            WorkflowCallbackRegistry callbacks = wf.callbackRegistry();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> callbacks.decisionApplicator(new CallbackId("no-such-id")));
        }

        @Test
        @DisplayName("taskAssignmentResolver(unknownCallbackId) throws IllegalArgumentException")
        void unknownTaskAssignmentResolverThrows() {
            WorkflowBuilder<State> wf = buildTaskPlan();
            WorkflowCallbackRegistry callbacks = wf.callbackRegistry();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> callbacks.taskAssignmentResolver(new CallbackId("no-such-id")));
        }
    }
}
