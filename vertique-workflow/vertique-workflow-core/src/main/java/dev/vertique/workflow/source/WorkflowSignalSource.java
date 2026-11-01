// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.source;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a concrete class as a signal source that generated wiring registers, so that no application {@code @Provides}
 * method names it.
 *
 * <p>The class implements an interface marked {@link SignalSourceType}, such as {@code KafkaSignalSource}, and has an
 * {@code @Inject} constructor. Generated wiring binds it into the {@code Set} of that interface, so the dependencies a
 * source needs, a lookup for example, come by constructor injection. A class without an {@code @Inject} constructor
 * fails compilation, naming the class.
 *
 * <p>{@code @NoAutoWire} on the class, or the processor option {@code vertique.codegen.autoWire=false}, turns this
 * registration off like every other generated registration.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface WorkflowSignalSource {}
