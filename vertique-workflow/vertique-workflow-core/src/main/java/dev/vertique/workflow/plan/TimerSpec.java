// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import dev.vertique.workflow.registry.CallbackId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Specifies how a workflow timer's fire time is determined.
 *
 * <p>The three permitted variants cover the ways an app can express a timer delay or deadline:
 * <ul>
 *   <li>{@link At} — absolute fire time known at plan-authoring time or derived at design time.</li>
 *   <li>{@link After} — relative delay from the moment the engine enters the timer step.</li>
 *   <li>{@link FromState} — fire time computed dynamically from the workflow state by a registered
 *       callback; used when the deadline depends on data accumulated earlier in the workflow.</li>
 * </ul>
 *
 * <p>The engine resolves the concrete {@link Instant} fire time at step-execution time:
 * <ul>
 *   <li>{@link At#fireAt()} is used directly.</li>
 *   <li>{@link After#delay()} is added to the clock instant at the moment the engine enters the
 *       timer step.</li>
 *   <li>{@link FromState#resolverCallbackId()} is looked up in the {@link
 *       dev.vertique.workflow.registry.WorkflowCallbackRegistry} and invoked with the current
 *       workflow state to produce the fire {@link Instant}.</li>
 * </ul>
 */
public sealed interface TimerSpec permits TimerSpec.At, TimerSpec.After, TimerSpec.FromState {

    /**
     * The timer fires at a specific absolute instant.
     *
     * @param fireAt the absolute UTC instant at which the timer should fire; must not be null
     */
    record At(Instant fireAt) implements TimerSpec {
        public At {
            Objects.requireNonNull(fireAt, "fireAt");
        }
    }

    /**
     * The timer fires after a fixed duration relative to the moment the engine enters the timer
     * step.
     *
     * @param delay the duration to wait; must be non-null, non-negative, and not zero
     */
    record After(Duration delay) implements TimerSpec {
        public After {
            Objects.requireNonNull(delay, "delay");
            if (delay.isNegative() || delay.isZero()) {
                throw new IllegalArgumentException("delay must be a positive duration; got " + delay);
            }
        }
    }

    /**
     * The timer's fire time is resolved at execution time by invoking a registered callback with
     * the current workflow state.
     *
     * <p>The callback registered under {@code resolverCallbackId} must have type
     * {@code Function<S, Instant>} where {@code S} is the workflow state type. It is looked up via
     * {@link dev.vertique.workflow.registry.WorkflowCallbackRegistry#timerResolver(CallbackId)}.
     *
     * <p>The engine validates the resolver's return value at firing time: it must be non-null;
     * a null return surfaces as a typed {@code WorkflowDefinitionException}.
     *
     * @param resolverCallbackId the callback id of the {@code Function<S, Instant>} registered in
     *     the workflow's {@link dev.vertique.workflow.registry.WorkflowCallbackRegistry}; never null
     */
    record FromState(CallbackId resolverCallbackId) implements TimerSpec {
        public FromState {
            Objects.requireNonNull(resolverCallbackId, "resolverCallbackId");
        }
    }
}
