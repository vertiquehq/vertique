// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.plan.WorkflowPlan;
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
}
