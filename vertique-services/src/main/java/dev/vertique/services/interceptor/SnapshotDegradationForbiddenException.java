// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.interceptor;

import dev.vertique.core.exception.ForbiddenException;
import dev.vertique.services.dispatch.NonRecoverableDispatchFailure;

/**
 * A {@link ForbiddenException} raised by {@link SnapshotDegradationGate} when a carried
 * {@code IdentitySnapshot} could not be reconstructed and the configured
 * {@code IdentitySnapshotDegradationPolicy} is {@code FAIL}.
 *
 * <p>By implementing {@link NonRecoverableDispatchFailure} this deny bypasses the recover chain in
 * {@code ServiceMethodInvoker} (fail-closed: a degraded, unverifiable identity cannot be turned back
 * into a successful dispatch by a permissive application {@code recoverError}).
 *
 * @see SnapshotDegradationGate
 * @see NonRecoverableDispatchFailure
 */
public final class SnapshotDegradationForbiddenException extends ForbiddenException
        implements NonRecoverableDispatchFailure {

    /**
     * Constructs a new non-recoverable forbidden exception with the given message.
     *
     * @param message the detail message describing the degradation
     */
    public SnapshotDegradationForbiddenException(String message) {
        super(message);
    }
}
