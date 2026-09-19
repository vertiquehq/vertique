// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import java.util.Map;

/**
 * Corpus fixture for a getter-only {@code Map<String, String>} that has a backing field, so Jackson
 * fills it. A map property is described as a bare {@code {"type":"object"}} under both generators —
 * values inside it are not described, the gap {@code spec.md} § Known description gaps records as S2
 * — so the gate-versus-binder proof posts an array for the property itself rather than a wrong-typed
 * value inside it.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class GetterOnlyMapDto {

    private Map<String, String> labels;

    /**
     * Returns the labels.
     *
     * @return the labels
     */
    public Map<String, String> getLabels() {
        return labels;
    }
}
