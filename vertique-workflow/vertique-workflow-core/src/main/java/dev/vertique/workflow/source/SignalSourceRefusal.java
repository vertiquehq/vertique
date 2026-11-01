// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.source;

import java.util.Objects;

/**
 * Fails a source's {@code dispatchAs} function to refuse a message outright: the message is not
 * retried and goes to the dead-letter path, or its exchange fails.
 *
 * <p>The refusal carries its {@link #errorType()} and nothing else: no cause, no stack trace and no
 * message beyond the type, so nothing from the message can ride along with it.
 */
public final class SignalSourceRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorType;

    /**
     * Creates a refusal.
     *
     * @param errorType the type of the refusal, forwarded as the only detail of the failure
     * @throws NullPointerException if {@code errorType} is {@code null}
     * @throws IllegalArgumentException if {@code errorType} is blank
     */
    public SignalSourceRefusal(String errorType) {
        super(requireType(errorType), null, false, false);
        this.errorType = errorType;
    }

    /**
     * Returns the type of the refusal.
     *
     * @return the error type
     */
    public String errorType() {
        return errorType;
    }

    private static String requireType(String errorType) {
        Objects.requireNonNull(errorType, "errorType");
        if (errorType.isBlank()) {
            throw new IllegalArgumentException("errorType must not be blank");
        }
        return errorType;
    }
}
