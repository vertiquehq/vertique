// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.source;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The container of a repeated {@link WorkflowSignalTarget}. Write several targets on one method and the compiler wraps
 * them in this annotation; an application never names it.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface WorkflowSignalTargets {

    /**
     * The targets of the method.
     *
     * @return the repeated targets, in declaration order
     */
    WorkflowSignalTarget[] value();
}
