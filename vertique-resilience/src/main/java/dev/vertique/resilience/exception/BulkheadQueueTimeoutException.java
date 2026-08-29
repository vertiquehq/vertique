// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.exception;

import java.util.regex.Pattern;

/** Signals that an operation waited too long for bulkhead queue admission. */
public final class BulkheadQueueTimeoutException extends ResilienceUnavailableException {

    private static final String MESSAGE = "Resilience bulkhead queue wait timed out";
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9:-]{1,32}:[0-9a-f]{64}");

    private final String operationKey;
    private final long queueTimeoutMs;

    /**
     * Creates a bulkhead queue-timeout failure for a derived operation key.
     *
     * @param operationKey validated opaque operation key
     * @param queueTimeoutMs positive queue timeout in milliseconds
     */
    public BulkheadQueueTimeoutException(String operationKey, long queueTimeoutMs) {
        super(MESSAGE);
        this.operationKey = requireKey(operationKey);
        if (queueTimeoutMs <= 0) {
            throw new IllegalArgumentException("queueTimeoutMs must be positive");
        }
        this.queueTimeoutMs = queueTimeoutMs;
    }

    /** @return the validated opaque operation key */
    public String operationKey() {
        return operationKey;
    }

    /** @return the queue timeout in milliseconds */
    public long queueTimeoutMs() {
        return queueTimeoutMs;
    }

    private static String requireKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("operationKey must be a derived resilience key");
        }
        return key;
    }
}
