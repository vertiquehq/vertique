// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs.resource;

/**
 * A published catalog item.
 *
 * @param id   the public item identifier
 * @param name the item name
 */
public record CatalogItem(String id, String name) {}
