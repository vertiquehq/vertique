// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

/** A request body without local definitions: one string member. */
public final class NoteRequest {

    /** The note's text. */
    public String text;

    /** Creates an empty request. */
    public NoteRequest() {}
}
