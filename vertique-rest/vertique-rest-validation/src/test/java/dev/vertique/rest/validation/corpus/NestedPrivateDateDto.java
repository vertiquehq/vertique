// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

/**
 * Corpus fixture holding {@link PrivateDatePropertyDto} as a property, so the nested position of a
 * restored shape is pinned as well as the root one.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class NestedPrivateDateDto {

    private PrivateDatePropertyDto detail;

    /**
     * Returns the nested detail.
     *
     * @return the nested detail
     */
    public PrivateDatePropertyDto getDetail() {
        return detail;
    }
}
