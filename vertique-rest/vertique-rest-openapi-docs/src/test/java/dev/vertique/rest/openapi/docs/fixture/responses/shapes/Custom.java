// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.shapes;

/**
 * A result type with a registered response producer ({@link CustomProducers#binding()}): a value of
 * this type, or of a subclass, is turned into a response by that producer at runtime, so no
 * document can describe its body.
 */
public class Custom {

    /** A member a JSON encoder would otherwise serialize. */
    public String payload;

    /** Creates an empty result. */
    public Custom() {}
}
