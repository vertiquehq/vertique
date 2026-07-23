// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Singular;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

/**
 * RFC 9457 Problem Details body for structured validation errors.
 *
 * <p>Extends {@link ProblemDetail} with an {@code errors} array that lists
 * each field-level validation failure as a {@link ValidationErrorDetail}.
 * Use the {@link #of(String, List)} factory method to create a standard 400 response.
 *
 * <p>Example JSON output:
 * <pre>{@code
 * {
 *   "type": "about:blank",
 *   "title": "Bad Request",
 *   "status": 400,
 *   "detail": "Request validation failed",
 *   "instance": "/api/users",
 *   "errors": [
 *     { "path": "/name", "detail": "must not be blank", "location": "body" },
 *     { "path": "/age",  "detail": "must be a positive integer", "location": "body" }
 *   ]
 * }
 * }</pre>
 */
@Getter
@SuperBuilder(toBuilder = true)
@Accessors(fluent = true)
@EqualsAndHashCode(callSuper = true)
@FieldDefaults(level = lombok.AccessLevel.PRIVATE, makeFinal = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class ValidationProblemDetail extends ProblemDetail {

    /** The list of individual field-level validation failures. */
    @Singular
    List<ValidationErrorDetail> errors;

    /**
     * Creates a {@link ValidationProblemDetail} for a 400 Bad Request response with
     * the given detail message and field errors.
     *
     * @param detail the overall validation failure message
     * @param errors the list of individual field-level failures
     * @return a fully populated {@link ValidationProblemDetail} with status 400
     */
    public static ValidationProblemDetail of(String detail, List<ValidationErrorDetail> errors) {
        return ValidationProblemDetail.builder()
                .type("about:blank")
                .title("Bad Request")
                .status(400)
                .detail(detail)
                .errors(errors)
                .build();
    }
}
