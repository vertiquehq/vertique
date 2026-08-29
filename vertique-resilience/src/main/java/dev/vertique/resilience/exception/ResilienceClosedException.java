// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.exception;

import java.util.regex.Pattern;

/** Signals that a resilience runtime rejected work after close. */
public final class ResilienceClosedException extends ResilienceUnavailableException {

    private static final String MESSAGE = "Resilience runtime is closed";
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9:-]{1,32}:[0-9a-f]{64}");

    private final String operationKey;

    /**
     * Creates a runtime-closed failure for a derived operation key.
     *
     * @param operationKey validated opaque operation key
     */
    public ResilienceClosedException(String operationKey) {
        super(MESSAGE);
        if (operationKey == null || !KEY_PATTERN.matcher(operationKey).matches()) {
            throw new IllegalArgumentException("operationKey must be a derived resilience key");
        }
        this.operationKey = operationKey;
    }

    /** @return the validated opaque operation key */
    public String operationKey() {
        return operationKey;
    }
}
