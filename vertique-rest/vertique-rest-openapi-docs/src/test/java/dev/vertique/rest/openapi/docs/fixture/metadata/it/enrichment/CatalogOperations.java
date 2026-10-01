// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.enrichment;

import dev.vertique.rest.openapi.docs.fixture.metadata.dto.ItemDto;

/**
 * The operations both catalog twins implement, {@link GeneratedCatalogResource} and {@link
 * ReflectedCatalogResource}. It carries no annotation: every JAX-RS and documentation annotation
 * sits on the twins, identically, so the two discovery paths read the same declarations.
 */
public interface CatalogOperations {

    /** The resource path both twins declare, which is also each operation's route in a document. */
    String RESOURCE_PATH = "/items";

    /** The operation id of {@link #listItems}, shared by both twins. */
    String LIST_ITEMS = "listItems";

    /** The operation id of {@link #createItem}, shared by both twins. */
    String CREATE_ITEM = "createItem";

    /**
     * Lists items.
     *
     * @param limit query {@code limit}
     */
    void listItems(String limit);

    /**
     * Creates an item.
     *
     * @param item the request body
     */
    void createItem(ItemDto item);
}
