// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime.fixture;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * JAX-RS resource fixture used by
 * {@link dev.vertique.rest.jaxrs.ResourceScannerDescriptorSeamTest} to exercise the
 * descriptor fast-path. The companion {@link SeamResource_JaxRsDescriptor} is present on
 * the test classpath and returns a single {@code ResourceMethodMeta}, so the seam test can
 * assert that the generated-descriptor path was taken (not the reflective walk).
 */
@Path("/seam")
public class SeamResource {

    /**
     * Endpoint method — only present so the reflective fallback (miss path) can discover it
     * independently when the companion is absent.
     *
     * @return a constant string
     */
    @GET
    @Path("/hello")
    public String hello() {
        return "hello";
    }
}
