// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A type annotated {@code @Schema(hidden = true)}. The output generator does not hide a type, so a
 * member of this type is still described; the output generator's hidden-member report holds the
 * type-level entry {@code (this type, null member, SCHEMA_HIDDEN, hideableBySchemaHidden = false)}.
 */
@Schema(hidden = true)
public class NoteZx {

    /** A plain string member. */
    public String text;

    /** Creates an empty note. */
    public NoteZx() {}

    /**
     * Creates a note.
     *
     * @param text the text
     */
    public NoteZx(String text) {
        this.text = text;
    }

    /**
     * Returns the text.
     *
     * @return the text
     */
    public String getText() {
        return text;
    }
}
