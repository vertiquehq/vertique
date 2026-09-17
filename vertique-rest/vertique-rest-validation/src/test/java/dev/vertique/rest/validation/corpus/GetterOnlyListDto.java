// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import java.util.List;

/**
 * Corpus fixture for a getter-only {@code List<String>} that has a backing field, so Jackson fills
 * it. The gate-versus-binder proof posts {@code {"tags":[1,2]}} and records that the binder coerces
 * the numeric items to strings.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class GetterOnlyListDto {

    private List<String> tags;

    /**
     * Returns the tags.
     *
     * @return the tags
     */
    public List<String> getTags() {
        return tags;
    }
}
