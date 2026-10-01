// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A class annotated {@code @Schema(hidden = true)}. The input generator does not hide a type, so a
 * body member of this type is still described; the hidden-member report holds a type-level entry
 * {@code (this type, null member, SCHEMA_HIDDEN)}.
 */
@Schema(hidden = true)
public class LedgerNoteZx {

    /** A plain string member. */
    public String text;
}
