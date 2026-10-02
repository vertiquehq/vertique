// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.shared;

/**
 * {@link OrderV1} with one more member, {@code giftNote}. Only {@link OrderBodySwitchingSchemaSource}
 * describes it; no resource declares it.
 *
 * @param item     the ordered item
 * @param quantity the ordered quantity
 * @param giftNote a note printed on the gift card
 */
public record OrderV2(String item, int quantity, String giftNote) {}
