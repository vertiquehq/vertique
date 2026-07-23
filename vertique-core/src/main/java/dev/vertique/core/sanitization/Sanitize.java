// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.sanitization;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an ordered chain of {@link Sanitizer} implementations to apply to the annotated element.
 *
 * <p>Sanitizers execute in the order they are declared in {@link #value()}. Each sanitizer
 * receives the output of the previous one as its input.
 *
 * <p>Placement semantics:
 * <ul>
 *   <li><b>Resource class (TYPE)</b> — applies to all request bodies and parameters handled by
 *       that JAX-RS resource; acts as the route-level default.</li>
 *   <li><b>Resource method (METHOD)</b> — overrides the type-level declaration for that specific
 *       route. Route-level sanitization runs before object-level and field-level.</li>
 *   <li><b>DTO type (TYPE)</b> — applied when the type is processed as a request body; acts as
 *       the object-level default for all fields and components of that type.</li>
 *   <li><b>Field or record component (FIELD / RECORD_COMPONENT)</b> — applied only to that
 *       specific field; runs after object-level sanitization.</li>
 *   <li><b>Method parameter (PARAMETER)</b> — applied to that specific JAX-RS parameter.</li>
 *   <li><b>Meta-annotation (ANNOTATION_TYPE)</b> — allows composing canonical sanitization
 *       presets as custom annotations.</li>
 * </ul>
 *
 * <p>Use {@link SkipSanitization} on a field or component to opt out of inherited sanitization.
 * The two annotations are mutually exclusive on the same element.
 *
 * @see Sanitizer
 * @see SanitizerBinding
 * @see SkipSanitization
 */
@Documented
@Target({
    ElementType.TYPE,
    ElementType.FIELD,
    ElementType.RECORD_COMPONENT,
    ElementType.PARAMETER,
    ElementType.METHOD,
    ElementType.ANNOTATION_TYPE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface Sanitize {

    /**
     * The ordered list of {@link Sanitizer} implementation classes to apply.
     * Sanitizers are chained in declaration order — each receives the output of the previous.
     *
     * @return the sanitizer classes to apply; must not be empty
     */
    Class<? extends Sanitizer>[] value();
}
