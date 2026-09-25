// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import dev.vertique.rest.core.router.RouterMount;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Set;

/**
 * G-06 fixture: a manually contributed resource (not a declared {@code Application}) whose
 * {@code @Inject} constructor takes {@code Set<RouterMount>} directly — the mistake C-COMPOSE's
 * threading rule forbids, applied to a resource instead of an application (TP-015's
 * {@code ReentrantApplication} already covers the application-construction re-entry; G-06 is the
 * re-entry the composer's guard does not cover: a resource resolved through
 * {@code @JaxRsResources Provider<Set<Object>> resources} — either the zero-declaration default
 * mount's own body ({@code RestModule.jaxRsRouterMount}), or the composer's step 4 manual-resource
 * resolution, both of which call {@code resources.get()} outside any {@code IN_PROGRESS} guard.
 * Dagger accepts the resulting cycle at compile time only because {@code jaxRsRouterMount} already
 * takes a {@code Provider<Set<Object>> resources} parameter (not a direct {@code Set<Object>}),
 * deferring construction, so the cycle surfaces only when a provider actually calls {@code .get()}
 * synchronously during composition or the zero-declaration default-mount body.
 */
@Path("/reentrant")
public class ReentrantResource {

    /**
     * Constructs the resource, depending directly on the very {@code Set<RouterMount>} its own
     * contribution feeds into — the re-entrant dependency a composition-wide guard must catch
     * instead of recursing until the stack overflows.
     *
     * @param mounts the resolved mount set (never actually used; resolving it is what re-enters
     *               composition)
     */
    @Inject
    public ReentrantResource(Set<RouterMount> mounts) {}

    /**
     * Handles {@code GET /reentrant}; never actually reachable, since resolving this resource's
     * dependency re-enters composition before any router exists.
     *
     * @return the fixed body {@code "reentrant"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String reentrant() {
        return "reentrant";
    }
}
