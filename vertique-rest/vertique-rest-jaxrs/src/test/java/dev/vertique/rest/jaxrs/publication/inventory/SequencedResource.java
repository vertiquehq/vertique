// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Resource that carries no {@code @GroupSequence} itself but inherits one from
 * {@link SequencedBase}, so its {@code Default}-group constraints are subject to a group sequence.
 */
@Path("/sequenced")
public class SequencedResource extends SequencedBase {

    /**
     * Lists by region.
     *
     * @param region query {@code region}, {@code @NotNull} in {@code Default}
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String listSequenced(@QueryParam("region") @NotNull String region) {
        return "sequenced";
    }
}
