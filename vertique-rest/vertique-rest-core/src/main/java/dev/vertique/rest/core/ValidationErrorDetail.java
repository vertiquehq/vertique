// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * One validation failure in a {@link ValidationProblemDetail}.
 *
 * <p>Designed to work with both OpenAPI validation and Jakarta Bean Validation.
 *
 * <p>A body location is reported in {@code pointer}, an RFC 6901 JSON Pointer into the request
 * document over the wire names the request was bound with ({@code ""} is the whole document,
 * {@code "/"} the member with the empty name). Any other request location is reported in
 * {@code parameter}, which names a non-body request location such as a query parameter, header, path
 * variable, cookie or form field. An entry carries at most one of the two, and neither when the
 * location cannot be stated. A {@code pointer} without a {@code location} is relative to the document
 * its producer validated. Test for presence, not truthiness: {@code ""} is a valid pointer and is
 * serialized.
 *
 * <p>{@code path} is the member earlier releases used for both kinds of location; in this release it
 * is replaced by {@code pointer} and {@code parameter}. Producers that have not moved over keep filling
 * it, so it is {@code null} for entries created with {@link #ofPointer(String, String)} or
 * {@link #ofParameter(String, String)}.
 *
 * <p>When serialized as JSON, {@code null} members are omitted so clients see only the context that is
 * actually available. Unknown members are ignored when reading. The rule that an entry never carries
 * both {@code pointer} and {@code parameter} is enforced by the canonical constructor, so it also
 * rejects a malformed response body read on the client side.
 *
 * <p>Use the {@link #of(String, String)}, {@link #ofPointer(String, String)} or
 * {@link #ofParameter(String, String)} factory methods when location, type, and args context are not
 * applicable.
 *
 * @param pointer   RFC 6901 JSON Pointer into the request document, or {@code null}; {@code ""}
 *                  designates the whole document
 * @param parameter name of a non-body request location (e.g., {@code "page"}, {@code "X-Token"}), or
 *                  {@code null}
 * @param path      path identifying the invalid field (e.g., {@code "/name"}, {@code "email"}), or
 *                  {@code null}; replaced by {@code pointer} and {@code parameter} in this release
 * @param detail    human-readable description of the validation failure
 * @param location  where the invalid value was found — {@code "body"}, {@code "query"},
 *                  {@code "header"}, {@code "path"}, {@code "cookie"}, {@code "form"},
 *                  {@code "file"}, or {@code null}
 * @param type      error classification — {@code "required"}, {@code "invalid_value"},
 *                  {@code "format"}, {@code "type"}, or {@code null}
 * @param args      constraint arguments extracted from the annotation (e.g., {@code {min: 1, max: 100}}
 *                  for {@code @Size}); {@code null} when the constraint has no meaningful arguments
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidationErrorDetail(
        @Nullable String pointer,
        @Nullable String parameter,
        @Nullable String path,
        String detail,
        @Nullable String location,
        @Nullable String type,
        @Nullable Map<String, Object> args) {

    /**
     * Canonical constructor, used by Jackson when reading.
     *
     * @throws IllegalArgumentException if both {@code pointer} and {@code parameter} are non-null;
     *                                  an empty string counts as present
     */
    @JsonCreator
    public ValidationErrorDetail {
        if (pointer != null && parameter != null) {
            throw new IllegalArgumentException("An error entry carries at most one of pointer and parameter");
        }
    }

    /**
     * Creates a {@link ValidationErrorDetail} with {@code path} and no {@code pointer} or
     * {@code parameter}.
     *
     * @param path     path identifying the invalid field
     * @param detail   human-readable description of the validation failure
     * @param location where the invalid value was found, or {@code null}
     * @param type     error classification, or {@code null}
     * @param args     constraint arguments, or {@code null}
     */
    public ValidationErrorDetail(
            String path,
            String detail,
            @Nullable String location,
            @Nullable String type,
            @Nullable Map<String, Object> args) {
        this(null, null, path, detail, location, type, args);
    }

    /**
     * Creates a {@link ValidationErrorDetail} with path and detail only, leaving pointer, parameter,
     * location, type, and args {@code null}. Use this for simple validation errors without OpenAPI or
     * parameter-location context.
     *
     * @param path   path identifying the invalid field
     * @param detail human-readable description of the validation failure
     * @return a new {@link ValidationErrorDetail} with {@code null} pointer, parameter, location,
     *         type, and args
     */
    public static ValidationErrorDetail of(String path, String detail) {
        return new ValidationErrorDetail(path, detail, null, null, null);
    }

    /**
     * Creates a {@link ValidationErrorDetail} locating the failure in the request document.
     *
     * @param pointer RFC 6901 JSON Pointer into the request document; {@code ""} is the whole document
     * @param detail  human-readable description of the validation failure
     * @return a new {@link ValidationErrorDetail} with the pointer and detail set and every other
     *         member {@code null}
     */
    public static ValidationErrorDetail ofPointer(String pointer, String detail) {
        return new ValidationErrorDetail(pointer, null, null, detail, null, null, null);
    }

    /**
     * Creates a {@link ValidationErrorDetail} locating the failure in a non-body request location.
     *
     * @param parameter name of the query parameter, header, path variable, cookie, form field or
     *                  other non-body request location
     * @param detail    human-readable description of the validation failure
     * @return a new {@link ValidationErrorDetail} with the parameter and detail set and every other
     *         member {@code null}
     */
    public static ValidationErrorDetail ofParameter(String parameter, String detail) {
        return new ValidationErrorDetail(null, parameter, null, detail, null, null, null);
    }

    /**
     * Returns a copy with the given {@code location}; every other member is carried over.
     *
     * @param location where the invalid value was found, or {@code null}
     * @return the copy
     */
    public ValidationErrorDetail withLocation(@Nullable String location) {
        return new ValidationErrorDetail(pointer, parameter, path, detail, location, type, args);
    }

    /**
     * Returns a copy with the given {@code type}; every other member is carried over.
     *
     * @param type error classification, or {@code null}
     * @return the copy
     */
    public ValidationErrorDetail withType(@Nullable String type) {
        return new ValidationErrorDetail(pointer, parameter, path, detail, location, type, args);
    }

    /**
     * Returns a copy with the given {@code args}; every other member is carried over.
     *
     * @param args constraint arguments, or {@code null}
     * @return the copy
     */
    public ValidationErrorDetail withArgs(@Nullable Map<String, Object> args) {
        return new ValidationErrorDetail(pointer, parameter, path, detail, location, type, args);
    }
}
