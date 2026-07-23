// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.core.exception.ConfigurationException;
import java.util.List;

/**
 * Thrown when Kafka consumer registration validation fails.
 *
 * <p>All violations are collected before throwing so that the caller receives a complete
 * error report in a single exception rather than one violation at a time.
 */
public class KafkaRegistrationException extends ConfigurationException {

    private final List<String> violations;

    /**
     * Creates the exception with the given list of violation messages.
     *
     * @param violations the validation violations found during registration scanning
     */
    public KafkaRegistrationException(List<String> violations) {
        super("Kafka consumer registration failed with " + violations.size() + " violation(s):\n"
                + String.join("\n", violations));
        this.violations = List.copyOf(violations);
    }

    /**
     * Creates the exception with the given violations and an underlying cause (e.g. a {@link LinkageError}
     * raised while loading a present-but-broken generated companion), so the root failure's type and stack
     * are preserved rather than flattened into a message.
     *
     * @param violations the validation violations found during registration scanning
     * @param cause      the underlying cause of the failure
     */
    public KafkaRegistrationException(List<String> violations, Throwable cause) {
        super(
                "Kafka consumer registration failed with " + violations.size() + " violation(s):\n"
                        + String.join("\n", violations),
                cause);
        this.violations = List.copyOf(violations);
    }

    /**
     * Returns the list of violation messages.
     *
     * @return an unmodifiable list of violation descriptions
     */
    public List<String> violations() {
        return violations;
    }
}
