// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor.emit;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.TypeVisibility;
import dev.vertique.codegen.dagger.processor.Binding;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the bindings a generated module can legally name.
 *
 * <p>The generated module is one {@code public} type in a package derived from all its origins, so
 * a package-private type (or one nested in a non-public type) outside that package cannot be
 * referenced from it, and neither can a type in the unnamed package. Emitting a reference to such a
 * type produces source that does not compile, which breaks the application's build merely by
 * putting the processor on its path. The unreachable bindings are left out and reported through a
 * mandatory warning so the reduction is never silent.
 */
final class ReferenceableBindings {

    private ReferenceableBindings() {}

    /**
     * Returns the bindings whose origin type the module in {@code packageName} can reference.
     *
     * @param ctx the codegen context that reports the warnings
     * @param bindings the accumulated bindings
     * @param packageName the package the module will be written to
     * @param moduleSimpleName the simple name of the generated module, for the warning text
     * @return the referenceable subset, in order; possibly empty
     */
    static List<Binding> filter(
            CodegenContext ctx, List<Binding> bindings, String packageName, String moduleSimpleName) {
        List<Binding> referenceable = new ArrayList<>(bindings.size());
        for (Binding binding : bindings) {
            if (TypeVisibility.isReferenceableFrom(binding.origin(), packageName)) {
                referenceable.add(binding);
                continue;
            }
            ctx.diagnostics()
                    .mandatoryWarning(
                            binding.origin(),
                            "%s is not accessible from package '%s', where %s is generated, so it is left"
                                    + " unbound. Make the type (and any enclosing type) public, set -A%s to a"
                                    + " package it is visible from, or provide it with a hand-written @Provides"
                                    + " method.",
                            binding.implType().canonicalName(),
                            packageName,
                            moduleSimpleName,
                            CodegenContext.OPTION_OUTPUT_PACKAGE);
        }
        return referenceable;
    }
}
