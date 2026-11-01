// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.source;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface that a {@link WorkflowSignalSource} class implements, so that generated wiring registers the
 * class into a {@code Set} of that interface without the code generator depending on a transport.
 *
 * <p>The generated registrations module also includes {@link #module()}, so an application that names only the
 * generated module still receives the transport's bindings and a source cannot be left unconsumed by forgetting the
 * transport module.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface SignalSourceType {

    /**
     * The Dagger module of the transport that consumes the {@code Set} of this interface. The generated registrations
     * module lists it in its {@code includes}.
     *
     * @return the transport's module class
     */
    Class<?> module();
}
