// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.emit;

import dev.vertique.codegen.CodegenContext;
import java.util.Arrays;
import java.util.Collection;
import javax.lang.model.element.TypeElement;

/**
 * Resolves the package a generated aggregate module is written to.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>The {@code -Avertique.codegen.package} option if set and non-blank.</li>
 *   <li>The longest common package prefix of all the classes the module lists.</li>
 *   <li>{@value #DEFAULT_PACKAGE} when that prefix is empty.</li>
 * </ol>
 */
final class GeneratedModulePackage {

    /** Fallback package when the longest common prefix of all package names is empty. */
    static final String DEFAULT_PACKAGE = "vertique.generated.workflow";

    private GeneratedModulePackage() {}

    /**
     * Resolves the output package for a generated module.
     *
     * @param ctx   the codegen context that carries the processor options; must not be {@code null}
     * @param types the classes the module lists, before any is left out; must not be empty
     * @return the resolved package name; never {@code null}
     */
    static String resolvePackage(CodegenContext ctx, Collection<TypeElement> types) {
        String override = ctx.env().getOptions().get(CodegenContext.OPTION_OUTPUT_PACKAGE);
        if (override != null && !override.isBlank()) {
            return override;
        }

        String lcp = types.stream()
                .map(ctx::packageNameOf)
                .reduce(GeneratedModulePackage::longestCommonPackagePrefix)
                .orElse("");

        return lcp.isEmpty() ? DEFAULT_PACKAGE : lcp;
    }

    /**
     * Computes the longest common package prefix of two package names by comparing
     * dot-separated segments.
     *
     * <p>For example, {@code "com.example.orders"} and {@code "com.example.payments"} share
     * the prefix {@code "com.example"}; {@code "com.foo"} and {@code "org.bar"} share no common
     * prefix and return an empty string.
     *
     * @param a the first package name; must not be {@code null}
     * @param b the second package name; must not be {@code null}
     * @return the longest common prefix, or an empty string when there is none
     */
    private static String longestCommonPackagePrefix(String a, String b) {
        String[] segA = a.isEmpty() ? new String[0] : a.split("\\.");
        String[] segB = b.isEmpty() ? new String[0] : b.split("\\.");
        int common = 0;
        int limit = Math.min(segA.length, segB.length);
        while (common < limit && segA[common].equals(segB[common])) {
            common++;
        }
        if (common == 0) {
            return "";
        }
        return String.join(".", Arrays.copyOf(segA, common));
    }
}
