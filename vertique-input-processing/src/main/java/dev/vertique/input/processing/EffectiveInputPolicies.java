// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.Sanitizer;
import java.util.List;

/**
 * Captures the merged canonicalizer and sanitizer chains for a single processing invocation.
 *
 * <p>Invocation-level chains are supplied by the calling transport — typically derived from
 * {@code @Canonicalize} / {@code @Sanitize} annotations on the invoked endpoint or handler — and
 * are applied to ALL string values in the input. Object-level and field-level chains are resolved
 * separately from the target type's own annotations.
 *
 * @param canonicalizers invocation-level canonicalizer chain
 * @param sanitizers     invocation-level sanitizer chain
 */
public record EffectiveInputPolicies(
        List<Class<? extends Canonicalizer>> canonicalizers, List<Class<? extends Sanitizer>> sanitizers) {

    /** Empty policies — no invocation-level processing. */
    public static final EffectiveInputPolicies NONE = new EffectiveInputPolicies(List.of(), List.of());

    /**
     * Returns {@code true} if no invocation-level processors are configured.
     *
     * <p>Object-level and field-level processors may still be active based on the target
     * type's annotation metadata — this method only reflects the invocation-level chains.
     *
     * @return {@code true} when both invocation-level chains are empty
     */
    public boolean isEmpty() {
        return canonicalizers.isEmpty() && sanitizers.isEmpty();
    }
}
