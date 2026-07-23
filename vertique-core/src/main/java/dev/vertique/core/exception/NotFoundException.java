// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

/**
 * Semantic root for not-found failures: a requested resource does not exist. Maps to HTTP 404 at the
 * REST boundary.
 */
public class NotFoundException extends VertiqueException {

    public NotFoundException(String message) {
        super(message);
    }

    public NotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
