// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container annotation for repeatable {@link ConditionalOnProperty} declarations.
 *
 * <p>This annotation is created implicitly by the Java compiler when two or more
 * {@link ConditionalOnProperty} annotations appear on the same type. Application code should
 * never use this annotation directly — annotate the type with multiple
 * {@link ConditionalOnProperty} instances instead.
 *
 * <p>Annotation processors must use {@code Elements.getAllAnnotationMirrors} and walk both the
 * single {@link ConditionalOnProperty} form and this container form to handle all cases
 * correctly. Using {@code element.getAnnotation(ConditionalOnProperty.class)} returns
 * {@code null} when only the container is present, which is a common footgun.
 *
 * <p>This annotation has {@link RetentionPolicy#SOURCE} retention — it is consumed only during
 * compilation and never reaches the runtime classpath or the compiled {@code .class} file.
 *
 * @see ConditionalOnProperty
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface ConditionalOnProperties {

    /**
     * The contained {@link ConditionalOnProperty} annotations. All conditions in this array
     * are evaluated with AND semantics: every condition must match for the annotated type to
     * be activated.
     *
     * @return the array of individual conditions; never empty when this container is present
     */
    ConditionalOnProperty[] value();
}
