// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.resolver;

import dev.vertique.core.exception.VertiqueSecurityException;
import java.util.Objects;

/**
 * Thrown by a {@link SecurityIdentityResolver} when it encounters an error condition during
 * identity resolution.
 *
 * <p>A failed {@link io.vertx.core.Future} wrapping an {@code IdentityResolutionException}
 * propagates to the chain runner, which MUST NOT suppress it. This exception is distinct from
 * a "not handled" result: a resolver that cannot interpret the context returns
 * {@link java.util.Optional#empty()} inside a succeeded {@link io.vertx.core.Future} rather than
 * throwing.
 *
 * <p>The typed {@link #error()} accessor allows callers and monitoring code to classify the
 * root cause without parsing the message string.
 */
public class IdentityResolutionException extends VertiqueSecurityException {

    private final IdentityResolutionError error;

    /**
     * Constructs an {@code IdentityResolutionException} with the given error classification and
     * detail message.
     *
     * @param error   the machine-readable error classification; must not be {@code null}
     * @param message the human-readable detail message; must not be {@code null}
     * @throws NullPointerException if {@code error} or {@code message} is {@code null}
     */
    public IdentityResolutionException(IdentityResolutionError error, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.error = Objects.requireNonNull(error, "error");
    }

    /**
     * Constructs an {@code IdentityResolutionException} with the given error classification,
     * detail message, and cause.
     *
     * @param error   the machine-readable error classification; must not be {@code null}
     * @param message the human-readable detail message; must not be {@code null}
     * @param cause   the underlying cause; may be {@code null}
     * @throws NullPointerException if {@code error} or {@code message} is {@code null}
     */
    public IdentityResolutionException(IdentityResolutionError error, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.error = Objects.requireNonNull(error, "error");
    }

    /**
     * Returns the typed error classification for this exception.
     *
     * @return the {@link IdentityResolutionError} constant; never {@code null}
     */
    public IdentityResolutionError error() {
        return error;
    }
}
