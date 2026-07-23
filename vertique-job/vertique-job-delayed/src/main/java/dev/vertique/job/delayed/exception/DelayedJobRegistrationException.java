// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed.exception;

import java.util.List;

/**
 * Thrown when one or more delayed job handler registration violations are found during startup.
 *
 * <p>All violations are collected before throwing so that the complete set of problems
 * is reported in a single exception.
 *
 * <p>Extends {@link DelayedJobConfigurationException} so it is also an instance of the
 * framework-wide {@link dev.vertique.core.exception.ConfigurationException} hierarchy.
 */
public class DelayedJobRegistrationException extends DelayedJobConfigurationException {

    private final List<String> violations;

    /**
     * Creates a new exception with the given violation messages.
     *
     * @param violations the list of violation messages (must not be empty)
     */
    public DelayedJobRegistrationException(List<String> violations) {
        super("Delayed job handler registration failed with "
                + violations.size()
                + " violation(s):\n  - "
                + String.join("\n  - ", violations));
        this.violations = List.copyOf(violations);
    }

    /**
     * Returns the individual violation messages.
     *
     * @return an unmodifiable list of violation messages
     */
    public List<String> violations() {
        return violations;
    }
}
