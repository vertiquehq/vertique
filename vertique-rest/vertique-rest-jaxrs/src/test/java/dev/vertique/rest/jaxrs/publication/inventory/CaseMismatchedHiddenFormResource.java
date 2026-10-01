// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Resource whose one operation hides {@code noteText}, with no location, while binding form field
 * {@code NoteText}: the names differ only in case, and form field names match exactly, so the hiding
 * entry matches no input. Used only by the failing-mount proof.
 *
 * <p>No generated descriptor companion exists for this class, and none may be added: it is a
 * reflection-path fixture, and it is not part of any parity mount.
 */
@Path("/case-mismatched-hidden-form")
public class CaseMismatchedHiddenFormResource {

    /** The name the hidden entry gives. */
    public static final String ENTRY_NAME = "noteText";

    /** The name the method binds. */
    public static final String BOUND_NAME = "NoteText";

    /** Creates the resource. */
    public CaseMismatchedHiddenFormResource() {}

    /**
     * {@code POST /case-mismatched-hidden-form}.
     *
     * @param note form field {@code NoteText}; the method's hidden entry names {@code noteText}
     *             (index 0)
     * @return a fixed body
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = ENTRY_NAME, hidden = true)
    public String hideFormByOtherCase(@FormParam(BOUND_NAME) String note) {
        return "form";
    }
}
