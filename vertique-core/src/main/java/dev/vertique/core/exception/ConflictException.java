// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

/**
 * Semantic root for conflict failures: a well-formed request that conflicts with the current state
 * of a resource (e.g. a version/idempotency/state conflict). Maps to HTTP 409 at the REST boundary.
 */
public class ConflictException extends VertiqueException {

    public ConflictException(String message) {
        super(message);
    }

    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
