// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

/**
 * Corpus fixture for the <em>nested</em> {@code BigDecimal} value position: the decimal sits one
 * object deeper, inside {@link RootDecimalDto}, so the document proves an override reaches positions
 * the generator may emit behind a {@code $ref}.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class NestedDecimalDto {

    /** The nested object holding the decimal value position. */
    public RootDecimalDto nested;
}
