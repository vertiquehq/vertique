// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual.membership;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 case 13's resource: contributed manually twice, by two separate modules
 * ({@link DuplicateManualResourceModuleA} and {@link DuplicateManualResourceModuleB}), so when
 * listed it has two manual matches (both trivially {@code sameSurface}-true, since both instances
 * are exactly this class), tripping C-COMPOSE step 6.7's ambiguity check. No catalog entry exists
 * for this class.
 */
@Path("/duplicate-manual")
public class DuplicateManualResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public DuplicateManualResource() {}

    /**
     * Handles {@code GET /duplicate-manual}.
     *
     * @return the fixed body {@code "duplicateManual"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String duplicateManual() {
        return "duplicateManual";
    }
}
