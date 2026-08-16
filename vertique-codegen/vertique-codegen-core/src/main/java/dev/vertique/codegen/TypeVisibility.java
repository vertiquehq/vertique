// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * Answers whether generated source placed in one package may name a user type declared in another.
 *
 * <p>Aggregate emitters — the generated clients/contributor modules — write a single {@code public}
 * type into a package derived from all their origins, then reference each origin by name. When an
 * origin is not reachable from that package the emitted source does not compile, and because javac
 * compiles generated sources in the same task, the application's build breaks merely by putting the
 * processor on {@code annotationProcessorPaths} — whether or not it installs the generated module.
 * Emitters therefore consult this class and skip what they cannot name.
 *
 * @see PackageResolver
 */
public final class TypeVisibility {

    private TypeVisibility() {}

    /**
     * Returns whether {@code type} can be named from source declared in {@code fromPackage}.
     *
     * <p>Within its own package any access level works. From any other package the type and every
     * type enclosing it must be {@code public} — a {@code public} interface nested inside a
     * package-private class is still unreachable. A type in the unnamed package is reachable only
     * from the unnamed package, which follows from the same equality check.
     *
     * @param type        the type the generated source wants to reference; must not be {@code null}
     * @param typePackage the package {@code type} is declared in, as
     *                    {@link CodegenContext#packageNameOf} returns it
     * @param fromPackage the package the generated source will be written to
     * @return {@code true} when the generated source may reference {@code type}
     */
    public static boolean isReferenceableFrom(TypeElement type, String typePackage, String fromPackage) {
        if (typePackage.equals(fromPackage)) {
            return true;
        }
        for (Element e = type; e instanceof TypeElement enclosing; e = e.getEnclosingElement()) {
            if (!enclosing.getModifiers().contains(Modifier.PUBLIC)) {
                return false;
            }
        }
        return true;
    }
}
