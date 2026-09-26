// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.apps.resources;

import dev.vertique.codegen.ConditionalOnProperty;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DI-eligible, conditionally-gated JAX-RS resource fixture (T003 TP-005 and TP-006). No fixture
 * configuration sets {@code resources.disabledResource.enabled}, so the condition never matches by
 * default: this resource's catalog entry is cataloged but reports {@code enabled = false}, and no
 * default-mount or application binding ever contributes it, so {@link #CONSTRUCTIONS} must stay
 * {@code 0}.
 */
@Path("/disabled")
@ConditionalOnProperty(name = "resources.disabledResource.enabled")
public class DisabledResource {

    /** Construction count; reset before every test via {@link #reset()}. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Counts the construction. */
    @Inject
    public DisabledResource() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Handles {@code GET /disabled}.
     *
     * @return the fixed body {@code "disabled"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String disabled() {
        return "disabled";
    }

    /** Resets {@link #CONSTRUCTIONS} to {@code 0}. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
    }
}
