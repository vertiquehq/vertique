// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.json.schema.HiddenMember;
import dev.vertique.json.schema.HidingMarker;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import java.util.List;

/**
 * Refuses to publish a request body whose type the document would describe with a member or type
 * that carries a hiding marker ({@code @Hidden} or {@code @Schema(hidden = true)}).
 *
 * <p>Every published body binding is inspected, whatever its schema source and whether or not a body
 * schema was captured: the input-direction generator of the operation's JSON mapper profile reports
 * each marked member or type its description of the body type still holds. The first reported entry
 * fails publication, naming the member or type, its marker, and what makes the document publishable
 * for that position. The message carries names only, never schema content. When the body type cannot
 * be inspected, publication fails without echoing the cause. Nothing is changed: not the generator
 * output, the validation gate, nor the binding.
 */
final class HiddenMemberRefusal {

    private HiddenMemberRefusal() {}

    /**
     * Fails when the description of a published request body holds a hidden member or type.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param operationId the runtime id of the operation
     * @param body the operation's visible body binding
     * @param profileId the operation's resolved JSON mapper profile id
     * @param generators the input generators of the document's assembly
     * @throws RestConfigurationException naming the first reported hidden member or type, or when the
     *     body type could not be inspected
     */
    static void refuse(
            String subject, String operationId, InputBinding body, String profileId, InputGenerators generators) {
        List<HiddenMember> hidden;
        try {
            hidden = generators.generator(profileId).hiddenMembers(body.type());
        } catch (RuntimeException e) {
            throw new RestConfigurationException(subject + ": the request body of operation '" + operationId
                    + "' could not be inspected for hidden members");
        }
        if (hidden.isEmpty()) {
            return;
        }
        HiddenMember first = hidden.get(0);
        throw new RestConfigurationException(subject + ": the request body of operation '" + operationId
                + "' describes " + what(first) + ", which carries " + marker(first.marker()) + "; " + fix(first));
    }

    /** Names the reported member, or the reported type when the entry has no member. */
    private static String what(HiddenMember entry) {
        if (entry.member() == null) {
            return "type " + entry.declaringType();
        }
        return "member '" + entry.member() + "' of " + entry.declaringType();
    }

    /** Names the marker or markers of an entry. */
    private static String marker(HidingMarker marker) {
        return switch (marker) {
            case HIDDEN -> "@Hidden";
            case SCHEMA_HIDDEN -> "@Schema(hidden = true)";
            case BOTH -> "both @Hidden and @Schema(hidden = true)";
        };
    }

    /** Chooses the fix for an entry from its position and marker. */
    private static String fix(HiddenMember entry) {
        if (entry.member() == null) {
            return "the input generator does not hide a type; declare @Schema(hidden = true) on the field or getter"
                    + " of each member that references it, or hide the operation";
        }
        if (entry.hideableBySchemaHidden()) {
            if (entry.marker() == HidingMarker.HIDDEN) {
                return "the input generator ignores @Hidden; declare @Schema(hidden = true) on the property's own"
                        + " field or getter";
            }
            return "the input generator ignores @Schema(hidden = true) where it is declared; declare it directly"
                    + " on the property's own field or getter, not through a bundle or mix-in";
        }
        return "the input generator cannot leave this member out where the document describes it (an enum constant,"
                + " @JsonUnwrapped content, or a property bound through a setter, a builder, a static factory's"
                + " creator parameter, or its value constraints); remove it from the published type, or hide the"
                + " operation";
    }
}
