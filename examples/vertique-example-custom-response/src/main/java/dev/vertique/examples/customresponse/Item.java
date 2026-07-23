// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.customresponse;

/**
 * Represents a named item in the in-memory item store.
 *
 * @param id   the unique identifier of the item
 * @param name the display name of the item
 */
public record Item(String id, String name) {}
