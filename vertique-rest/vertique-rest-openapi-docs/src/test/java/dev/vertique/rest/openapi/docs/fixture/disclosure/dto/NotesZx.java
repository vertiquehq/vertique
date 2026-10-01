// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.HashMap;
import java.util.Map;

/**
 * A case-sensitively bound request body with one schema-hidden member, one ignored member, one
 * published member, a member whose type a profile overrides, and described extras.
 *
 * <p>Because the type accepts any extra member, the input-direction generator reserves both
 * unpublished names in the root guard ({@code propertyNames.not.enum} listing {@code auditTrailZx}
 * and {@code internalNoteZx}) and lists that guard, and only that guard, in the redaction manifest
 * ({@code /propertyNames}). Under the {@code tags-zx} profile, {@link #tags} is described by the
 * profile's fragment verbatim, which declares its own {@code propertyNames} that the manifest never
 * lists. The hidden-member report of this type is empty: the hiding marker sits on the property's
 * own field.
 */
public class NotesZx {

    /** Hidden from the document; still reserved, so the gate refuses it as an extra. */
    @Schema(hidden = true)
    public String internalNoteZx;

    /** Ignored by Jackson; still reserved, so the gate refuses it as an extra. */
    @JsonIgnore
    public String auditTrailZx;

    /** The one published string member. */
    public String title;

    /** A member whose schema the {@code tags-zx} profile overrides. */
    public TagsZx tags;

    private final Map<String, Object> extra = new HashMap<>();

    /**
     * Accepts any member not declared above.
     *
     * @param k the member name
     * @param v the member value
     */
    @JsonAnySetter
    public void any(String k, Object v) {
        extra.put(k, v);
    }
}
