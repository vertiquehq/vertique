// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor;

import com.palantir.javapoet.ClassName;
import javax.lang.model.element.TypeElement;

/**
 * Immutable description of one generic Dagger registration declaration.
 *
 * <p>A registration is either optional, as {@code @RegisterAs} and {@code @RegisterIntoSet} declarations are, or
 * required, as a signal source class is. The emitter skips an optional registration it cannot reference with a
 * warning, and refuses a required one with an error, because a skipped source would leave a source that never
 * consumes.
 *
 * @param origin          the annotated implementation type
 * @param target          the type exposed by the generated binding
 * @param implementation  the implementation type injected into the generated binding method
 * @param intoSet         whether the binding contributes to a set
 * @param required        whether the registration must be emitted; a registration that cannot be is an error
 * @param transportModule the Dagger module the generated registrations module includes for this registration, or
 *                        {@code null} for none; a registration that names one binds the target parameterized with
 *                        wildcards, so a generic source type fills one {@code Set} whatever its type argument
 */
public record Registration(
        TypeElement origin,
        TypeElement target,
        ClassName implementation,
        boolean intoSet,
        boolean required,
        TypeElement transportModule) {

    /**
     * Creates an optional registration that includes no module.
     *
     * @param origin         the annotated implementation type
     * @param target         the type exposed by the generated binding
     * @param implementation the implementation type injected into the generated binding method
     * @param intoSet        whether the binding contributes to a set
     */
    public Registration(TypeElement origin, TypeElement target, ClassName implementation, boolean intoSet) {
        this(origin, target, implementation, intoSet, false, null);
    }

    /**
     * Creates the required registration of a signal source class into the set of its source type.
     *
     * @param origin          the source class
     * @param target          the source type the class implements
     * @param transportModule the module of the transport that consumes the set
     * @return the registration
     */
    public static Registration sourceClass(TypeElement origin, TypeElement target, TypeElement transportModule) {
        return new Registration(origin, target, ClassName.get(origin), true, true, transportModule);
    }

    /**
     * Returns whether this registration binds the target parameterized with wildcards.
     *
     * @return {@code true} for a signal source class
     */
    public boolean bindsWildcardTarget() {
        return transportModule != null;
    }
}
