// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.b;

import jakarta.validation.constraints.Size;

/**
 * The request body of {@link OrdersResource}. It shares its simple name, and its nested type's
 * simple name, with {@code fixture.input.a.Item}, with different constraints. Its nested {@link Tag}
 * is used by two members, so the generator describes it as one root local definition, which
 * publication relocates.
 */
public final class Item {

    /** The primary tag. */
    public Tag primary;

    /** The secondary tag, of the same type. */
    public Tag secondary;

    /** Creates an empty item. */
    public Item() {}

    /** An order tag whose code has at most five characters. */
    public static final class Tag {

        /** At most five characters. */
        @Size(max = 5)
        public String code;

        /** Creates an empty tag. */
        public Tag() {}
    }
}
