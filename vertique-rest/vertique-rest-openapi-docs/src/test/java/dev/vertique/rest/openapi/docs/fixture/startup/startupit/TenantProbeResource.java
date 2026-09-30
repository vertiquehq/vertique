// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.startupit;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The one resource of a hand-built JAX-RS mount at the pattern path {@code /:tenant/*}; it declares
 * no {@code tenant} parameter. Its one operation, {@code GET /tenant-probe}, answers with
 * {@value #MARKER}.
 */
@Path("/tenant-probe")
public class TenantProbeResource extends CountingResource {

    /** The resource's path relative to its mount. */
    public static final String PATH = "/tenant-probe";

    /** The literal body the resource answers with. */
    public static final String MARKER = "tenant-probe";

    /** Creates the resource with no answered request. */
    public TenantProbeResource() {
        super(MARKER);
    }

    /**
     * Answers with {@value #MARKER}.
     *
     * @return the marker
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String tenantProbe() {
        return answer();
    }
}
