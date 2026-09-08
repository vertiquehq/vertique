// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;

/**
 * INTERNAL framework seam — processor-authoring substrate consumed by sibling framework modules;
 * not an application contract and outside the maturity promise. An application uses the wiring
 * annotations this module documents and never calls this type.
 *
 * <p>Answers whether generated source placed in one package may name a user type declared in another.
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
     * <p>Within its own package every access level works <em>except</em> {@code private}: a
     * {@code private} nested type is in scope only inside the body of its enclosing top-level class
     * (JLS 6.6.1), and generated source is always a separate compilation unit.
     *
     * <p>Across packages the type and every type enclosing it must be {@code public} — a
     * {@code public} interface nested inside a package-private class is still unreachable.
     *
     * <p>A type in the unnamed package can never be referenced from a named one: it can neither be
     * imported nor named by its simple name there (JLS 7.4.2, 6.5), so no access level helps. This
     * case does <em>not</em> follow from the same-package check — a {@code public} top-level type in
     * the unnamed package would otherwise pass the modifier walk — and it is reachable in practice,
     * because a compilation unit whose contracts all sit in the unnamed package resolves an empty
     * longest-common-prefix, which emitters map to a named fallback package. The reverse direction
     * is fine and is treated as such: source in the unnamed package may import a public type from a
     * named package (JLS 7.5).
     *
     * @param type the type the generated source wants to reference; must not be {@code null}
     * @param fromPackage the package the generated source will be written to; {@code ""} for the
     *                    unnamed package
     * @return {@code true} when the generated source may reference {@code type}
     */
    public static boolean isReferenceableFrom(TypeElement type, String fromPackage) {
        String typePackage = packageNameOf(type);
        if (typePackage.equals(fromPackage)) {
            return !hasEnclosureWith(type, Modifier.PRIVATE);
        }
        if (typePackage.isEmpty()) {
            return false;
        }
        return !hasEnclosureWithout(type, Modifier.PUBLIC);
    }

    /**
     * Returns the package {@code type} is declared in, derived from its enclosing elements.
     *
     * <p>Deriving this rather than accepting it as a parameter removes the one way a caller could
     * silently get a wrong answer — passing the module's package where the type's was meant.
     *
     * @param type the type to inspect; must not be {@code null}
     * @return the package name, or {@code ""} for the unnamed package
     */
    private static String packageNameOf(TypeElement type) {
        for (Element e = type; e != null; e = e.getEnclosingElement()) {
            if (e instanceof PackageElement pkg) {
                return pkg.getQualifiedName().toString();
            }
        }
        return "";
    }

    /**
     * Returns whether {@code type} or any type enclosing it carries {@code modifier}.
     *
     * @param type     the type to inspect
     * @param modifier the modifier to look for
     * @return {@code true} when the modifier is present on the type or any enclosing type
     */
    private static boolean hasEnclosureWith(TypeElement type, Modifier modifier) {
        for (Element e = type; e instanceof TypeElement enclosing; e = e.getEnclosingElement()) {
            if (enclosing.getModifiers().contains(modifier)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether {@code type} or any type enclosing it lacks {@code modifier}.
     *
     * @param type     the type to inspect
     * @param modifier the modifier every enclosing type must carry
     * @return {@code true} when the modifier is missing from the type or any enclosing type
     */
    private static boolean hasEnclosureWithout(TypeElement type, Modifier modifier) {
        for (Element e = type; e instanceof TypeElement enclosing; e = e.getEnclosingElement()) {
            if (!enclosing.getModifiers().contains(modifier)) {
                return true;
            }
        }
        return false;
    }
}
