// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.db.model;

/**
 * Request body for updating an item.
 *
 * @param name        the item name
 * @param description optional description
 */
public record UpdateItemRequest(String name, String description) {}
