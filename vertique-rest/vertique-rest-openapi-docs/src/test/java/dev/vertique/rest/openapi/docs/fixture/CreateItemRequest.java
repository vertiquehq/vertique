// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

/**
 * The request body of {@link CatalogResource#createItem}. Its members carry no rename and no hiding
 * marker, so its canonical input schema and redaction manifest describe every member as declared.
 *
 * @param name     the item's name
 * @param quantity the item's quantity
 */
public record CreateItemRequest(String name, int quantity) {}
