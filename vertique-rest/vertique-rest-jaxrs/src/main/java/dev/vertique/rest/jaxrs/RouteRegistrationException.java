// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.RestConfigurationException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown at startup when route registration validation detects inconsistencies.
 *
 * <p>Contains the full list of detected violations with per-operation diagnostics.
 */
public class RouteRegistrationException extends RestConfigurationException {

    private final List<RouteRegistrationViolation> violations;

    /**
     * Creates a new exception with the given list of violations.
     *
     * @param violations the detected route registration violations
     */
    public RouteRegistrationException(List<RouteRegistrationViolation> violations) {
        super(formatMessage(violations));
        this.violations = List.copyOf(violations);
    }

    /**
     * Returns the list of route registration violations that caused this exception.
     *
     * @return unmodifiable list of violations
     */
    public List<RouteRegistrationViolation> violations() {
        return violations;
    }

    private static String formatMessage(List<RouteRegistrationViolation> violations) {
        String details = violations.stream()
                .map(v -> String.format("  [%s] %s: %s", v.type(), v.operationId(), v.message()))
                .collect(Collectors.joining("\n"));
        return String.format("Route registration failed with %d violation(s):\n%s", violations.size(), details);
    }
}
