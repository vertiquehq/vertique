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
 * cancelling workflow instances. Methods must be annotated with {@link WorkflowStart},
 * {@link WorkflowSignal}, or {@link WorkflowQuery}.
 *
 * <p>A contract is the caller API: it names no definition. Any number of definitions may implement
 * one contract, and a client is bound to a definition by the factory call that creates it, either
 * from a definition class or from the contract and a definition id.
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
     * name maps to one interface. The contract name is never part of the plan hash. A non-empty name
     * must not be blank and must have at most 255 code points; the annotation processor rejects any
     * other name at compile time.
     *
     * @return the logical name; empty means the interface's binary name
     */
    String value() default "";
}
