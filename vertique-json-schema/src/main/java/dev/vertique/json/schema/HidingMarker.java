// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

/**
 * The hiding marker or markers a {@link HiddenMember} carries.
 *
 * <p>The two markers are {@code io.swagger.v3.oas.annotations.Hidden} and {@code @Schema(hidden =
 * true)}. Each is read wherever it is declared: directly, through a Jackson annotation bundle at any
 * depth, or through the mix-in the profile's mapper registers for the declaring class.
 */
public enum HidingMarker {

    /** Only {@code io.swagger.v3.oas.annotations.Hidden} is present. */
    HIDDEN,

    /** Only {@code @Schema(hidden = true)} is present. */
    SCHEMA_HIDDEN,

    /** Both {@code io.swagger.v3.oas.annotations.Hidden} and {@code @Schema(hidden = true)} are present. */
    BOTH
}
