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
 * Generated-path twin of {@link SequencedResource}: it too extends {@link SequencedBase}, so it
 * inherits the same class-level {@code @GroupSequence}. Its hand-written companion
 * {@link GeneratedSequencedResource_JaxRsDescriptor} makes the scanner take the generated-descriptor
 * path.
 */
@Path("/sequenced")
public class GeneratedSequencedResource extends SequencedBase {

    /**
     * Lists by region; see {@link SequencedResource#listSequenced}.
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
