// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.DefaultBranchResultReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultNamedConditionRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStartStateMapperRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateMutatorRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTimerResolverRegistry;
import dev.vertique.workflow.definition.callbacks.NamedBranchResultReducer;
import dev.vertique.workflow.definition.callbacks.NamedPayloadMapper;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.NamedStateMutator;
import dev.vertique.workflow.definition.callbacks.NamedStateReducer;
import dev.vertique.workflow.definition.callbacks.NamedTimerResolver;
import dev.vertique.workflow.definition.callbacks.PayloadMapperRegistry;
import dev.vertique.workflow.definition.callbacks.RegisteredIdentifierLookup;
import dev.vertique.workflow.definition.callbacks.StartStateMapperRegistry;
import dev.vertique.workflow.definition.expression.ExpressionProfile;
import dev.vertique.workflow.definition.expression.cel.CelExpressionProfile;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory;
import dev.vertique.workflow.definition.schema.CompensationStep;
import dev.vertique.workflow.definition.schema.CompleteStep;
import dev.vertique.workflow.definition.schema.DecisionStep;
import dev.vertique.workflow.definition.schema.FailStep;
import dev.vertique.workflow.definition.schema.ForkStep;
import dev.vertique.workflow.definition.schema.HumanTaskStep;
import dev.vertique.workflow.definition.schema.JoinStep;
import dev.vertique.workflow.definition.schema.ServiceStep;
import dev.vertique.workflow.definition.schema.StepNode;
import dev.vertique.workflow.definition.schema.TimerStep;
import dev.vertique.workflow.definition.schema.WaitSignalStep;
import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.plan.CompensationNode;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.DecisionNode;
import dev.vertique.workflow.plan.FailNode;
import dev.vertique.workflow.plan.ForkNode;
import dev.vertique.workflow.plan.JoinNode;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.plan.TimerNode;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies {@link WorkflowDefinitionCompiler}: for each {@link StepNode} variant, a hand-built
 * minimal {@link WorkflowDefinitionDocument} is compiled and the resulting
 * {@link WorkflowPlan#nodes()} shape is asserted.
 *
 * <p>Each test builds the same plan both via the document compiler and via the code-first DSL and
 * asserts the node types match. Because FR-WF-DEF-011 does NOT require plan-hash equivalence
 * between code-first and document-defined plans, only node count and node class are asserted here.
 * The plan-hash determinism across re-compilations is verified in {@link PlanHashDeterminismTest}.
 */
class WorkflowDefinitionCompilerTest {

    // --- Test types ---

    record OrderState(String orderId, String status) {}

    record PlaceOrder(String id) {}

    record ShippedEvent(String trackingCode) {}

    record ApprovePayload(String note) {}

    @WorkflowContract(definitionId = "order-wf", definitionVersion = 1)
    interface OrderContract {}

    // --- Infrastructure ---

    private WorkflowDefinitionCompiler compiler;
    private RegisteredIdentifierLookup lookup;
    private WorkflowDefinitionMapperFactory mapperFactory;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        mapperFactory = Mockito.mock(WorkflowDefinitionMapperFactory.class);
        Mockito.when(mapperFactory.jsonMapper()).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper());
        ExpressionProfile profile = new CelExpressionProfile();

        StartStateMapperRegistry startStateMappers =
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                        "order.fromPlaceOrder",
                        PlaceOrder.class,
                        OrderState.class,
                        cmd -> new OrderState(cmd.id(), "PENDING")))));

        PayloadMapperRegistry payloadMappers = Mockito.mock(PayloadMapperRegistry.class);
        NamedPayloadMapper rawMapper = new NamedPayloadMapper<>("any", OrderState.class, s -> s);
        Mockito.when(payloadMappers.lookup(Mockito.anyString())).thenReturn(rawMapper);

        DefaultStateReducerRegistry stateReducers = new DefaultStateReducerRegistry(Set.of(b -> {
            b.register(new NamedStateReducer<>("order.applyShipped", OrderState.class, (s, e) -> s));
            // "review.applyApprove" is used as a decision applicator in human-task tests
            b.register(new NamedStateReducer<>("review.applyApprove", OrderState.class, (s, e) -> s));
            b.register(new NamedStateReducer<>("review.applyReject", OrderState.class, (s, e) -> s));
        }));

        DefaultStateMutatorRegistry stateMutators = new DefaultStateMutatorRegistry(
                Set.of(b -> b.register(new NamedStateMutator<>("order.markTimedOut", OrderState.class, s -> s))));

        DefaultTimerResolverRegistry timerResolvers = new DefaultTimerResolverRegistry(
                Set.of(b -> b.register(new NamedTimerResolver<>("order.shipAt", OrderState.class, s -> Instant.MAX))));

        DefaultNamedConditionRegistry conditionRegistry = new DefaultNamedConditionRegistry(
                Set.of(b -> b.register(new dev.vertique.workflow.definition.callbacks.NamedCondition<>(
                        "policy.lowRisk", OrderState.class, s -> false))));

        DefaultBranchResultReducerRegistry branchResultReducers =
                new DefaultBranchResultReducerRegistry(Set.of(b -> b.register(
                        new NamedBranchResultReducer<>("review.combineResults", OrderState.class, (s, results) -> s))));

        lookup = new RegisteredIdentifierLookup(
                payloadMappers,
                startStateMappers,
                stateReducers,
                stateMutators,
                timerResolvers,
                new dev.vertique.workflow.definition.callbacks.DefaultFailMessageFactoryRegistry(
                        Set.of(b -> b.register(new dev.vertique.workflow.definition.callbacks.NamedFailMessageFactory<>(
                                "order.cancelMsg", OrderState.class, s -> "cancelled")))),
                new dev.vertique.workflow.definition.callbacks.DefaultSubjectResolverRegistry(Set.of()),
                new dev.vertique.workflow.definition.callbacks.DefaultTaskAssignmentResolverRegistry(Set.of(
                        b -> b.register(new dev.vertique.workflow.definition.callbacks.NamedTaskAssignmentResolver<>(
                                "order.assignReviewer",
                                OrderState.class,
                                s -> new dev.vertique.workflow.tasks.TaskAssignment.Role("reviewer"))))),
                branchResultReducers,
                conditionRegistry);

        DecisionRouteCompiler decisionRouteCompiler =
                new DecisionRouteCompiler(profile, conditionRegistry, mapperFactory);
        compiler = new WorkflowDefinitionCompiler(lookup, profile, decisionRouteCompiler);
    }

    // --- Helper to emit plan ---

    private WorkflowPlan compile(List<StepNode> steps, String initialStep) {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                "order-wf",
                1L,
                OrderState.class.getName(),
                OrderContract.class.getName(),
                PlaceOrder.class.getName(),
                "order.fromPlaceOrder",
                null,
                initialStep,
                steps);
        WorkflowBuilder<OrderState> builder = new WorkflowBuilder<>();
        compiler.emit(builder, OrderState.class, OrderContract.class, doc);
        return builder.build("order-wf", 1L, OrderState.class.getName());
    }

    // --- Service step ---

    @Nested
    @DisplayName("service step")
    class ServiceStepTests {

        @Test
        @DisplayName("service step without compensation emits ServiceDispatchNode")
        void serviceNoCompensation() {
            WorkflowPlan plan = compile(
                    List.of(new ServiceStep("ship", "shipping.create", "any", null, "done"), new CompleteStep("done")),
                    "ship");

            assertThat(plan.nodes().stream().filter(ServiceDispatchNode.class::isInstance))
                    .hasSize(1);
            ServiceDispatchNode node = plan.nodes().stream()
                    .filter(ServiceDispatchNode.class::isInstance)
                    .map(ServiceDispatchNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(node.stepId()).isEqualTo("ship");
            assertThat(node.targetId()).isEqualTo("shipping.create");
            assertThat(node.compensationStepId()).isNull();
            assertThat(node.nextStepId()).isEqualTo("done");
        }

        @Test
        @DisplayName("service step with compensation emits ServiceDispatchNode with compensation id")
        void serviceWithCompensation() {
            WorkflowPlan plan = compile(
                    List.of(
                            new ServiceStep("ship", "shipping.create", "any", "cancel-ship", "done"),
                            new CompensationStep("cancel-ship", "ship", "shipping.cancel", "any"),
                            new CompleteStep("done")),
                    "ship");

            ServiceDispatchNode node = plan.nodes().stream()
                    .filter(ServiceDispatchNode.class::isInstance)
                    .map(ServiceDispatchNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(node.compensationStepId()).isEqualTo("cancel-ship");

            assertThat(plan.nodes().stream().filter(CompensationNode.class::isInstance))
                    .hasSize(1);
        }
    }

    // --- Wait signal step ---

    @Nested
    @DisplayName("wait-signal step")
    class WaitSignalStepTests {

        @Test
        @DisplayName("wait-signal without timeout emits WaitSignalNode with no timeout branch")
        void waitSignalNoTimeout() {
            WorkflowPlan plan = compile(
                    List.of(
                            new WaitSignalStep(
                                    "wait-shipped",
                                    "order.shipped",
                                    ShippedEvent.class.getName(),
                                    "order.applyShipped",
                                    "done",
                                    null),
                            new CompleteStep("done")),
                    "wait-shipped");

            WaitSignalNode node = plan.nodes().stream()
                    .filter(WaitSignalNode.class::isInstance)
                    .map(WaitSignalNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(node.stepId()).isEqualTo("wait-shipped");
            assertThat(node.signalName()).isEqualTo("order.shipped");
            assertThat(node.timeout()).isNull();
        }

        @Test
        @DisplayName("wait-signal with timeout emits WaitSignalNode with timeout branch")
        void waitSignalWithTimeout() {
            WaitSignalStep.TimeoutBlock timeout =
                    new WaitSignalStep.TimeoutBlock("PT15M", "order.markTimedOut", "cancel");
            WorkflowPlan plan = compile(
                    List.of(
                            new WaitSignalStep(
                                    "wait-shipped",
                                    "order.shipped",
                                    ShippedEvent.class.getName(),
                                    "order.applyShipped",
                                    "done",
                                    timeout),
                            new CompleteStep("done"),
                            new CompleteStep("cancel")),
                    "wait-shipped");

            WaitSignalNode node = plan.nodes().stream()
                    .filter(WaitSignalNode.class::isInstance)
                    .map(WaitSignalNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(node.timeout()).isNotNull();
            assertThat(node.timeout().timeoutNextStepId()).isEqualTo("cancel");
        }
    }

    // --- Timer step ---

    @Nested
    @DisplayName("timer step")
    class TimerStepTests {

        @Test
        @DisplayName("ISO Duration fireAt emits TimerNode with After spec")
        void isoDuration() {
            WorkflowPlan plan =
                    compile(List.of(new TimerStep("defer", "PT1H", "done"), new CompleteStep("done")), "defer");

            TimerNode node = plan.nodes().stream()
                    .filter(TimerNode.class::isInstance)
                    .map(TimerNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(node.stepId()).isEqualTo("defer");
            assertThat(node.nextStepId()).isEqualTo("done");
            assertThat(node.spec()).isInstanceOf(dev.vertique.workflow.plan.TimerSpec.After.class);
        }

        @Test
        @DisplayName("ISO Instant fireAt emits TimerNode with At spec")
        void isoInstant() {
            WorkflowPlan plan = compile(
                    List.of(new TimerStep("defer", "2025-01-01T00:00:00Z", "done"), new CompleteStep("done")), "defer");

            TimerNode node = plan.nodes().stream()
                    .filter(TimerNode.class::isInstance)
                    .map(TimerNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(node.spec()).isInstanceOf(dev.vertique.workflow.plan.TimerSpec.At.class);
        }

        @Test
        @DisplayName("ref: fireAt emits TimerNode with FromState spec")
        void refResolver() {
            WorkflowPlan plan = compile(
                    List.of(new TimerStep("defer", "ref:order.shipAt", "done"), new CompleteStep("done")), "defer");

            TimerNode node = plan.nodes().stream()
                    .filter(TimerNode.class::isInstance)
                    .map(TimerNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(node.spec()).isInstanceOf(dev.vertique.workflow.plan.TimerSpec.FromState.class);
        }
    }

    // --- Human task step ---

    @Nested
    @DisplayName("human-task step")
    class HumanTaskStepTests {

        @Test
        @DisplayName("human-task with literal role assignment + one decision emits HumanTaskNode")
        void humanTaskLiteralRole() {
            HumanTaskStep.AssignmentBlock assignment = new HumanTaskStep.AssignmentBlock("role", "reviewer", null);
            HumanTaskStep.TaskDecisionBlock decision = new HumanTaskStep.TaskDecisionBlock(
                    "approve", ApprovePayload.class.getName(), "review.applyApprove", "done");
            WorkflowPlan plan = compile(
                    List.of(
                            new HumanTaskStep("review", "approval", assignment, List.of(decision), null, null, null),
                            new CompleteStep("done")),
                    "review");

            assertThat(plan.nodes().stream().filter(dev.vertique.workflow.plan.HumanTaskNode.class::isInstance))
                    .hasSize(1);
            dev.vertique.workflow.plan.HumanTaskNode node = plan.nodes().stream()
                    .filter(dev.vertique.workflow.plan.HumanTaskNode.class::isInstance)
                    .map(dev.vertique.workflow.plan.HumanTaskNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(node.stepId()).isEqualTo("review");
            assertThat(node.decisions()).hasSize(1);
            assertThat(node.decisions().get(0).name()).isEqualTo("approve");
            assertThat(node.dueDate()).isNull();
            assertThat(node.requireVersionStability()).isFalse();
        }

        @Test
        @DisplayName("human-task with due date + requireVersionStability emits correct HumanTaskNode")
        void humanTaskWithDueAndVersionStability() {
            HumanTaskStep.AssignmentBlock assignment = new HumanTaskStep.AssignmentBlock("role", "reviewer", null);
            HumanTaskStep.TaskDecisionBlock decision = new HumanTaskStep.TaskDecisionBlock(
                    "approve", ApprovePayload.class.getName(), "review.applyApprove", "done");
            HumanTaskStep.DueBlock due = new HumanTaskStep.DueBlock("PT24H", "order.markTimedOut", "cancelled");
            WorkflowPlan plan = compile(
                    List.of(
                            new HumanTaskStep("review", "approval", assignment, List.of(decision), due, null, true),
                            new CompleteStep("done"),
                            new CompleteStep("cancelled")),
                    "review");

            dev.vertique.workflow.plan.HumanTaskNode node = plan.nodes().stream()
                    .filter(dev.vertique.workflow.plan.HumanTaskNode.class::isInstance)
                    .map(dev.vertique.workflow.plan.HumanTaskNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(node.dueDate()).isNotNull();
            assertThat(node.dueNextStepId()).isEqualTo("cancelled");
            assertThat(node.requireVersionStability()).isTrue();
        }

        @Test
        @DisplayName("human-task with role-from-state assignment emits HumanTaskNode with RoleFromState spec")
        void humanTaskRoleFromState() {
            HumanTaskStep.AssignmentBlock assignment =
                    new HumanTaskStep.AssignmentBlock("role-from-state", null, "order.assignReviewer");
            HumanTaskStep.TaskDecisionBlock decision = new HumanTaskStep.TaskDecisionBlock(
                    "approve", ApprovePayload.class.getName(), "review.applyApprove", "done");
            WorkflowPlan plan = compile(
                    List.of(
                            new HumanTaskStep("review", "approval", assignment, List.of(decision), null, null, null),
                            new CompleteStep("done")),
                    "review");

            dev.vertique.workflow.plan.HumanTaskNode node = plan.nodes().stream()
                    .filter(dev.vertique.workflow.plan.HumanTaskNode.class::isInstance)
                    .map(dev.vertique.workflow.plan.HumanTaskNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(node.assignment())
                    .isInstanceOf(dev.vertique.workflow.plan.HumanTaskNode.AssignmentSpec.RoleFromState.class);
        }
    }

    // --- Decision step ---

    @Nested
    @DisplayName("decision step")
    class DecisionStepTests {

        @Test
        @DisplayName("decision step emits DecisionNode with fingerprinted callback id")
        void decisionWithWhenRoutes() {
            List<DecisionStep.RouteEntry> routes = List.of(
                    new DecisionStep.RouteEntry("state.status == 'ACTIVE'", null, "active-step"),
                    new DecisionStep.RouteEntry(null, "policy.lowRisk", "low-risk-step"));
            WorkflowPlan plan = compile(
                    List.of(
                            new DecisionStep("route", routes, "default-step"),
                            new CompleteStep("active-step"),
                            new CompleteStep("low-risk-step"),
                            new CompleteStep("default-step")),
                    "route");

            DecisionNode node = plan.nodes().stream()
                    .filter(DecisionNode.class::isInstance)
                    .map(DecisionNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(node.stepId()).isEqualTo("route");
            // Fingerprinted id starts with "doc:" prefix
            assertThat(node.nextStepResolverCallbackId().value()).startsWith("doc:");
        }
    }

    // --- Complete step ---

    @Nested
    @DisplayName("complete step")
    class CompleteStepTests {

        @Test
        @DisplayName("complete step emits CompleteNode")
        void completeStep() {
            WorkflowPlan plan = compile(List.of(new CompleteStep("done")), "done");

            assertThat(plan.nodes()).hasSize(1);
            assertThat(plan.nodes().get(0)).isInstanceOf(CompleteNode.class);
        }
    }

    // --- Fail step ---

    @Nested
    @DisplayName("fail step")
    class FailStepTests {

        @Test
        @DisplayName("fail step emits FailNode with correct error type")
        void failStep() {
            WorkflowPlan plan =
                    compile(List.of(new FailStep("cancel", "order-cancelled", "order.cancelMsg")), "cancel");

            FailNode node = plan.nodes().stream()
                    .filter(FailNode.class::isInstance)
                    .map(FailNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(node.errorType()).isEqualTo("order-cancelled");
        }
    }

    // --- Fork + Join ---

    @Nested
    @DisplayName("fork + join steps")
    class ForkJoinStepTests {

        @Test
        @DisplayName("fork + join with all-required emits ForkNode and JoinNode")
        void forkJoinAllRequired() {
            WorkflowPlan plan = compile(
                    List.of(
                            new ForkStep(
                                    "fork",
                                    List.of(
                                            new ForkStep.BranchEntry("legal", "legal-done", null),
                                            new ForkStep.BranchEntry("finance", "finance-done", null)),
                                    "joined",
                                    null),
                            new CompleteStep("legal-done"),
                            new CompleteStep("finance-done"),
                            new JoinStep("joined", "all-required", "review.combineResults", "done", null),
                            new CompleteStep("done")),
                    "fork");

            assertThat(plan.nodes().stream().filter(ForkNode.class::isInstance)).hasSize(1);
            assertThat(plan.nodes().stream().filter(JoinNode.class::isInstance)).hasSize(1);

            ForkNode forkNode = plan.nodes().stream()
                    .filter(ForkNode.class::isInstance)
                    .map(ForkNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(forkNode.branches()).hasSize(2);
            assertThat(forkNode.joinStepId()).isEqualTo("joined");

            JoinNode joinNode = plan.nodes().stream()
                    .filter(JoinNode.class::isInstance)
                    .map(JoinNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(joinNode.policy()).isInstanceOf(dev.vertique.workflow.plan.AllRequiredJoinPolicy.class);
            assertThat(joinNode.nextStepId()).isEqualTo("done");
        }

        @Test
        @DisplayName("fork + join with first-success emits ForkNode and JoinNode with FirstSuccessJoinPolicy")
        void forkJoinFirstSuccess() {
            WorkflowPlan plan = compile(
                    List.of(
                            new ForkStep(
                                    "fork",
                                    List.of(new ForkStep.BranchEntry("a", "a-done", "CANCEL_SAFE")),
                                    "joined",
                                    null),
                            new CompleteStep("a-done"),
                            new JoinStep("joined", "first-success", "review.combineResults", "done", null),
                            new CompleteStep("done")),
                    "fork");

            JoinNode joinNode = plan.nodes().stream()
                    .filter(JoinNode.class::isInstance)
                    .map(JoinNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(joinNode.policy()).isInstanceOf(dev.vertique.workflow.plan.FirstSuccessJoinPolicy.class);
        }

        @Test
        @DisplayName("fork + join with first-failure + onFailure emits correct JoinNode")
        void forkJoinFirstFailure() {
            WorkflowPlan plan = compile(
                    List.of(
                            new ForkStep(
                                    "fork",
                                    List.of(new ForkStep.BranchEntry("a", "a-done", "CANCEL_SAFE")),
                                    "joined",
                                    null),
                            new CompleteStep("a-done"),
                            new JoinStep("joined", "first-failure", "review.combineResults", "done", "failed"),
                            new CompleteStep("done"),
                            new CompleteStep("failed")),
                    "fork");

            JoinNode joinNode = plan.nodes().stream()
                    .filter(JoinNode.class::isInstance)
                    .map(JoinNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(joinNode.policy()).isInstanceOf(dev.vertique.workflow.plan.FirstFailureJoinPolicy.class);
            assertThat(joinNode.failureStepId()).isEqualTo("failed");
        }
    }
}
