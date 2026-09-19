// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import jakarta.validation.constraints.NotBlank;

/**
 * The nested object referenced by {@link NestedObjectDto}. It carries its own required property so
 * the corpus shows whether nested {@code required} arrays survive a generator or profile change.
 *
 * <p>This type is a corpus subject only through its owner; it is deliberately not a standalone entry
 * in {@link SchemaCorpus#FIXTURES}.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class NestedAddressDto {

    /** The nested required property. */
    @NotBlank
    public String city;
}
