// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.exception;

import dev.vertique.services.ServiceRegistrationViolation;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown at startup when service registration validation fails.
 *
 * <p>Contains all violations found during scanning, enabling fail-fast reporting
 * of all problems at once.
 */
public class ServiceRegistrationException extends ServiceConfigurationException {

    private final List<ServiceRegistrationViolation> violations;

    /**
     * Creates a new exception with the given violations.
     *
     * @param violations the list of validation violations found during scanning
     */
    public ServiceRegistrationException(List<ServiceRegistrationViolation> violations) {
        super(buildMessage(violations));
        this.violations = List.copyOf(violations);
    }

    /** Creates a registration exception while preserving the common policy failure as its cause. */
    public ServiceRegistrationException(List<ServiceRegistrationViolation> violations, Throwable cause) {
        super(buildMessage(violations), cause);
        this.violations = List.copyOf(violations);
    }

    /**
     * Returns the list of validation violations.
     *
     * @return an unmodifiable list of violations
     */
    public List<ServiceRegistrationViolation> violations() {
        return violations;
    }

    private static String buildMessage(List<ServiceRegistrationViolation> violations) {
        return "Service registration failed with " + violations.size() + " violation(s):\n"
                + violations.stream().map(v -> "  - " + v).collect(Collectors.joining("\n"));
    }
}
