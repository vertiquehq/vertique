// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import java.util.Map;

/**
 * Corpus fixture for a getter-only {@code Map<String, String>} that has a backing field, so Jackson
 * fills it. On the output direction the map property is still described as a bare {@code
 * {"type":"object"}}. On the input direction, values inside it are now described too (rest-023 T003):
 * {@code labels.additionalProperties} publishes the value type's own schema ({@code
 * {"type":"string"}}) rather than staying unconstrained — so the gate-versus-binder proof posts an
 * array for the property itself, exercising the map-level (not the value-level) shape.
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
