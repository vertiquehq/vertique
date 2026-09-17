// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import jakarta.validation.constraints.NotNull;

/**
 * Corpus fixture for a required property: a single {@code @NotNull} string, which the generator
 * reports through the document's {@code required} array.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class RequiredPropertyDto {

    /** The required property; drives the {@code required} array. */
    @NotNull
    public String name;
}
