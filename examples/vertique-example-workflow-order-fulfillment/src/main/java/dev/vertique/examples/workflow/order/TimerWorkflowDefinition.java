// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;

/**
 * Workflow definitions used by the cycle-2 timer integration tests.
 *
 * <p>Two definitions are registered from a single class; the outer class is a namespace holder
 * only. Each inner definition provides a concrete {@link WorkflowDefinition} instance that is
 * registered as a separate {@link dev.vertique.workflow.registry.WorkflowContributor}.
 *
 * <h2>Definitions</h2>
 * <ul>
 *   <li>{@link StandaloneTimer} — {@code "timer-standalone"}: waits 2 seconds then completes.
 *       Used to verify a plain {@link dev.vertique.workflow.plan.TimerNode} fires and advances the
 *       workflow.</li>
 *   <li>{@link SignalWithTimeout} — {@code "timer-signal-timeout"}: waits for the
 *       {@code "ok-signal"} signal with a 2-second timeout. If the signal arrives first the
 *       workflow completes via the {@code "done"} branch; if the timer fires first the workflow
 *       completes via the {@code "cancelled"} branch.</li>
 * </ul>
 */
public final class TimerWorkflowDefinition {

    /** Minimum state record shared by both timer workflow flavors. */
    public record TimerState(String id) {}

    /** Start command / payload for both timer workflow flavors. */
    public record TimerStart(String id) {}

    /** Signal payload for the signal-with-timeout flavor. */
    public record OkSignal(String id) {}

    /**
     * Marker contract interface for the {@link StandaloneTimer} workflow definition.
     *
     * <p>The registry requires each definition to declare a unique contract class. This interface
     * serves that requirement for {@code "timer-standalone"} without exposing typed proxy methods
     * (the timer IT drives the workflow via {@link dev.vertique.workflow.ops.WorkflowOperations}
     * directly, not through a contract proxy).
     */
    public interface StandaloneTimerContract {}

    /**
     * Marker contract interface for the {@link SignalWithTimeout} workflow definition.
     *
     * <p>The registry requires each definition to declare a unique contract class. This interface
     * serves that requirement for {@code "timer-signal-timeout"} without exposing typed proxy
     * methods.
     */
    public interface SignalWithTimeoutContract {}

    private TimerWorkflowDefinition() {}

    // --- Standalone-timer definition ---

    /**
     * A two-step workflow that suspends on a standalone 2-second timer then completes.
     *
     * <p>Plan: {@code init → wait (TimerNode, 2s) → done (CompleteNode)}.
     */
    @Singleton
    public static final class StandaloneTimer implements WorkflowDefinition<TimerState, StandaloneTimerContract> {

        /**
         * Creates a new {@code StandaloneTimer} definition.
         */
        @Inject
        public StandaloneTimer() {}

        @Override
        public Class<StandaloneTimerContract> contract() {
            return StandaloneTimerContract.class;
        }

        @Override
        public Class<TimerState> stateType() {
            return TimerState.class;
        }

        @Override
        public String definitionId() {
            return "timer-standalone";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<TimerState> wf) {
            wf.init(TimerStart.class, cmd -> new TimerState(cmd.id()))
                    .initialStep("wait")
                    .timer("wait", Duration.ofSeconds(2))
                    .toStep("done")
                    .complete("done");
        }
    }

    // --- Signal-with-timeout definition ---

    /**
     * A workflow that waits for the {@code "ok-signal"} with a 2-second timeout deadline.
     *
     * <p>Plan: {@code init → wait-signal (WaitSignalNode + timeout 2s) → done | cancelled}.
     *
     * <ul>
     *   <li>Signal path: {@code wait-signal → done}</li>
     *   <li>Timeout path: {@code wait-signal → cancelled}</li>
     * </ul>
     */
    @Singleton
    public static final class SignalWithTimeout implements WorkflowDefinition<TimerState, SignalWithTimeoutContract> {

        /**
         * Creates a new {@code SignalWithTimeout} definition.
         */
        @Inject
        public SignalWithTimeout() {}

        @Override
        public Class<SignalWithTimeoutContract> contract() {
            return SignalWithTimeoutContract.class;
        }

        @Override
        public Class<TimerState> stateType() {
            return TimerState.class;
        }

        @Override
        public String definitionId() {
            return "timer-signal-timeout";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<TimerState> wf) {
            wf.init(TimerStart.class, cmd -> new TimerState(cmd.id()))
                    .initialStep("wait-signal")
                    .waitForSignal("wait-signal", "ok-signal", OkSignal.class)
                    .onSignal((s, sig) -> new TimerState(s.id()))
                    .toStepOnSignal("done")
                    .timeoutAfter(Duration.ofSeconds(2))
                    .toStepOnTimeout("cancelled")
                    .complete("done")
                    .complete("cancelled");
        }
    }
}
