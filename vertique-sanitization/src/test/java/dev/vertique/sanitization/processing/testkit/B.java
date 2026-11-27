// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.processing.testkit;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.sanitization.Sanitizer;

/**
 * Deterministic marker sanitizer used by {@link InvocationPolicyScenarios}; see
 * {@link A} for the shared rationale. Also the target of {@link ComposedSanitize}'s meta-annotation.
 */
public final class B implements Sanitizer {

    /**
     * The pure transform this sanitizer applies.
     *
     * @param value the raw string value
     * @return {@code value}, unchanged
     */
    public static String transform(String value) {
        return value;
    }

    @Override
    public String sanitize(String value, InputValueContext context) {
        return transform(value);
    }
}
