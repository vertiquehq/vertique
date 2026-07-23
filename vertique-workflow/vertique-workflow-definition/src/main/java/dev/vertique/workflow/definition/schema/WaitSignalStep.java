// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.schema;

import jakarta.annotation.Nullable;

/**
 * A step that suspends the workflow until a named signal arrives, then applies the signal payload
 * to the state and advances to the next step.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id} — unique step identifier within the workflow.
 *   <li>{@code signal} — name of the expected signal event (e.g., {@code "inventory.reserved"}).
 *   <li>{@code payloadType} — fully-qualified class name of the signal payload type.
 *   <li>{@code stateReducer} — registered state reducer id that folds the signal payload into the
 *       current state.
 *   <li>{@code next} — id of the step to transition to after receiving the signal.
 *   <li>{@code timeout} — optional timeout configuration; {@code null} if no timeout is defined.
 * </ul>
 */
public record WaitSignalStep(
        String id,
        String signal,
        String payloadType,
        String stateReducer,
        String next,
        @Nullable TimeoutBlock timeout) implements StepNode {

    /**
     * Optional timeout configuration for a {@link WaitSignalStep}.
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code after} — when the timeout fires, expressed as an ISO-8601 Duration
     *       (e.g., {@code "PT15M"}), an ISO-8601 Instant (absolute wall-clock time), or
     *       {@code "ref:<resolverId>"} where {@code resolverId} is a registered timer resolver id.
     *       The value-level parsing is the validator's responsibility — the parser stores it as a
     *       raw string.
     *   <li>{@code onTimeoutMutator} — registered state mutator id applied when the timeout fires.
     *   <li>{@code next} — id of the step to transition to after the timeout fires.
     * </ul>
     */
    public record TimeoutBlock(String after, String onTimeoutMutator, String next) {}
}
