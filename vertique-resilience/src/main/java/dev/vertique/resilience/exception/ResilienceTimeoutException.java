// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.exception;

import java.util.regex.Pattern;

/** Signals that a resilience operation exceeded its per-attempt timeout. */
public final class ResilienceTimeoutException extends ResilienceException {

    private static final String MESSAGE = "Resilience operation timed out";
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9:-]{1,32}:[0-9a-f]{64}");

    private final String operationKey;
    private final long timeoutMs;

    /**
     * Creates a timeout failure for a derived operation key.
     *
     * @param operationKey validated opaque operation key
     * @param timeoutMs positive timeout in milliseconds
     */
    public ResilienceTimeoutException(String operationKey, long timeoutMs) {
        super(MESSAGE);
        this.operationKey = requireKey(operationKey);
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        this.timeoutMs = timeoutMs;
    }

    /** @return the validated opaque operation key */
    public String operationKey() {
        return operationKey;
    }

    /** @return the timeout in milliseconds */
    public long timeoutMs() {
        return timeoutMs;
    }

    private static String requireKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("operationKey must be a derived resilience key");
        }
        return key;
    }
}
