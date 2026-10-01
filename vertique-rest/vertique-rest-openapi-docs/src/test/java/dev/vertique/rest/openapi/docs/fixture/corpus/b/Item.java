// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.corpus.b;

import jakarta.validation.constraints.Pattern;

/**
 * A body type sharing its simple name, and its nested type's simple name, with {@code
 * fixture.corpus.a.Item}: both schemas hold a definition named after {@link Tag}, with different
 * constraints. Here a tag code is digits only.
 */
public final class Item {

    /** The primary tag. */
    public Tag primary;

    /** The secondary tag, of the same type, so the schema shares one definition. */
    public Tag secondary;

    /** A tag whose code is digits only. */
    public static final class Tag {

        /** Digits only. */
        @Pattern(regexp = "^[0-9]+$")
        public String code;
    }
}
