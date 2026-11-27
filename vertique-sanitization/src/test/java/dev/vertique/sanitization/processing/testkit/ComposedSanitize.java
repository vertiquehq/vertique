// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.processing.testkit;

import dev.vertique.sanitization.Sanitize;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A composed annotation meta-annotated with {@code @Sanitize(B.class)}, used by the
 * {@code composed-annotation} scenario to prove {@code AnnotationResolver.findMetaAnnotation}
 * recursion: a method carrying only this annotation must resolve the same sanitize chain as one
 * carrying {@code @Sanitize(B.class)} directly.
 */
@Documented
@Sanitize(B.class)
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ComposedSanitize {}
