// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.interceptor;

import dev.vertique.core.exception.ForbiddenException;
import dev.vertique.services.dispatch.NonRecoverableDispatchFailure;

/**
 * A {@link ForbiddenException} raised by the services action gate
 * ({@link ServiceAuthorizationInterceptor}) that must <strong>never</strong> be recovered by the
 * {@link ServiceInterceptor#recoverError} chain.
 *
 * <p>By implementing {@link NonRecoverableDispatchFailure} this deny bypasses the recover chain in
 * {@code ServiceMethodInvoker} (fail-closed: an authorization denial cannot be turned back into a
 * successful dispatch by a permissive application {@code recoverError}). It still maps to HTTP 403
 * exactly like its {@link ForbiddenException} parent, so the wire/reply outcome of a deny is
 * unchanged — only its (non-)recoverability is.
 *
 * @see ServiceAuthorizationInterceptor
 * @see NonRecoverableDispatchFailure
 */
public final class NonRecoverableForbiddenException extends ForbiddenException
        implements NonRecoverableDispatchFailure {

    /**
     * Constructs a new non-recoverable forbidden exception with the given message.
     *
     * @param message the detail message describing the deny
     */
    public NonRecoverableForbiddenException(String message) {
        super(message);
    }
}
