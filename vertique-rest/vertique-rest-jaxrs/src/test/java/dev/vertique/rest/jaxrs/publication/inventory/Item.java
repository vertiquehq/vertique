// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

/**
 * Response DTO returned by the response-shape resources.
 *
 * @param id   the item id
 * @param name the item name
 */
public record Item(String id, String name) {}
