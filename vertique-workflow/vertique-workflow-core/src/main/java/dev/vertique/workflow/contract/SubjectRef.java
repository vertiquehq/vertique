// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code WorkflowSubjectRef} parameter in a {@link WorkflowStart} method as the source
 * of the optional subject reference for the start command.
 *
 * <p>The subject reference links the workflow instance to the domain entity it is acting on. It is
 * optional — if neither this annotation nor {@link SubjectReferenced} is applicable, the subject
 * ref is null.
 *
 * <p>When both this annotation and {@link SubjectReferenced} (payload-side interface) are present,
 * the annotated parameter takes precedence.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SubjectRef {}
