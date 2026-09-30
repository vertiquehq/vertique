// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * Resource without any {@code @JsonProfile}, so its operation resolves the configured
 * {@code jaxrs.jsonProfile}.
 */
@Path("/unprofiled")
public class UnprofiledResponsesResource {

    /**
     * Returns a plain DTO.
     *
     * @return an item
     */
    @GET
    public Item plainItem() {
        return new Item("2", "two");
    }
}
