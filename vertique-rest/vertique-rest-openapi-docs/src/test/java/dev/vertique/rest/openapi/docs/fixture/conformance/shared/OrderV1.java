// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.shared;

/**
 * The order as first published: the declared request body of {@link
 * BackOfficeOrderResource#createOrder}. Its members carry no rename and no hiding marker.
 *
 * @param item     the ordered item
 * @param quantity the ordered quantity
 */
public record OrderV1(String item, int quantity) {}
