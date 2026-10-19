// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.vertique.core.validation.ViolationCarrier;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.core.validation.ViolationPathForm;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One validation failure in a {@link ValidationProblemDetail}.
 *
 * <p>Designed to work with both OpenAPI validation and Jakarta Bean Validation.
 *
 * <p>A body location the framework resolved is reported in {@code pointer}, an RFC 6901 JSON Pointer
 * into the request document over the wire names the request was bound with ({@code ""} is the whole
 * document, {@code "/"} the member with the empty name). A workflow payload rejection carries the
 * engine's pointer into the payload it validated. The framework's validation gates report any other
 * request location in {@code parameter}, which names a non-body request location such as a query
 * parameter, header, path variable, cookie or form field, except where a producer's module document
 * records otherwise. An entry carries at most one of the two, and neither when the location cannot be
 * stated. A {@code pointer} without a {@code location} is relative to the document
 * its producer validated. Test for presence, not truthiness: {@code ""} is a valid pointer and is
 * serialized.
 *
 * <p>When serialized as JSON, {@code null} members are omitted so clients see only the context that is
 * actually available. Unknown members are ignored when reading. The rule that an entry never carries
 * both {@code pointer} and {@code parameter} is enforced by the canonical constructor, so it also
 * rejects a malformed response body read on the client side.
 *
 * <p>Use the {@link #ofPointer(String, String)} or {@link #ofParameter(String, String)} factory
 * methods when location, type, and args context are not applicable.
 *
 * @param pointer   RFC 6901 JSON Pointer into the request document, or {@code null}; {@code ""}
 *                  designates the whole document
 * @param parameter name of a non-body request location (e.g., {@code "page"}, {@code "X-Token"}), or
 *                  {@code null}
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
     * Creates a {@link ValidationErrorDetail} locating the failure in the request document.
     *
     * @param pointer RFC 6901 JSON Pointer into the request document; {@code ""} is the whole document
     * @param detail  human-readable description of the validation failure
     * @return a new {@link ValidationErrorDetail} with the pointer and detail set and every other
     *         member {@code null}
     */
    public static ValidationErrorDetail ofPointer(String pointer, String detail) {
        return new ValidationErrorDetail(pointer, null, detail, null, null, null);
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
        return new ValidationErrorDetail(null, parameter, detail, null, null, null);
    }

    /**
     * Renders the violations a {@link ViolationCarrier} holds, one entry each in the carrier's order, by the
     * path form the carrier declares.
     *
     * <p>For {@link ViolationPathForm#JSON_POINTER} a detail's path becomes {@code pointer} when
     * {@link ViolationPathForm#accepts(String)} holds; a path it rejects is left off, so the entry keeps
     * its message, type and args and states no location. For any other form the paths are Java property
     * paths, which are not request locations, so no entry carries {@code pointer}, {@code parameter} or
     * {@code location}. The form is read from the declaration only, never from how a path looks.
     *
     * <p>The list has as many entries as the carrier has violations; it is not capped, and logging is
     * the caller's concern. An application that renders its own error shape can call this to reuse the
     * rules.
     *
     * @param carrier the exception holding the violations
     * @return an immutable list of entries
     */
    public static List<ValidationErrorDetail> ofViolations(ViolationCarrier carrier) {
        ViolationPathForm form = carrier.violationPathForm();
        List<ViolationDetail> violations = carrier.violations();
        List<ValidationErrorDetail> entries = new ArrayList<>(violations.size());
        for (ViolationDetail violation : violations) {
            String pointer =
                    form == ViolationPathForm.JSON_POINTER && form.accepts(violation.path()) ? violation.path() : null;
            entries.add(new ValidationErrorDetail(
                    pointer, null, violation.message(), null, violation.type(), violation.args()));
        }
        return List.copyOf(entries);
    }

    /**
     * Returns a copy with the given {@code location}; every other member is carried over.
     *
     * @param location where the invalid value was found, or {@code null}
     * @return the copy
     */
    public ValidationErrorDetail withLocation(@Nullable String location) {
        return new ValidationErrorDetail(pointer, parameter, detail, location, type, args);
    }

    /**
     * Returns a copy with the given {@code type}; every other member is carried over.
     *
     * @param type error classification, or {@code null}
     * @return the copy
     */
    public ValidationErrorDetail withType(@Nullable String type) {
        return new ValidationErrorDetail(pointer, parameter, detail, location, type, args);
    }

    /**
     * Returns a copy with the given {@code args}; every other member is carried over.
     *
     * @param args constraint arguments, or {@code null}
     * @return the copy
     */
    public ValidationErrorDetail withArgs(@Nullable Map<String, Object> args) {
        return new ValidationErrorDetail(pointer, parameter, detail, location, type, args);
    }
}
