// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.exception;

import java.util.regex.Pattern;

/** Signals that a circuit breaker rejected an operation while open. */
public final class CircuitOpenException extends ResilienceUnavailableException {

    private static final String MESSAGE = "Resilience circuit is open";
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9:-]{1,32}:[0-9a-f]{64}");

    private final String operationKey;
    private final String stateKey;

    /**
     * Creates a circuit-open failure for derived operation and state keys.
     *
     * @param operationKey validated opaque operation key
     * @param stateKey validated opaque circuit state key
     */
    public CircuitOpenException(String operationKey, String stateKey) {
        super(MESSAGE);
        this.operationKey = requireKey(operationKey, "operationKey");
        this.stateKey = requireKey(stateKey, "stateKey");
    }

    /** @return the validated opaque operation key */
    public String operationKey() {
        return operationKey;
    }

    /** @return the validated opaque circuit state key */
    public String stateKey() {
        return stateKey;
    }

    private static String requireKey(String key, String name) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(name + " must be a derived resilience key");
        }
        return key;
    }
}
