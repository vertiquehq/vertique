// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Corpus fixture for the {@code BigDecimal} <em>map key</em> position. A map key is not a value
 * position: it is already a JSON string by construction, so a declared {@code BigDecimal} override
 * must <em>not</em> appear here. The map value is a plain {@code String} so that nothing but the key
 * position distinguishes this document.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class DecimalMapKeyDto {

    /** The map property whose key type is the decimal position under test. */
    public Map<BigDecimal, String> labels;
}
