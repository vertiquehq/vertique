// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method in a {@link WorkflowContract} interface as a signal delivery operation.
 *
 * <p>Signal methods must return {@code Future<Void>}. Parameters must follow the shape
 * {@code (WorkflowInstanceId id, P payload)} or
 * {@code (WorkflowInstanceId id, P payload, @SignalDedupKey String dedupKey)}.
 *
 * <p>A dedup key source is required — either a {@link SignalDedupKey}-annotated {@code String}
 * parameter or a payload type that implements {@link SignalDedupKeyed}. Proxy creation fails if
 * neither is present.
 *
 * <p>The signal name must match a {@code WaitSignalNode.signalName()} in the resolved plan. Two
 * methods with the same signal name on the same contract are rejected.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WorkflowSignal {

    /**
     * The name of the signal this method delivers; must match a {@code WaitSignalNode.signalName()}
     * in the resolved workflow plan.
     *
     * @return the signal name
     */
    String value();
}
