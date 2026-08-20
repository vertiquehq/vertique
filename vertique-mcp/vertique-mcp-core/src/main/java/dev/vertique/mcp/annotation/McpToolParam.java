// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes one declared parameter of an {@link McpTool} method on the wire.
 *
 * <p>A blank {@link #name()} resolves to the source parameter name seen by the annotation
 * processor. Descriptions are non-blank and at most 4,096 characters.
 *
 * <p>Requiredness is type-derived, never annotation-derived: an {@code Optional<T>} parameter may
 * be absent, every other parameter is required. Raw generics, wildcards, and an
 * {@code @NotNull Optional<T>} are rejected at compile time.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.CLASS)
public @interface McpToolParam {

    /**
     * The protocol property name of the parameter.
     *
     * @return the external name, or the empty string to use the source parameter name
     */
    String name() default "";

    /**
     * The human-readable description of the parameter.
     *
     * @return a non-blank description of at most 4,096 characters
     */
    String description();
}
