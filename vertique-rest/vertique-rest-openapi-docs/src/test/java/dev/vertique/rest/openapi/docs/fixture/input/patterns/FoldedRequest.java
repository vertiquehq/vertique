// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.patterns;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * A request body bound case-insensitively, with two published members and no reserved name. The
 * input-direction generator describes each member's other ASCII casings as a {@code
 * patternProperties} key ending in the portable end anchor {@code (?![\s\S])}, so its captured schema
 * carries generator-built patterns; its manifest lists nothing for redaction.
 */
@JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
public final class FoldedRequest {

    /** The first member. */
    public String title;

    /** The second member. */
    public String author;

    /** Creates an empty request. */
    public FoldedRequest() {}
}
