// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.patterns;

import jakarta.validation.constraints.Pattern;

/**
 * A request body with one member carrying an authored {@code @Pattern} with flags, and two members
 * of one nested type, which the generator describes as a root local definition holding an authored
 * pattern, so a pattern is published inside a relocated definition. No member has a reserved name,
 * so the generator lists nothing for redaction.
 */
public final class FlaggedRequest {

    /** The authored regular expression of {@link #expression}. */
    public static final String EXPRESSION_PATTERN = "^a.b # c$";

    /** The authored regular expression of {@link Code#value}. */
    public static final String CODE_PATTERN = "^[A-Z]{3}$";

    /** The member with the authored pattern and its {@code DOTALL} and {@code COMMENTS} flags. */
    @Pattern(
            regexp = EXPRESSION_PATTERN,
            flags = {Pattern.Flag.DOTALL, Pattern.Flag.COMMENTS})
    public String expression;

    /** The first code. */
    public Code from;

    /** The second code, of the same type. */
    public Code to;

    /** Creates an empty request. */
    public FlaggedRequest() {}

    /** A code of three capital letters. */
    public static final class Code {

        /** Three capital letters. */
        @Pattern(regexp = CODE_PATTERN)
        public String value;

        /** Creates an empty code. */
        public Code() {}
    }
}
