// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * Describes a single violation with its path, message, classified type, and constraint arguments.
 *
 * <p>This is an HTTP-agnostic violation detail. For REST-specific details with location
 * context (query, header, body, etc.), see {@code ValidationErrorDetail} in the {@code rest-core} module.
 *
 * <p>The invalid value is intentionally excluded to prevent accidental leakage of sensitive
 * data (passwords, tokens, PII) in error responses or logs.
 *
 * <p>The {@code path} is the producer's own: a Java property path for an object it validated, or an
 * RFC 6901 pointer for a document it validated, as {@code form} states. It is never a location in a
 * request by itself; a transport that knows where the validated value sits maps it at its own
 * boundary. A {@code null} path says the violation locates nothing in any validated value, as for a
 * rule that concerns the whole operation or a marker such as "further violations omitted".
 *
 * @param path    the path of the violation in the form {@code form} states (e.g., {@code "name"},
 *                {@code "address.city"}, {@code "/address/city"}), or {@code null} when the violation
 *                locates nothing
 * @param message the interpolated constraint violation message
 * @param type    the classified violation type (e.g., {@code "required"}, {@code "size"}, {@code "min"});
 *                derived from the constraint annotation with annotation simple name as fallback;
 *                {@code null} when type resolution is not configured. An application's own rule
 *                identifier can be carried here
 * @param args    constraint arguments extracted from the annotation (e.g., {@code {min: 1, max: 100}}
 *                for {@code @Size}); {@code null} when the constraint has no meaningful arguments
 * @param form    how {@code path} is written; {@link ViolationPathForm#JAVA_PROPERTY_PATH} when
 *                {@code null}. It is never part of the JSON form of a detail
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViolationDetail(
        @Nullable String path,
        String message,
        @Nullable String type,
        @Nullable Map<String, Object> args,
        @JsonIgnore ViolationPathForm form) {

    /** Treats a missing form as {@link ViolationPathForm#JAVA_PROPERTY_PATH}. */
    public ViolationDetail {
        if (form == null) {
            form = ViolationPathForm.JAVA_PROPERTY_PATH;
        }
    }

    /**
     * Creates a detail whose path is a Java property path, or {@code null} for a violation that locates
     * nothing.
     *
     * @param path    the property path, or {@code null}
     * @param message the violation message
     * @param type    the violation type, or {@code null}
     * @param args    the constraint arguments, or {@code null}
     */
    public ViolationDetail(
            @Nullable String path, String message, @Nullable String type, @Nullable Map<String, Object> args) {
        this(path, message, type, args, ViolationPathForm.JAVA_PROPERTY_PATH);
    }

    /**
     * Creates a {@link ViolationDetail} with path and message only, leaving type and args {@code null}.
     *
     * @param path    the property path
     * @param message the violation message
     * @return a new detail with no type or args
     */
    public static ViolationDetail of(String path, String message) {
        return new ViolationDetail(path, message, null, null);
    }

    /**
     * Creates a detail whose path is an RFC 6901 pointer into the document the producer validated.
     *
     * <p>The producer guarantees the pointer holds no key the client chose. A transport still checks it
     * with {@link ViolationPathForm#accepts(String)} before it reports it as a location.
     *
     * @param pointer the pointer into the validated document; {@code ""} is the whole document
     * @param message the violation message
     * @param type    the violation type, or {@code null}
     * @param args    the constraint arguments, or {@code null}
     * @return a new detail in {@link ViolationPathForm#JSON_POINTER} form
     */
    public static ViolationDetail atPointer(
            String pointer, String message, @Nullable String type, @Nullable Map<String, Object> args) {
        return new ViolationDetail(pointer, message, type, args, ViolationPathForm.JSON_POINTER);
    }

    /**
     * Creates a detail that locates nothing in any validated value.
     *
     * @param message the violation message
     * @param type    the violation type or rule identifier, or {@code null}
     * @return a new detail with a {@code null} path
     */
    public static ViolationDetail unlocated(String message, @Nullable String type) {
        return new ViolationDetail(null, message, type, null);
    }
}
