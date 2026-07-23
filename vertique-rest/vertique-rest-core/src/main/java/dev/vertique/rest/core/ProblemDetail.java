// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Singular;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

/**
 * RFC 9457 Problem Details for HTTP APIs.
 * Used as the default error response format.
 *
 * <p>Standard fields ({@code type}, {@code title}, {@code status}, {@code detail},
 * {@code instance}) are all optional per the spec and omitted from JSON when null.
 *
 * <p>Additional RFC 9457 extension members can be added in two ways:
 * <ul>
 *   <li>Via the builder's {@code .extension(name, value)} method for ad-hoc extensions</li>
 *   <li>By subclassing with {@code @SuperBuilder} to define typed extension fields</li>
 * </ul>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457</a>
 */
@Getter
@SuperBuilder(toBuilder = true)
@Accessors(fluent = true)
@EqualsAndHashCode
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class ProblemDetail {

    String type;
    String title;
    Integer status;
    String detail;
    String instance;

    @Singular("extension")
    @JsonAnyGetter
    Map<String, Object> extensions;

    /**
     * Creates a ProblemDetail from a status code and message.
     */
    public static ProblemDetail of(int status, String detail) {
        return of(status, detail, null);
    }

    /**
     * Creates a ProblemDetail from a status code, message, and instance URI.
     */
    public static ProblemDetail of(int status, String detail, String instance) {
        return ProblemDetail.builder()
                .type("about:blank")
                .title(titleForStatus(status))
                .status(status)
                .detail(detail)
                .instance(instance)
                .build();
    }

    /**
     * Returns the standard HTTP reason phrase for a status code.
     */
    public static String titleForStatus(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 409 -> "Conflict";
            case 413 -> "Payload Too Large";
            case 415 -> "Unsupported Media Type";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "Error";
        };
    }
}
