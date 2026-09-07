// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

/**
 * The two framework-owned invocation-level policy axes, paired with the display names of their
 * additive and skip annotations for {@link InvocationPolicyConflictException} diagnostics.
 *
 * <p>An axis has exactly two annotations: one <em>additive</em> (declares a chain, e.g.
 * {@code @Sanitize}) and one <em>skip</em> (opts out entirely, e.g. {@code @SkipSanitization}). See
 * {@link InvocationPolicyResolver} for how the two combine and {@link InvocationPolicySource} for
 * how one axis's view of one element is represented.
 */
public enum PolicyAxis {

    /** The canonicalization axis: {@code @Canonicalize} / {@code @SkipCanonicalization}. */
    CANONICALIZE("@Canonicalize", "@SkipCanonicalization"),

    /** The sanitization axis: {@code @Sanitize} / {@code @SkipSanitization}. */
    SANITIZE("@Sanitize", "@SkipSanitization");

    private final String additiveAnnotation;
    private final String skipAnnotation;

    PolicyAxis(String additiveAnnotation, String skipAnnotation) {
        this.additiveAnnotation = additiveAnnotation;
        this.skipAnnotation = skipAnnotation;
    }

    /**
     * The display name of this axis's additive annotation, used in diagnostics.
     *
     * @return e.g. {@code "@Sanitize"}
     */
    public String additiveAnnotation() {
        return additiveAnnotation;
    }

    /**
     * The display name of this axis's skip annotation, used in diagnostics.
     *
     * @return e.g. {@code "@SkipSanitization"}
     */
    public String skipAnnotation() {
        return skipAnnotation;
    }
}
