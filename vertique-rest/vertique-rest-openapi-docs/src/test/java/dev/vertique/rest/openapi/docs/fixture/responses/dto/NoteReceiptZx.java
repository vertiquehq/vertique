// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

/**
 * An output DTO whose member {@code note} has the type {@link NoteZx}, which is annotated
 * {@code @Schema(hidden = true)}. The reachable hidden type is reported as a type-level entry
 * naming {@code NoteZx}.
 */
public class NoteReceiptZx {

    /** A member whose type carries {@code @Schema(hidden = true)}. */
    public NoteZx note;

    /** Creates an empty receipt. */
    public NoteReceiptZx() {}

    /**
     * Creates a receipt.
     *
     * @param note the note
     */
    public NoteReceiptZx(NoteZx note) {
        this.note = note;
    }

    /**
     * Returns the note.
     *
     * @return the note
     */
    public NoteZx getNote() {
        return note;
    }
}
