// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.types.WorkflowType;
import io.vertx.core.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WorkflowPlan#planHash()} is deterministic across multiple builder runs
 * (simulating multiple JVM restarts) and changes when the DSL structure changes.
 *
 * <p>The hash is computed over the plan's structural content (node ids, callback id strings, and
 * ordering). Non-deterministic lambda identities must NOT influence the hash; only the stable
 * step-id and signal-name strings matter.
 */
class WorkflowPlanHashTest {

    record OrderState(String id) {}

    record StartCmd(String id) {}

    record SignalPayload(String data) {}

    // --- Unannotated fixture types: their binary names are part of the golden hash, so do not rename them ---

    record GoldenState(String id) {}

    record GoldenStart(String id) {}

    record GoldenSignal(String data) {}

    record GoldenDecision(boolean approved) {}

    @WorkflowContract
    interface GoldenContract {
        @WorkflowSignal("order.paid")
        Future<Void> orderPaid(WorkflowInstanceId id, GoldenSignal payload, @SignalDedupKey String dedupKey);

        @WorkflowSignal("order.shipped")
        Future<Void> orderShipped(WorkflowInstanceId id, GoldenSignal payload, @SignalDedupKey String dedupKey);
    }

    // --- Fixture types for logical naming: annotated types and their unannotated twins ---

    @WorkflowType("order")
    record NamedState(String id) {}

    @WorkflowType("order.paid")
    record NamedPaid(String data) {}

    @WorkflowType("order.approval")
    record NamedApproval(boolean approved) {}

    record PlainState(String id) {}

    record PlainPaid(String data) {}

    record PlainApproval(boolean approved) {}

    @WorkflowContract
    interface TypedContract {
        @WorkflowSignal("order.paid")
        Future<Void> orderPaid(WorkflowInstanceId id, Object payload, @SignalDedupKey String dedupKey);
    }

    @WorkflowContract("orders")
    interface OrdersContract {
        @WorkflowSignal("order.paid")
        Future<Void> orderPaid(WorkflowInstanceId id, Object payload, @SignalDedupKey String dedupKey);
    }

    @WorkflowContract("other")
    interface OtherContract {
        @WorkflowSignal("order.paid")
        Future<Void> orderPaid(WorkflowInstanceId id, Object payload, @SignalDedupKey String dedupKey);
    }

    /** Builds plan A: charge → ship → wait-signal → complete. */
    private static WorkflowPlan buildPlanA() {
        WorkflowBuilder<OrderState> wf = new WorkflowBuilder<>();
        wf.init(StartCmd.class, cmd -> new OrderState(cmd.id()))
                .initialStep("charge")
                .dispatch("charge", "payment-svc", s -> new Object(), "ship")
                .dispatch("ship", "fulfillment-svc", s -> new Object(), "wait-confirmed")
                .waitFor(
                        "wait-confirmed",
                        "order.confirmed",
                        SignalPayload.class,
                        (s, p) -> new OrderState(s.id()),
                        "done")
                .complete("done");
        return wf.build("order-saga", 1L);
    }

    /** Builds plan B: different structure (extra step). */
    private static WorkflowPlan buildPlanB() {
        WorkflowBuilder<OrderState> wf = new WorkflowBuilder<>();
        wf.init(StartCmd.class, cmd -> new OrderState(cmd.id()))
                .initialStep("charge")
                .dispatch("charge", "payment-svc", s -> new Object(), "extra")
                .dispatch("extra", "notify-svc", s -> new Object(), "done")
                .complete("done");
        return wf.build("order-saga", 1L);
    }

    @Nested
    @DisplayName("determinism across builder runs")
    class Determinism {

        @Test
        @DisplayName("two independent runs of the same DSL produce equal planHash")
        void sameDslProducesEqualHash() {
            WorkflowPlan first = buildPlanA();
            WorkflowPlan second = buildPlanA();

            assertThat(first.planHash())
                    .as("planHash must be deterministic across builder runs")
                    .isEqualTo(second.planHash());
        }

        @Test
        @DisplayName("planHash is a 64-character hex string (SHA-256)")
        void hashIsHexSha256() {
            WorkflowPlan plan = buildPlanA();

            assertThat(plan.planHash())
                    .as("planHash should be a 64-char lowercase hex SHA-256 digest")
                    .matches("[0-9a-f]{64}");
        }
    }

    @Nested
    @DisplayName("structural sensitivity")
    class StructuralSensitivity {

        @Test
        @DisplayName("plan with different steps produces a different planHash")
        void differentStructureProducesDifferentHash() {
            WorkflowPlan planA = buildPlanA();
            WorkflowPlan planB = buildPlanB();

            assertThat(planA.planHash())
                    .as("structurally different plans must have different hashes")
                    .isNotEqualTo(planB.planHash());
        }

        @Test
        @DisplayName("plan with different signal name produces a different planHash")
        void differentSignalNameProducesDifferentHash() {
            WorkflowBuilder<OrderState> wf1 = new WorkflowBuilder<>();
            wf1.init(StartCmd.class, cmd -> new OrderState(cmd.id()))
                    .initialStep("wait")
                    .waitFor("wait", "signal.A", SignalPayload.class, (s, p) -> s, "done")
                    .complete("done");
            WorkflowPlan planSignalA = wf1.build("test", 1L);

            WorkflowBuilder<OrderState> wf2 = new WorkflowBuilder<>();
            wf2.init(StartCmd.class, cmd -> new OrderState(cmd.id()))
                    .initialStep("wait")
                    .waitFor("wait", "signal.B", SignalPayload.class, (s, p) -> s, "done")
                    .complete("done");
            WorkflowPlan planSignalB = wf2.build("test", 1L);

            assertThat(planSignalA.planHash()).isNotEqualTo(planSignalB.planHash());
        }
    }

    /**
     * Plan hash of {@code golden-saga} v1 with unannotated state, signal and decision classes, captured by
     * registering this definition at revision {@code 778e8407}, before any logical type naming existed.
     */
    private static final String GOLDEN_UNANNOTATED_PLAN_HASH =
            "268d4fa429daf6dc490b4962b88b142216e0f685c1d2ebb096179157b4e00a73";

    @Test
    @DisplayName("an unannotated definition registers with the plan hash it had before logical type names")
    void unannotatedRegistryHashMatchesBaselineGolden() {
        // Given: a definition whose state, start, signal and decision classes carry no annotation
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();

        // When: it registers
        registry.register(new WorkflowDefinition<GoldenState, GoldenContract>() {
            @Override
            public Class<GoldenContract> contract() {
                return GoldenContract.class;
            }

            @Override
            public Class<GoldenState> stateType() {
                return GoldenState.class;
            }

            @Override
            public String definitionId() {
                return "golden-saga";
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<GoldenState> wf) {
                wf.init(GoldenStart.class, start -> new GoldenState(start.id()))
                        .initialStep("charge")
                        .dispatch("charge", "payment-svc", state -> new Object(), "wait-paid")
                        .waitForSignal("wait-paid", "order.paid", GoldenSignal.class)
                        .onSignal((state, signal) -> state)
                        .toStepOnSignal("wait-shipped")
                        .build()
                        .waitFor(
                                "wait-shipped", "order.shipped", GoldenSignal.class, (state, signal) -> state, "review")
                        .task("review")
                        .assignToRole("reviewers")
                        .decision("approve", GoldenDecision.class)
                        .onDecision((state, decision) -> state)
                        .toStep("done")
                        .build()
                        .complete("done");
            }
        });

        // Then: the plan hash is the one captured before the change
        assertThat(registry.resolvePinned("golden-saga", 1L).plan().planHash()).isEqualTo(GOLDEN_UNANNOTATED_PLAN_HASH);
    }

    @Test
    @DisplayName("annotated state, signal and decision types enter the plan by logical name and change the hash")
    void annotatedTypesAreNamedAndHashedLogically() {
        // Given: a definition over annotated types and an identical twin over unannotated copies, each in
        // its own registry
        DefaultWorkflowRegistry annotatedRegistry = new DefaultWorkflowRegistry();
        DefaultWorkflowRegistry twinRegistry = new DefaultWorkflowRegistry();

        // When: both register
        annotatedRegistry.register(
                definition(TypedContract.class, "typed-saga", NamedState.class, NamedPaid.class, NamedApproval.class));
        twinRegistry.register(
                definition(TypedContract.class, "typed-saga", PlainState.class, PlainPaid.class, PlainApproval.class));

        // Then: the annotated plan carries the logical names
        WorkflowPlan plan = annotatedRegistry.resolvePinned("typed-saga", 1L).plan();
        assertThat(plan.stateTypeName()).isEqualTo("order");
        assertThat(waitNode(plan).payloadTypeName()).isEqualTo("order.paid");
        assertThat(decisionNode(plan).decisions().get(0).payloadTypeName()).isEqualTo("order.approval");

        // And: the hash digests those names, and so differs from the twin's
        assertThat(plan.planHash()).isEqualTo(WorkflowBuilder.computePlanHashForTesting(plan));
        assertThat(plan.planHash())
                .isNotEqualTo(
                        twinRegistry.resolvePinned("typed-saga", 1L).plan().planHash());
    }

    @Test
    @DisplayName("the contract name does not affect the plan hash")
    void contractNameDoesNotAffectPlanHash() {
        // Given: two definitions identical except for the name on their contract interfaces
        DefaultWorkflowRegistry ordersRegistry = new DefaultWorkflowRegistry();
        DefaultWorkflowRegistry otherRegistry = new DefaultWorkflowRegistry();

        // When: each registers in its own registry
        ordersRegistry.register(
                definition(OrdersContract.class, "x", PlainState.class, PlainPaid.class, PlainApproval.class));
        otherRegistry.register(
                definition(OtherContract.class, "x", PlainState.class, PlainPaid.class, PlainApproval.class));

        // Then: the plans, hashes included, are equal
        WorkflowPlan ordersPlan = ordersRegistry.resolvePinned("x", 1L).plan();
        WorkflowPlan otherPlan = otherRegistry.resolvePinned("x", 1L).plan();
        assertThat(ordersPlan.planHash()).isEqualTo(otherPlan.planHash());
        assertThat(ordersPlan).isEqualTo(otherPlan);
    }

    /** Waits for {@code paid}, then asks for one {@code approve} decision of type {@code approval}. */
    private static <S, C, P, A> WorkflowDefinition<S, C> definition(
            Class<C> contract, String definitionId, Class<S> state, Class<P> paid, Class<A> approval) {
        return new WorkflowDefinition<>() {
            @Override
            public Class<C> contract() {
                return contract;
            }

            @Override
            public Class<S> stateType() {
                return state;
            }

            @Override
            public String definitionId() {
                return definitionId;
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<S> wf) {
                wf.init(StartCmd.class, start -> null)
                        .initialStep("wait-paid")
                        .waitForSignal("wait-paid", "order.paid", paid)
                        .onSignal((current, signal) -> current)
                        .toStepOnSignal("review")
                        .build()
                        .task("review")
                        .assignToRole("reviewers")
                        .decision("approve", approval)
                        .onDecision((current, decision) -> current)
                        .toStep("done")
                        .build()
                        .complete("done");
            }
        };
    }

    private static WaitSignalNode waitNode(WorkflowPlan plan) {
        return plan.nodes().stream()
                .filter(WaitSignalNode.class::isInstance)
                .map(WaitSignalNode.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static HumanTaskNode decisionNode(WorkflowPlan plan) {
        return plan.nodes().stream()
                .filter(HumanTaskNode.class::isInstance)
                .map(HumanTaskNode.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
