// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor;

import com.palantir.javapoet.ClassName;
import javax.lang.model.element.TypeElement;

/**
 * Immutable description of one generic Dagger registration declaration.
 *
 * <p>A registration is either an ordinary one, as {@code @RegisterAs} and {@code @RegisterIntoSet} declarations are, or
 * that of a signal source. The emitter skips an ordinary registration it cannot reference with a warning. It refuses
 * the registration of a signal source with an error, because a skipped source would never consume, and binds the
 * source type parameterized with wildcards, so that a generic source type fills one {@code Set} whatever its type
 * argument, and includes the transport module that consumes the {@code Set}.
 *
 * @param origin          the annotated implementation type
 * @param target          the type exposed by the generated binding
 * @param implementation  the implementation type injected into the generated binding method
 * @param intoSet         whether the binding contributes to a set
 * @param transportModule the Dagger module of the transport that consumes the set, or {@code null} for an ordinary
 *                        registration; a registration that names one is the registration of a signal source
 */
public record Registration(
        TypeElement origin,
        TypeElement target,
        ClassName implementation,
        boolean intoSet,
        TypeElement transportModule) {

    /**
     * Creates an ordinary registration that includes no module.
     *
     * @param origin         the annotated implementation type
     * @param target         the type exposed by the generated binding
     * @param implementation the implementation type injected into the generated binding method
     * @param intoSet        whether the binding contributes to a set
     */
    public Registration(TypeElement origin, TypeElement target, ClassName implementation, boolean intoSet) {
        this(origin, target, implementation, intoSet, null);
    }

    /**
     * Creates the registration of a signal source class into the set of its source type.
     *
     * @param origin          the source class
     * @param target          the source type the class implements
     * @param transportModule the module of the transport that consumes the set
     * @return the registration
     */
    public static Registration sourceClass(TypeElement origin, TypeElement target, TypeElement transportModule) {
        return new Registration(origin, target, ClassName.get(origin), true, transportModule);
    }

    /**
     * Creates the registration of a class that another processor generates for an interface, bound by name into the
     * set of a source type.
     *
     * <p>The class need not exist when the registration is made: a processor generates it in a later round.
     *
     * @param origin          the interface the class is generated for
     * @param implementation  the name of the generated class
     * @param target          the source type the generated class implements
     * @param transportModule the module of the transport that consumes the set
     * @return the registration
     */
    public static Registration generatedSource(
            TypeElement origin, ClassName implementation, TypeElement target, TypeElement transportModule) {
        return new Registration(origin, target, implementation, true, transportModule);
    }

    /**
     * Returns whether this is the registration of a signal source.
     *
     * @return {@code true} when a transport module is named
     */
    public boolean isSignalSource() {
        return transportModule != null;
    }
}
