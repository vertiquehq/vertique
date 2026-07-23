// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.plan.TimerNode;
import dev.vertique.workflow.plan.TimerSpec;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the cycle-2 DSL extensions on {@link WorkflowBuilder}: standalone {@code .timer(...)} /
 * {@code .timerAt(...)} steps, the new {@code .waitForSignal(...)} fluent chain with optional
 * {@code .timeoutAfter(...)} branch, and the corresponding plan-hash drift detection for
 * {@link TimerSpec} payload changes.
 */
class WorkflowBuilderTimerDslTest {

    record State(int value) {}

    record Cmd(int seed) {}

    record SignalPayload(int value) {}

    private static WorkflowBuilder<State> baseBuilder() {
        WorkflowBuilder<State> wf = new WorkflowBuilder<>();
        wf.init(Cmd.class, cmd -> new State(cmd.seed())).initialStep("start");
        return wf;
    }

    @Nested
    @DisplayName("standalone timer DSL")
    class StandaloneTimer {

        @Test
        @DisplayName(".timer(stepId, Duration).toStep(next) emits TimerNode with TimerSpec.After")
        void durationTimer() {
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "wait-1h")
                    .timer("wait-1h", Duration.ofHours(1))
                    .toStep("done")
                    .complete("done");
            WorkflowPlan plan = wf.build("def", 1L);

            TimerNode timer = (TimerNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("wait-1h"))
                    .findFirst()
                    .orElseThrow();
            assertThat(timer.nextStepId()).isEqualTo("done");
            assertThat(timer.spec()).isInstanceOf(TimerSpec.After.class);
            assertThat(((TimerSpec.After) timer.spec()).delay()).isEqualTo(Duration.ofHours(1));
        }

        @Test
        @DisplayName(".timer(stepId, Instant).toStep(next) emits TimerNode with TimerSpec.At")
        void instantTimer() {
            Instant fireAt = Instant.parse("2030-01-01T00:00:00Z");
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "wait-until")
                    .timer("wait-until", fireAt)
                    .toStep("done")
                    .complete("done");
            WorkflowPlan plan = wf.build("def", 1L);

            TimerNode timer = (TimerNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("wait-until"))
                    .findFirst()
                    .orElseThrow();
            assertThat(timer.spec()).isInstanceOf(TimerSpec.At.class);
            assertThat(((TimerSpec.At) timer.spec()).fireAt()).isEqualTo(fireAt);
        }

        @Test
        @DisplayName(".timerAt(stepId, resolver).toStep(next) registers callback and emits TimerSpec.FromState")
        void fromStateTimer() {
            Instant resolved = Instant.parse("2031-06-15T12:00:00Z");
            Function<State, Instant> resolver = s -> resolved;

            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "wait-state")
                    .timerAt("wait-state", resolver)
                    .toStep("done")
                    .complete("done");
            WorkflowPlan plan = wf.build("def", 1L);

            TimerNode timer = (TimerNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("wait-state"))
                    .findFirst()
                    .orElseThrow();
            assertThat(timer.spec()).isInstanceOf(TimerSpec.FromState.class);

            TimerSpec.FromState spec = (TimerSpec.FromState) timer.spec();
            assertThat(spec.resolverCallbackId()).isNotNull();

            WorkflowCallbackRegistry callbacks = wf.callbackRegistry();
            Function<State, Instant> registered = callbacks.timerResolver(spec.resolverCallbackId());
            assertThat(registered).isNotNull();
            assertThat(registered.apply(new State(0))).isEqualTo(resolved);
        }

        @Test
        @DisplayName("duplicate stepId — two timers with the same id — is rejected at the second .timer(...) call")
        void duplicateStepIdRejected() {
            WorkflowBuilder<State> wf = baseBuilder();
            // A dispatch and a timer share stepId "shared" — the second declaration must throw.
            wf.dispatch("shared", "svc", s -> new Object(), "wait");
            assertThatThrownBy(() -> wf.timer("shared", Duration.ofHours(1)))
                    .isInstanceOf(WorkflowDefinitionException.class);
        }

        @Test
        @DisplayName("TimerNode.nextStepId referencing a missing step is rejected at build()")
        void timerMissingNextStepIdRejected() {
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "wait-1h")
                    .timer("wait-1h", Duration.ofMinutes(5))
                    .toStep("nonexistent")
                    .complete("done");
            assertThatThrownBy(() -> wf.build("def", 1L)).isInstanceOf(WorkflowDefinitionException.class);
        }
    }

    @Nested
    @DisplayName("waitForSignal DSL")
    class WaitForSignalDsl {

        @Test
        @DisplayName("no-timeout chain emits WaitSignalNode with timeout=null")
        void noTimeout() {
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "wait-shipped")
                    .waitForSignal("wait-shipped", "order.shipped", SignalPayload.class)
                    .onSignal((s, p) -> new State(p.value()))
                    .toStepOnSignal("done")
                    .build()
                    .complete("done");

            WorkflowPlan plan = wf.build("def", 1L);
            WaitSignalNode wait = (WaitSignalNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("wait-shipped"))
                    .findFirst()
                    .orElseThrow();
            assertThat(wait.signalName()).isEqualTo("order.shipped");
            assertThat(wait.payloadTypeName()).isEqualTo(SignalPayload.class.getName());
            assertThat(wait.nextStepId()).isEqualTo("done");
            assertThat(wait.timeout()).isNull();

            WorkflowCallbackRegistry callbacks = wf.callbackRegistry();
            assertThat(callbacks.stateUpdater(wait.stateUpdaterCallbackId())).isNotNull();
        }

        @Test
        @DisplayName(".timeoutAfter(...).onTimeout(...).toStepOnTimeout(...) emits TimeoutBranch with mutator")
        void timeoutAfterWithMutator() {
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "wait-payment")
                    .waitForSignal("wait-payment", "payment.captured", SignalPayload.class)
                    .onSignal((s, p) -> new State(p.value()))
                    .toStepOnSignal("ok")
                    .timeoutAfter(Duration.ofHours(1))
                    .onTimeout(s -> new State(-1))
                    .toStepOnTimeout("cancelled")
                    .complete("ok")
                    .complete("cancelled");

            WorkflowPlan plan = wf.build("def", 1L);
            WaitSignalNode wait = (WaitSignalNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("wait-payment"))
                    .findFirst()
                    .orElseThrow();
            assertThat(wait.timeout()).isNotNull();
            assertThat(wait.timeout().timeout()).isInstanceOf(TimerSpec.After.class);
            assertThat(((TimerSpec.After) wait.timeout().timeout()).delay()).isEqualTo(Duration.ofHours(1));
            assertThat(wait.timeout().timeoutNextStepId()).isEqualTo("cancelled");

            WorkflowCallbackRegistry callbacks = wf.callbackRegistry();
            Function<State, State> mutator =
                    callbacks.stateMutator(wait.timeout().onTimeoutMutatorCallbackId());
            assertThat(mutator).isNotNull();
            assertThat(mutator.apply(new State(42))).isEqualTo(new State(-1));
        }

        @Test
        @DisplayName(".timeoutAfter(...).toStepOnTimeout(...) without explicit onTimeout registers identity mutator")
        void timeoutAfterIdentityMutator() {
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "wait-payment")
                    .waitForSignal("wait-payment", "payment.captured", SignalPayload.class)
                    .onSignal((s, p) -> new State(p.value()))
                    .toStepOnSignal("ok")
                    .timeoutAfter(Duration.ofMinutes(5))
                    .toStepOnTimeout("cancelled")
                    .complete("ok")
                    .complete("cancelled");

            WorkflowPlan plan = wf.build("def", 1L);
            WaitSignalNode wait = (WaitSignalNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("wait-payment"))
                    .findFirst()
                    .orElseThrow();
            assertThat(wait.timeout()).isNotNull();

            WorkflowCallbackRegistry callbacks = wf.callbackRegistry();
            Function<State, State> identity =
                    callbacks.stateMutator(wait.timeout().onTimeoutMutatorCallbackId());
            State original = new State(42);
            assertThat(identity.apply(original)).isEqualTo(original);
        }

        @Test
        @DisplayName(".timeoutAt(Instant) emits TimeoutBranch with TimerSpec.At")
        void timeoutAt() {
            Instant deadline = Instant.parse("2030-12-31T23:59:59Z");
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "wait-payment")
                    .waitForSignal("wait-payment", "payment.captured", SignalPayload.class)
                    .onSignal((s, p) -> new State(p.value()))
                    .toStepOnSignal("ok")
                    .timeoutAt(deadline)
                    .toStepOnTimeout("cancelled")
                    .complete("ok")
                    .complete("cancelled");

            WorkflowPlan plan = wf.build("def", 1L);
            WaitSignalNode wait = (WaitSignalNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("wait-payment"))
                    .findFirst()
                    .orElseThrow();
            assertThat(wait.timeout().timeout()).isInstanceOf(TimerSpec.At.class);
            assertThat(((TimerSpec.At) wait.timeout().timeout()).fireAt()).isEqualTo(deadline);
        }

        @Test
        @DisplayName(".timeoutFromState(resolver) emits TimeoutBranch with TimerSpec.FromState and registers resolver")
        void timeoutFromState() {
            Instant resolved = Instant.parse("2031-06-15T00:00:00Z");
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "wait-payment")
                    .waitForSignal("wait-payment", "payment.captured", SignalPayload.class)
                    .onSignal((s, p) -> new State(p.value()))
                    .toStepOnSignal("ok")
                    .timeoutFromState(s -> resolved)
                    .toStepOnTimeout("cancelled")
                    .complete("ok")
                    .complete("cancelled");

            WorkflowPlan plan = wf.build("def", 1L);
            WaitSignalNode wait = (WaitSignalNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("wait-payment"))
                    .findFirst()
                    .orElseThrow();
            assertThat(wait.timeout().timeout()).isInstanceOf(TimerSpec.FromState.class);

            TimerSpec.FromState fromState = (TimerSpec.FromState) wait.timeout().timeout();
            WorkflowCallbackRegistry callbacks = wf.callbackRegistry();
            Function<State, Instant> registered = callbacks.timerResolver(fromState.resolverCallbackId());
            assertThat(registered.apply(new State(0))).isEqualTo(resolved);
        }

        @Test
        @DisplayName("TimeoutBranch.timeoutNextStepId referencing a missing step is rejected at build()")
        void timeoutMissingNextStepIdRejected() {
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "wait-payment")
                    .waitForSignal("wait-payment", "payment.captured", SignalPayload.class)
                    .onSignal((s, p) -> new State(p.value()))
                    .toStepOnSignal("ok")
                    .timeoutAfter(Duration.ofMinutes(5))
                    .toStepOnTimeout("nonexistent")
                    .complete("ok");
            assertThatThrownBy(() -> wf.build("def", 1L)).isInstanceOf(WorkflowDefinitionException.class);
        }

        @Test
        @SuppressWarnings("deprecation")
        @DisplayName("cycle-1 .waitFor(...) deprecated overload still emits WaitSignalNode with timeout=null")
        void deprecatedWaitForOverload() {
            WorkflowBuilder<State> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "wait-shipped")
                    .waitFor(
                            "wait-shipped",
                            "order.shipped",
                            SignalPayload.class,
                            (s, p) -> new State(p.value()),
                            "done")
                    .complete("done");
            WorkflowPlan plan = wf.build("def", 1L);

            WaitSignalNode wait = (WaitSignalNode) plan.nodes().stream()
                    .filter(n -> n.stepId().equals("wait-shipped"))
                    .findFirst()
                    .orElseThrow();
            assertThat(wait.timeout()).isNull();
            assertThat(wait.signalName()).isEqualTo("order.shipped");
        }
    }

    @Nested
    @DisplayName("planHash drift for cycle-2 DSL")
    class PlanHashDrift {

        private static String hashOf(java.util.function.Consumer<WorkflowBuilder<State>> body) {
            WorkflowBuilder<State> wf = baseBuilder();
            body.accept(wf);
            return wf.build("def", 1L).planHash();
        }

        @Test
        @DisplayName("changing TimerSpec.After delay flips planHash")
        void durationDrift() {
            String h1 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "wait")
                    .timer("wait", Duration.ofHours(1))
                    .toStep("done")
                    .complete("done"));
            String h2 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "wait")
                    .timer("wait", Duration.ofHours(2))
                    .toStep("done")
                    .complete("done"));
            assertThat(h1).isNotEqualTo(h2);
        }

        @Test
        @DisplayName("changing TimerSpec.At fireAt flips planHash")
        void instantDrift() {
            Instant a = Instant.parse("2030-01-01T00:00:00Z");
            Instant b = Instant.parse("2030-01-02T00:00:00Z");
            String h1 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "wait")
                    .timer("wait", a)
                    .toStep("done")
                    .complete("done"));
            String h2 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "wait")
                    .timer("wait", b)
                    .toStep("done")
                    .complete("done"));
            assertThat(h1).isNotEqualTo(h2);
        }

        @Test
        @DisplayName("adding a TimeoutBranch on a WaitSignalNode flips planHash")
        void timeoutBranchDrift() {
            String noTimeout = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "wait")
                    .waitForSignal("wait", "sig", SignalPayload.class)
                    .onSignal((s, p) -> new State(p.value()))
                    .toStepOnSignal("ok")
                    .build()
                    .complete("ok"));
            String withTimeout = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "wait")
                    .waitForSignal("wait", "sig", SignalPayload.class)
                    .onSignal((s, p) -> new State(p.value()))
                    .toStepOnSignal("ok")
                    .timeoutAfter(Duration.ofMinutes(5))
                    .toStepOnTimeout("cancelled")
                    .complete("ok")
                    .complete("cancelled"));
            assertThat(noTimeout).isNotEqualTo(withTimeout);
        }

        @Test
        @DisplayName("cycle-1-only plan hashes deterministically across builds")
        void cycleOneOnlyDeterministic() {
            String h1 = hashOf(
                    wf -> wf.dispatch("start", "svc", s -> new Object(), "done").complete("done"));
            String h2 = hashOf(
                    wf -> wf.dispatch("start", "svc", s -> new Object(), "done").complete("done"));
            assertThat(h1).isEqualTo(h2);
        }

        @Test
        @DisplayName("TimerSpec.At sub-millisecond differences flip planHash (no toEpochMilli truncation)")
        void instantSubMillisecondDriftDetected() {
            // Two Instants that differ ONLY in nanoseconds — Long.toString(toEpochMilli()) would
            // hash them identically. The fix hashes (epochSecond, nano) so the digest is sensitive
            // to nanosecond-level changes.
            Instant a = Instant.ofEpochSecond(1893456000L, 100);
            Instant b = Instant.ofEpochSecond(1893456000L, 200);
            String h1 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "wait")
                    .timer("wait", a)
                    .toStep("done")
                    .complete("done"));
            String h2 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "wait")
                    .timer("wait", b)
                    .toStep("done")
                    .complete("done"));
            assertThat(h1).isNotEqualTo(h2);
        }

        @Test
        @DisplayName("TimerSpec.After sub-millisecond differences flip planHash")
        void durationSubMillisecondDriftDetected() {
            // Same nanosecond-precision sensitivity for relative durations.
            Duration a = Duration.ofSeconds(60).plusNanos(100);
            Duration b = Duration.ofSeconds(60).plusNanos(200);
            String h1 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "wait")
                    .timer("wait", a)
                    .toStep("done")
                    .complete("done"));
            String h2 = hashOf(wf -> wf.dispatch("start", "svc", s -> new Object(), "wait")
                    .timer("wait", b)
                    .toStep("done")
                    .complete("done"));
            assertThat(h1).isNotEqualTo(h2);
        }
    }
}
