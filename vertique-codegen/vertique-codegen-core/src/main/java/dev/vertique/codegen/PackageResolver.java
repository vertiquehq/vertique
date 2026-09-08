// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.util.List;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;

/**
 * INTERNAL framework seam — processor-authoring substrate consumed by sibling framework modules;
 * not an application contract and outside the maturity promise. An application uses the wiring
 * annotations this module documents and never calls this type.
 *
 * <p>Resolves the output package name for a generated artifact (Dagger module, descriptor module,
 * etc.) from a set of origin elements.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>If the processor option {@code -Avertique.codegen.package} is set in the processing
 *       environment, that value is returned verbatim (overrides LCP logic).</li>
 *   <li>Otherwise, computes the longest common prefix (LCP) of the package names of the supplied
 *       origin elements. If only one element is supplied, its own package is used. If the
 *       packages share no common prefix (entirely disjoint top-level packages), a compiler error
 *       is emitted and {@code null} is returned so the caller can abort emission.</li>
 * </ol>
 *
 * <p>The API takes {@code List<? extends Element>} so any codegen leaf can call it without
 * coupling to a leaf-specific binding shape; callers map their own bindings/candidates to origin
 * elements before invocation.
 */
public final class PackageResolver {

    private final ProcessingEnvironment env;
    private final Elements elements;

    /**
     * Constructs a {@code PackageResolver} for the given processing environment.
     *
     * @param env the processing environment; must not be {@code null}
     */
    public PackageResolver(ProcessingEnvironment env) {
        this.env = env;
        this.elements = env.getElementUtils();
    }

    /**
     * Resolves the output package for the given list of origin elements.
     *
     * <p>If the list is empty, this method returns {@code null}. Callers must not invoke their
     * emitters when no origins are present.
     *
     * <p>If the computed LCP is empty (origins span entirely disjoint top-level packages) and no
     * override option is set, a compiler error is emitted and {@code null} is returned so the
     * caller can abort emission for this round.
     *
     * @param origins the list of origin elements; must not be {@code null}
     * @param context the codegen context used for diagnostics
     * @return the resolved package name, or {@code null} when no package can be determined
     */
    public String resolve(List<? extends Element> origins, CodegenContext context) {
        if (origins.isEmpty()) {
            return null;
        }

        String override = env.getOptions().get(CodegenContext.OPTION_OUTPUT_PACKAGE);
        if (override != null && !override.isBlank()) {
            return override;
        }

        String lcp = origins.stream()
                .map(o -> elements.getPackageOf(o).getQualifiedName().toString())
                .reduce(PackageResolver::longestCommonPrefix)
                .orElse("");

        if (lcp.isEmpty()) {
            context.diagnostics()
                    .error(
                            null,
                            "Auto-wired types span disjoint packages with no common prefix."
                                    + " Set -A%s=<package> to choose the output package explicitly.",
                            CodegenContext.OPTION_OUTPUT_PACKAGE);
            return null;
        }

        return lcp;
    }

    // --- Internal helpers ---

    /**
     * Returns the longest common prefix of two dot-delimited package name strings, respecting
     * package-segment boundaries.
     *
     * <p>For example, {@code "com.example.foo"} and {@code "com.example.bar"} share the prefix
     * {@code "com.example"}. {@code "com.alpha"} and {@code "org.beta"} share no prefix (returns
     * {@code ""}).
     *
     * @param a the first package name
     * @param b the second package name
     * @return the longest common package prefix, or {@code ""} if there is none
     */
    public static String longestCommonPrefix(String a, String b) {
        if (a.equals(b)) {
            return a;
        }
        String[] partsA = a.split("\\.", -1);
        String[] partsB = b.split("\\.", -1);
        int limit = Math.min(partsA.length, partsB.length);
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (!partsA[i].equals(partsB[i])) {
                break;
            }
            if (prefix.length() > 0) {
                prefix.append('.');
            }
            prefix.append(partsA[i]);
        }
        return prefix.toString();
    }
}
