// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

/**
 * A flat output view with the scalar members {@code viewSku} and {@code viewTitle}, whose names
 * share none with {@link CatalogItem}'s, declared as explicit content in place of a returned {@code
 * CatalogItem}.
 */
public class ItemView {

    /** The stock-keeping unit. */
    public String viewSku;

    /** The display title. */
    public String viewTitle;

    /** Creates an empty view. */
    public ItemView() {}
}
