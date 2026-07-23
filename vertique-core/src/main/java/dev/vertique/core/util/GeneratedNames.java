// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

/**
 * Resolves the fully-qualified name of a generated companion class from its origin type.
 *
 * <p>The Vertique codegen processors emit a companion class per origin type (for example
 * {@code {Contract}_DelayedJobProxy}, {@code {Contract}_WorkflowClientProxy}, or
 * {@code {Consumer}_BindingMeta}). The processor side flattens any enclosing-type nesting by joining
 * the enclosing simple names with {@code _} and emits the companion into the origin's own package
 * (never an {@code -Avertique.codegen.package} override). At runtime the framework must reconstruct
 * that exact name from a {@link Class} object to locate the companion via
 * {@link Class#forName(String, boolean, ClassLoader)}.
 *
 * <p>{@link Class#getName()} renders nested types with a {@code $} separator
 * ({@code com.example.Outer$Inner}); the generated companion uses {@code _}
 * ({@code com.example.Outer_Inner_<suffix>}). This helper bridges the two so the runtime lookup name
 * matches what the processor emitted, for both top-level and nested origins.
 */
public final class GeneratedNames {

    private GeneratedNames() {}

    /**
     * Returns the fully-qualified name of the generated companion for the given origin type.
     *
     * <p>Replaces the {@code $} nested-type separator in {@link Class#getName()} with {@code _} and
     * appends {@code suffix}. For {@code com.example.Outer$Inner} and suffix {@code _DelayedJobProxy}
     * this yields {@code com.example.Outer_Inner_DelayedJobProxy}; for a top-level
     * {@code com.example.Job} it yields {@code com.example.Job_DelayedJobProxy}.
     *
     * @param origin the origin type the companion was generated from; must not be {@code null}
     * @param suffix the companion-class suffix (for example {@code "_DelayedJobProxy"}); must not be
     *               {@code null}
     * @return the fully-qualified companion class name
     */
    public static String companionFqn(Class<?> origin, String suffix) {
        return origin.getName().replace('$', '_') + suffix;
    }
}
