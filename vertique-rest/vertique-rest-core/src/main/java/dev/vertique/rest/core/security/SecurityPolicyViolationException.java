// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import dev.vertique.rest.core.RestConfigurationException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown at startup when security policy validation detects inconsistencies.
 *
 * <p>Contains the full list of detected violations with per-operation diagnostics.
 */
public class SecurityPolicyViolationException extends RestConfigurationException {

    private final List<SecurityPolicyViolation> violations;

    /**
     * Creates a new exception with the given list of violations.
     *
     * @param violations the detected security policy violations
     */
    public SecurityPolicyViolationException(List<SecurityPolicyViolation> violations) {
        super(formatMessage(violations));
        this.violations = List.copyOf(violations);
    }

    /**
     * Returns the list of security policy violations that caused this exception.
     *
     * @return unmodifiable list of violations
     */
    public List<SecurityPolicyViolation> violations() {
        return violations;
    }

    private static String formatMessage(List<SecurityPolicyViolation> violations) {
        String details = violations.stream()
                .map(v -> String.format("  [%s] %s: %s", v.type(), v.operationId(), v.message()))
                .collect(Collectors.joining("\n"));
        return String.format("Security policy validation failed with %d violation(s):\n%s", violations.size(), details);
    }
}
