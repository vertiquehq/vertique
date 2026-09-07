// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing.testkit;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;

/**
 * Deterministic marker canonicalizer used by {@link InvocationPolicyScenarios} (T016, issue #379);
 * see {@link A} for the shared rationale, applied to the {@code CANONICALIZE} axis.
 */
public final class K implements Canonicalizer {

    /**
     * The pure transform this canonicalizer applies.
     *
     * @param value the raw string value
     * @return {@code value}, unchanged
     */
    public static String transform(String value) {
        return value;
    }

    @Override
    public String canonicalize(String value, InputValueContext context) {
        return transform(value);
    }
}
