// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.dto;

/**
 * The member type of {@link NotesZx#tags} whose schema the {@code tags-zx} profile replaces with a
 * developer-declared fragment carrying its own {@code propertyNames} keyword (see {@code
 * TagsProfileModule}). Under any other profile the generator describes it as a plain object with one
 * string member.
 */
public class TagsZx {

    /** A plain string member; the profile's override, not this member, decides the published schema. */
    public String k;
}
