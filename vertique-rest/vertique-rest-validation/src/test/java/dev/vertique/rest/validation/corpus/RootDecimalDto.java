// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import java.math.BigDecimal;

/**
 * Corpus fixture for the <em>root</em> {@code BigDecimal} value position: a decimal property directly
 * on the body object. Under a profile declaring a {@code BigDecimal} schema-type override this
 * position must carry the override fragment; under a profile that declares none it stays the
 * generator's own numeric shape.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class RootDecimalDto {

    /** The root decimal value position. */
    public BigDecimal amount;
}
