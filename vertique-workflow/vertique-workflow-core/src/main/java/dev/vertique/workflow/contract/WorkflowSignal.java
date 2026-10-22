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
 * <p>A contract declares the signals its definitions may wait for: every signal a registered
 * definition waits for must be declared by a method of that definition's contract, and the
 * registry refuses a definition that waits for an undeclared signal. A contract may declare signals
 * that some of its definitions do not wait for; a signal sent to an instance whose pinned version
 * does not wait for it is refused. Two methods with the same signal name on the same contract are
 * rejected.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WorkflowSignal {

    /**
     * The name of the signal this method delivers; it is delivered to instances whose pinned
     * workflow plan has a {@code WaitSignalNode.signalName()} equal to it.
     *
     * @return the signal name
     */
    String value();
}
