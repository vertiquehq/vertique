// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing.testkit;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;

/**
 * Deterministic marker sanitizer used by {@link InvocationPolicyScenarios} (T016, issue #379) to
 * distinguish which {@code @Sanitize} declaration site an {@link InvocationPolicyResolverTest} /
 * {@link ReflectiveInvocationPoliciesTest} scenario resolved to.
 *
 * <p>{@link #transform(String)} is the single source of truth for this stub's behavior, mirroring
 * {@link CrossTransportUppercaseSanitizer}: the value is left unchanged, since the scenario matrix
 * asserts <em>which class</em> was resolved into a chain, not what the class does when invoked.
 */
public final class A implements Sanitizer {

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
