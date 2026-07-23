// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.db.model;

/**
 * Request body for creating a new item.
 *
 * @param name        the item name
 * @param description optional description
 */
public record CreateItemRequest(String name, String description) {}
