// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.exception;

import jakarta.annotation.Nullable;
import java.util.Optional;

/**
 * Thrown when the credential source failed, threw, or returned an invalid name or value. The failure
 * never keeps the underlying cause, because it can carry the credential: {@link #getCause()} is always
 * null and {@link #causeType()} names only the cause's class.
 */
public final class HttpCredentialException extends HttpClientTechnicalException {

    private static final String MESSAGE = "The credential source failed";

    @Nullable
    private final String causeType;

    /** Creates a credential failure with no known cause. */
    public HttpCredentialException() {
        this(null);
    }

    /**
     * Creates a credential failure and keeps only the simple class name of its cause.
     *
     * @param cause the cause, or null when there is none; it is not retained
     */
    public HttpCredentialException(@Nullable Throwable cause) {
        super(MESSAGE);
        this.causeType = cause == null ? null : cause.getClass().getSimpleName();
    }

    /**
     * Returns the simple class name of the cause, never its message.
     *
     * @return the cause's simple class name, or empty when there was no cause
     */
    public Optional<String> causeType() {
        return Optional.ofNullable(causeType);
    }

    /**
     * Always returns null: the cause is never retained.
     *
     * @return null
     */
    @Override
    public synchronized Throwable getCause() {
        return null;
    }
}
