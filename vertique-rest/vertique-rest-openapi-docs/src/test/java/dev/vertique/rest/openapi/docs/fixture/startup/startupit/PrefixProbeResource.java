// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.startupit;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The one resource of a hand-built JAX-RS mount placed at or beside the documentation prefix. Its
 * one operation, {@code GET /probe}, answers with {@value #MARKER}.
 */
@Path("/probe")
public class PrefixProbeResource extends CountingResource {

    /** The resource's path relative to its mount. */
    public static final String PATH = "/probe";

    /** The literal body the resource answers with. */
    public static final String MARKER = "prefix-probe";

    /** Creates the resource with no answered request. */
    public PrefixProbeResource() {
        super(MARKER);
    }

    /**
     * Answers with {@value #MARKER}.
     *
     * @return the marker
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String prefixProbe() {
        return answer();
    }
}
