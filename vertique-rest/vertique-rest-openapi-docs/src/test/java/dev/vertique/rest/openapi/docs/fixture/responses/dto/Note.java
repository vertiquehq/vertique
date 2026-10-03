// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An output DTO whose one member is renamed away from its serialized name: Jackson serializes the
 * field {@code note} as {@code note}, while its {@code @Schema(name = "remark")} makes the output
 * generator publish it as {@code remark}. The output generator's rename report holds exactly {@code
 * (Note, note, note, remark)}.
 */
public class Note {

    /** The text, published under the schema name {@code remark}. */
    @Schema(name = "remark")
    public String note;

    /** Creates an empty note. */
    public Note() {}

    /**
     * Creates a note.
     *
     * @param note the text
     */
    public Note(String note) {
        this.note = note;
    }

    /**
     * Returns the text.
     *
     * @return the text
     */
    public String getNote() {
        return note;
    }
}
