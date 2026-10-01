// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.dto;

/**
 * A second plain JSON body type, distinct from {@link ItemDto}: an annotation naming this type as
 * the implementation of an {@code ItemDto} body names a type the runtime does not bind.
 */
public final class OtherDto {

    /** The label. */
    public String label;
}
