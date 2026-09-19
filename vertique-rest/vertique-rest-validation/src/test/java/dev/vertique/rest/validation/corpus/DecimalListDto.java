// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import java.math.BigDecimal;
import java.util.List;

/**
 * Corpus fixture for the <em>element</em> {@code BigDecimal} value position: the decimal is the item
 * type of an array property, which is a value position and must carry a declared override.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class DecimalListDto {

    /** The array property whose items are the decimal value position. */
    public List<BigDecimal> amounts;
}
