// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;

/**
 * INTERNAL seam — shared by the annotation processors of this framework and of other repositories, which call it
 * across modules; not an application contract and outside the maturity promise of {@code vertique-codegen-core}. An
 * application never calls this type.
 *
 * <p>Holds the one rule that names the class a signal source processor generates for an interface, so that the
 * processor that generates it and the wiring processor that registers it by name agree on the name. The class is
 * {@code <Interface>_SignalSource} in the interface's package; a nested interface is named from its nesting, joined by
 * {@code _}, as {@code Outer_Inner_SignalSource}.
 */
public final class SignalSourceNaming {

    private static final String GENERATED_SUFFIX = "_SignalSource";

    private SignalSourceNaming() {}

    /**
     * Returns the fully qualified name of the class generated for a signal source interface.
     *
     * @param origin the interface the class is generated for; must not be {@code null}
     * @return {@code <package>.<Interface>_SignalSource}, with the names of the enclosing types joined by {@code _} for
     *     a nested interface; the simple name alone in the unnamed package
     */
    public static String generatedName(TypeElement origin) {
        List<String> names = new ArrayList<>();
        Element current = origin;
        while (current instanceof TypeElement enclosing) {
            names.add(0, enclosing.getSimpleName().toString());
            current = enclosing.getEnclosingElement();
        }
        String simple = String.join("_", names) + GENERATED_SUFFIX;
        if (current instanceof PackageElement pkg && !pkg.isUnnamed()) {
            return pkg.getQualifiedName() + "." + simple;
        }
        return simple;
    }
}
