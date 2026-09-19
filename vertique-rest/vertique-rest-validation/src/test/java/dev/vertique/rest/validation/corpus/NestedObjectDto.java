// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

/**
 * Corpus fixture for a nested object property: whether the nested schema is inlined or emitted as a
 * {@code $defs} entry behind a {@code $ref} is part of the pinned document shape.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class NestedObjectDto {

    /** The nested object property. */
    public NestedAddressDto address;
}
