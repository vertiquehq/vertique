// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code String} parameter in a {@link WorkflowStart} method as the source of the
 * optional business key for the start command.
 *
 * <p>The business key is stored on the workflow instance and used for domain-scoped uniqueness and
 * lookup. It is optional — if neither this annotation nor {@link BusinessKeyed} is applicable, the
 * business key is null.
 *
 * <p>When both this annotation and {@link BusinessKeyed} (payload-side interface) are present,
 * the annotated parameter takes precedence.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BusinessKey {}
