// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

/**
 * Request-body DTO bound by the inventory resources' body parameters.
 *
 * @param sku      the ordered item's SKU
 * @param quantity the ordered quantity
 */
public record Order(String sku, int quantity) {}
