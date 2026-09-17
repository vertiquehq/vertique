// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Corpus fixture for the swagger-2 {@code ##default} sentinel: an unset annotation default that must
 * not reach the published document.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class DefaultSentinelDto {

    /** Property whose Swagger default is the sentinel the generator strips. */
    @Schema(defaultValue = "##default")
    public String label;
}
