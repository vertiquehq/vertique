// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares validation constraints for multipart file parts bound to this parameter. Valid only on
 * {@code FileUpload} and {@code List<FileUpload>} parameters (named via {@code @FormParam} or the
 * unannotated aggregate). On an aggregate parameter the constraints apply to every file part it
 * receives.
 *
 * <p>Not valid on {@code EntityPart} parameters in v1: an {@code EntityPart} parameter may bind a
 * text form field, which file-part validation cannot see, so the constraint would be misleading.
 * Annotating an {@code EntityPart} parameter is a startup route violation.
 *
 * <p>Startup route violations (fail-closed, {@code INVALID_FILE_PART_DECLARATION}): placement on a
 * non-file or {@code EntityPart} parameter; {@code maxSizeBytes == 0} or {@code < -1}; an {@code
 * allowedTypes} entry outside the frozen grammar; two constrained declarations covering the same
 * part name.
 *
 * <p>{@code allowedTypes} grammar (exact): {@code type "/" subtype} with exactly one slash; {@code
 * type} and {@code subtype} are non-empty RFC 7230 token strings (ALPHA / DIGIT / {@code
 * !#$%&'*+-.^_`|~}); {@code *} is permitted only as the entire subtype ({@code type/*}). No
 * parameters, whitespace, or quality factors; {@code *}{@code /*}, {@code *}{@code /subtype},
 * extra slashes ({@code image/png/extra}), control or non-token characters, and empty entries are
 * rejected. Entries are parsed and lowercase-canonicalized once at descriptor creation.
 *
 * <p>Size constraints are <b>post-spool validation</b>: parts are already written to the uploads
 * directory (within the global body limit) before validation runs. The only ingress protection is
 * {@code HttpConfig.maxBodySize}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FilePart {

    /**
     * Returns the allowed declared media types. An empty array permits any declared media type.
     *
     * @return the allowed media types
     */
    String[] allowedTypes() default {};

    /**
     * Returns the maximum part size in bytes, or {@code -1} when no per-part cap applies.
     *
     * @return the maximum part size in bytes, or {@code -1}
     */
    long maxSizeBytes() default -1;
}
