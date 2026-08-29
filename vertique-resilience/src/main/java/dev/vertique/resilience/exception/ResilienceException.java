// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.exception;

import dev.vertique.core.exception.TechnicalException;

/** Technical failure produced by the resilience runtime. */
public abstract sealed class ResilienceException extends TechnicalException
        permits ResilienceTimeoutException, ResiliencePolicyException {

    /**
     * Creates a cause-free resilience failure with a safe message.
     *
     * @param safeMessage fixed message that contains no untrusted execution data
     */
    protected ResilienceException(String safeMessage) {
        super(safeMessage);
    }
}
