// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.exception;

import java.util.regex.Pattern;

/** Signals that a bulkhead rejected an operation because capacity was exhausted. */
public final class BulkheadRejectedException extends ResilienceUnavailableException {

    private static final String MESSAGE = "Resilience bulkhead rejected execution";
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9:-]{1,32}:[0-9a-f]{64}");

    private final String operationKey;
    private final int maxConcurrentCalls;

    /**
     * Creates a bulkhead rejection for a derived operation key.
     *
     * @param operationKey validated opaque operation key
     * @param maxConcurrentCalls positive concurrency limit
     */
    public BulkheadRejectedException(String operationKey, int maxConcurrentCalls) {
        super(MESSAGE);
        this.operationKey = requireKey(operationKey);
        if (maxConcurrentCalls <= 0) {
            throw new IllegalArgumentException("maxConcurrentCalls must be positive");
        }
        this.maxConcurrentCalls = maxConcurrentCalls;
    }

    /** @return the validated opaque operation key */
    public String operationKey() {
        return operationKey;
    }

    /** @return the configured concurrency limit */
    public int maxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    private static String requireKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("operationKey must be a derived resilience key");
        }
        return key;
    }
}
