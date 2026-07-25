// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/** JAX-RS-only consumer used to prove that the facade keeps unrelated processors inert. */
@Path("/single")
public final class SingleFeatureApp {

    @Inject
    public SingleFeatureApp() {}

    @GET
    public String get() {
        return "single";
    }
}
