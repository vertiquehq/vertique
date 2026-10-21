// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.types;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gives a workflow state class or payload class a logical name that is independent of its package
 * and class name.
 *
 * <p>Plans store and hash the logical name instead of the binary class name, so a class can be
 * moved or renamed without changing the plan hash of the versions that use it. A class without this
 * annotation is named by {@link Class#getName()}.
 *
 * <p>A logical name is required, nonblank, at most 255 code points and free of ISO control
 * characters. Within the type namespace one name maps to one class; a second class under the same
 * name, or a name equal to the binary name of another indexed class, fails startup with
 * {@code WorkflowTypeNameConflictException}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WorkflowType {

    /**
     * The logical type name.
     *
     * @return the logical name; nonblank, at most 255 code points
     */
    String value();
}
