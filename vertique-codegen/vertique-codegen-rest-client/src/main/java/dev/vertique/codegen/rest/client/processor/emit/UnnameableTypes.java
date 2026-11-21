// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor.emit;

import dev.vertique.codegen.TypeVisibility;
import java.util.Optional;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.SimpleTypeVisitor14;

/**
 * Finds the first type in a {@link TypeMirror} that source generated into a given package cannot
 * name.
 *
 * <p>The proxy and bean-accessor emitters write into the package of the type they derive from, so
 * every type they name must be reachable from there; one that is not makes the generated source
 * fail with a "has private access" or "is not public" error far from the cause. Both emitters
 * report the type up front instead.
 */
final class UnnameableTypes {

    private static final SimpleTypeVisitor14<Optional<TypeElement>, String> FINDER =
            new SimpleTypeVisitor14<>(Optional.empty()) {
                @Override
                public Optional<TypeElement> visitDeclared(DeclaredType type, String fromPackage) {
                    if (type.asElement() instanceof TypeElement element
                            && !TypeVisibility.isReferenceableFrom(element, fromPackage)) {
                        return Optional.of(element);
                    }
                    for (TypeMirror argument : type.getTypeArguments()) {
                        Optional<TypeElement> found = argument.accept(this, fromPackage);
                        if (found.isPresent()) {
                            return found;
                        }
                    }
                    return Optional.empty();
                }

                @Override
                public Optional<TypeElement> visitArray(ArrayType type, String fromPackage) {
                    return type.getComponentType().accept(this, fromPackage);
                }

                @Override
                public Optional<TypeElement> visitWildcard(WildcardType type, String fromPackage) {
                    for (TypeMirror bound : new TypeMirror[] {type.getExtendsBound(), type.getSuperBound()}) {
                        if (bound != null) {
                            Optional<TypeElement> found = bound.accept(this, fromPackage);
                            if (found.isPresent()) {
                                return found;
                            }
                        }
                    }
                    return Optional.empty();
                }
            };

    private UnnameableTypes() {}

    /**
     * Returns the first type named by {@code type}, including its type arguments, array component
     * and wildcard bounds, that cannot be named from {@code fromPackage}.
     *
     * @param type the type the generated source will name; may be {@code null}
     * @param fromPackage the package the generated source is written to
     * @return the unnameable type, or empty when every named type is reachable
     */
    static Optional<TypeElement> firstUnnameable(TypeMirror type, String fromPackage) {
        return type == null ? Optional.empty() : type.accept(FINDER, fromPackage);
    }
}
