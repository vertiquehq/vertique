// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An enum whose constant {@link #INTERNAL_ZX} carries {@code @Schema(hidden = true)}. The output
 * generator cannot leave an enum constant out; a member of this type yields the hidden-member entry
 * {@code (this type, "INTERNAL_ZX", SCHEMA_HIDDEN, hideableBySchemaHidden = false)}.
 */
public enum TierZx {

    /** A published constant. */
    PUBLIC_ZX,

    /** A constant marked hidden, which the generator still describes. */
    @Schema(hidden = true)
    INTERNAL_ZX
}
