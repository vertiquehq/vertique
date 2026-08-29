// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.exception;

import dev.vertique.core.exception.UnavailableException;

/** Unavailability failure produced by the resilience runtime. */
public abstract sealed class ResilienceUnavailableException extends UnavailableException
        permits CircuitOpenException,
                BulkheadRejectedException,
                BulkheadQueueTimeoutException,
                ResilienceClosedException {

    /**
     * Creates a cause-free resilience unavailability failure with a safe message.
     *
     * @param safeMessage fixed message that contains no untrusted execution data
     */
    protected ResilienceUnavailableException(String safeMessage) {
        super(safeMessage);
    }
}
