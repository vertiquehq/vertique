// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

/**
 * Semantic root for business-rule / domain-rule violations: a request that is well-formed but
 * violates a domain rule. Extends {@link ValidationException} and maps to HTTP 400 at the REST
 * boundary.
 */
public class BusinessRuleException extends ValidationException {

    public BusinessRuleException(String message) {
        super(message);
    }

    public BusinessRuleException(String message, Throwable cause) {
        super(message, cause);
    }
}
