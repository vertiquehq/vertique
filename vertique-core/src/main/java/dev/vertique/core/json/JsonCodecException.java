// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import jakarta.annotation.Nullable;

/**
 * Thrown when a {@link JsonProfileCodec} cannot encode, decode or convert a value.
 *
 * <p>The framework's messages are fixed and never contain the input: {@code "JSON encoding failed"},
 * {@code "JSON decoding failed"} or {@code "JSON conversion failed"}. The cause is the underlying
 * library's exception, kept for diagnosis; its message may quote the input, so never return the cause
 * to a client.
 */
public final class JsonCodecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the value-free message
     * @param cause the underlying library exception; may be {@code null}
     */
    public JsonCodecException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
