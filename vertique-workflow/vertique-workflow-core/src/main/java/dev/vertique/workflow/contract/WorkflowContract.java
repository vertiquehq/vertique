// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a typed workflow contract that can be used with
 * {@code WorkflowClientFactory.create(Class, String)} or created from a definition class with
 * {@code WorkflowClientFactory.create(Class)}.
 *
 * <p>The annotated interface declares methods for starting, signalling, querying, and optionally
 * cancelling a specific workflow definition. Methods must be annotated with
 * {@link WorkflowStart}, {@link WorkflowSignal}, or {@link WorkflowQuery}.
 *
 * <p>A client is bound to a definition by the factory call that creates it, not by this annotation:
 * the {@code definitionId} and {@code definitionVersion} attributes are no longer read by
 * {@code WorkflowClientFactory}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WorkflowContract {

    /**
     * The logical contract name.
     *
     * <p>Names a contract independently of its package and class name. Contract names form their own
     * namespace, separate from {@link dev.vertique.workflow.types.WorkflowType} names; within it one
     * name maps to one interface. The contract name is never part of the plan hash.
     *
     * @return the logical name; empty means the interface's binary name
     */
    String value() default "";

    /**
     * The workflow definition id this contract was written for. Clients do not read it, and it may be
     * omitted.
     *
     * @return the definition id, or empty when not set
     */
    String definitionId() default "";

    /**
     * The workflow definition version this contract was written for. Clients do not read it, and it
     * may be omitted.
     *
     * @return the definition version, or {@code 0} when not set
     */
    long definitionVersion() default 0L;
}
