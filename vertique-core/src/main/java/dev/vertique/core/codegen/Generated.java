// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.codegen;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type, method, or constructor written by a Vertique annotation processor.
 *
 * <p>Every Vertique code generator stamps this marker on the types it writes, on their nested
 * types, and on the methods of the anonymous classes it emits inline. It has {@code CLASS} retention,
 * so it survives into the class file, where bytecode tools read it: JaCoCo leaves out of coverage
 * any class or method carrying an annotation whose simple name contains {@code Generated} with
 * {@code CLASS} or {@code RUNTIME} retention. The JDK's
 * {@link javax.annotation.processing.Generated} has {@code SOURCE} retention and is discarded by
 * the compiler, so generators emit both: that one for source-level tools, this one for the class
 * file.
 *
 * <p>Application code should not use this annotation. It is not visible through reflection.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Generated {}
