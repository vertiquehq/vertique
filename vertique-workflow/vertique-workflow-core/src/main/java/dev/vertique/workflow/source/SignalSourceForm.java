// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.source;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an annotation that turns an interface into a signal source, and names the source interface the generated class
 * is registered into.
 *
 * <p>For an interface annotated with such an annotation, the processor of the annotation generates
 * {@code <Interface>_SignalSource}, a class with an {@code @Inject} constructor that implements {@link #value()}.
 * Generated wiring binds that class, by name, into the {@code Set} of {@link #value()}, and the generated registrations
 * module includes the module that {@code @SignalSourceType} on {@link #value()} names, so wiring depends on no transport.
 * A build in which no processor generated the class fails on the interface, naming the class.
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface SignalSourceForm {

    /**
     * The source interface the generated class implements and is registered into; it is annotated
     * {@link SignalSourceType}.
     *
     * @return the source interface
     */
    Class<?> value();
}
