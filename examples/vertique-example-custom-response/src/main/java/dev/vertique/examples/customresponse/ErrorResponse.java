// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.customresponse;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Categorized error response envelope returned by {@link CategorizedExceptionMapper}.
 *
 * <p>Replaces the default RFC 9457 Problem Detail format with a custom structure that
 * classifies errors by business category and assigns a unique error ID for traceability.
 *
 * @param type    the error category classifying the failure
 * @param message the human-readable error message
 * @param id      a UUID uniquely identifying this error occurrence
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(ErrorCategory type, String message, String id) {

    /**
     * Classifies errors into broad operational categories.
     */
    public enum ErrorCategory {
        /** Infrastructure or unexpected application failures (HTTP 500). */
        TECHNICAL,
        /** Input validation failures (HTTP 400). */
        VALIDATION,
        /** Authentication or authorization failures (HTTP 401/403). */
        SECURITY,
        /** Domain or business rule violations (HTTP 404, 409, etc.). */
        BUSINESS
    }
}
