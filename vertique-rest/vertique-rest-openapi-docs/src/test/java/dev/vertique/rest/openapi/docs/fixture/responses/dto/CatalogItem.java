// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

/**
 * A flat output entity with the scalar members {@code sku} and {@code title}, returned as a plain
 * entity or a list by catalog operations.
 */
public class CatalogItem {

    /** The stock-keeping unit. */
    public String sku;

    /** The display title. */
    public String title;

    /** Creates an empty item. */
    public CatalogItem() {}
}
