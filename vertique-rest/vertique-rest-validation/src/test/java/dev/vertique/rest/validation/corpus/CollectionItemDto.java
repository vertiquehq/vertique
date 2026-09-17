// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import jakarta.validation.constraints.NotNull;

/**
 * Corpus fixture for a mixed scalar object, and the element type of the generic-collection body
 * fixture {@link SchemaCorpus#COLLECTION_BODY}. Its two properties are out of canonical key order in
 * declaration order, so the document also witnesses canonical ordering.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class CollectionItemDto {

    /** Required string property. */
    @NotNull
    public String sku;

    /** Primitive integer property. */
    public int quantity;
}
