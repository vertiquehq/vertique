// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.corpus.a;

import jakarta.validation.constraints.Size;

/**
 * A body type sharing its simple name, and its nested type's simple name, with {@code
 * fixture.corpus.b.Item}: both schemas hold a definition named after {@link Tag}, with different
 * constraints. Here a tag code has at most three characters.
 */
public final class Item {

    /** The primary tag. */
    public Tag primary;

    /** The secondary tag, of the same type, so the schema shares one definition. */
    public Tag secondary;

    /** A tag whose code has at most three characters. */
    public static final class Tag {

        /** At most three characters. */
        @Size(max = 3)
        public String code;
    }
}
