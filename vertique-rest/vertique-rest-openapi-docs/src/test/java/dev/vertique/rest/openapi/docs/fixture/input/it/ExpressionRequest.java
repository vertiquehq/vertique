// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import jakarta.validation.constraints.Pattern;

/**
 * The request body of {@link VerbatimResource}: one member with an authored {@code @Pattern} that
 * carries flags. No member has a reserved name, so the generator lists nothing for redaction.
 */
public final class ExpressionRequest {

    /** The authored regular expression of {@link #expression}. */
    public static final String EXPRESSION_PATTERN = "^a.b # comment$";

    /** The member with the authored pattern and its {@code DOTALL} and {@code COMMENTS} flags. */
    @Pattern(
            regexp = EXPRESSION_PATTERN,
            flags = {Pattern.Flag.DOTALL, Pattern.Flag.COMMENTS})
    public String expression;

    /** Creates an empty request. */
    public ExpressionRequest() {}
}
