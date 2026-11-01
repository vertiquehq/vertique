// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.source;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares, on a method of an interface that a transport's processor turns into a signal source, one signal the
 * method's message is recorded as, and how the message addresses the workflow instance that waits for it.
 *
 * <p>The only parameter of the method is the message, and its type must be the type of the signal's payload, because
 * this form maps no payload. {@link #key()} and {@link #businessKey()} name an accessor of the message type, a record
 * component or a no-argument method, and exactly one of them is set. {@link #dedupBy()} names the accessor that yields
 * the dedup key; a message has one dedup key, so every target of an interface states the same one, and a target by
 * business key must state it. The generated source refers to each accessor by method reference, so a misspelled name
 * fails compilation, naming it.
 *
 * <p>A lookup, a payload mapping, a scope and a filter belong to the builder or to a source class, which carry the
 * same settings in code.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
@Repeatable(WorkflowSignalTargets.class)
public @interface WorkflowSignalTarget {

    /**
     * The workflow contract that declares the signal.
     *
     * @return the contract interface
     */
    Class<?> contract();

    /**
     * The name of the signal, as the contract declares it with {@code @WorkflowSignal}.
     *
     * @return the signal name
     */
    String signal();

    /**
     * The accessor of the message type that yields the key of the waiting instance. Exactly one of {@code key} and
     * {@link #businessKey()} is set.
     *
     * @return the accessor name, or empty when the target uses {@code businessKey}
     */
    String key() default "";

    /**
     * The accessor of the message type that yields the business key of the waiting instance. Exactly one of
     * {@link #key()} and {@code businessKey} is set, and a target by business key must state {@link #dedupBy()}.
     *
     * @return the accessor name, or empty when the target uses {@code key}
     */
    String businessKey() default "";

    /**
     * The accessor of the message type that yields the dedup key. Every target of an interface states the same value.
     *
     * @return the accessor name, or empty for none
     */
    String dedupBy() default "";

    /**
     * What the source does with a message whose key matches no waiting instance.
     *
     * @return the policy; {@link SignalNoMatch#IGNORE} by default
     */
    SignalNoMatch onNoMatch() default SignalNoMatch.IGNORE;
}
