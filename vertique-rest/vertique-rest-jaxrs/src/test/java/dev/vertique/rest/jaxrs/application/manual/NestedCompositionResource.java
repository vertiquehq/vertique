// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import dev.vertique.rest.jaxrs.application.DaggerCompositionComponents_ZeroDeclarationComponent;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * W-1 fixture (round 2 of the P01 gate re-review): a manually contributed resource whose
 * {@code @Inject} constructor builds and resolves a SECOND, entirely independent Dagger
 * component's {@code Set<RouterMount>} — never the component currently composing this resource
 * itself. Unlike {@link ReentrantResource} (G-06), which depends on its OWN component's
 * {@code Set<RouterMount>} and must be rejected, this fixture's nested composition is of a
 * different component and is legitimate: it must succeed.
 *
 * <p>The composition-wide re-entry guard ({@code JaxRsApplicationComposer.COMPOSING}) is, as of
 * the G-06 fix, one {@code ThreadLocal<Boolean>} flag per thread rather than one keyed by
 * component identity, so it cannot tell this legitimate nested composition of a different
 * component apart from a same-component re-entry — both run on the same thread. This fixture
 * proves that gap (round-2 finding W-1).
 *
 * <p>No {@code Provider} indirection is needed here (contrast {@link ReentrantResource}): the
 * nested component is built and resolved imperatively inside the constructor body, invisible to
 * Dagger's own compile-time graph, so there is no compile-time cycle to defer.
 */
@Path("/nested-composition")
public class NestedCompositionResource {

    /**
     * Constructs the resource by building a second, independent
     * {@code CompositionComponents.ZeroDeclarationComponent} and resolving its own
     * {@code Set<RouterMount>} — a nested composition of a different component, which a
     * per-component (not per-thread) guard must allow.
     */
    @Inject
    public NestedCompositionResource() {
        DaggerCompositionComponents_ZeroDeclarationComponent.factory()
                .create(new JsonObject())
                .routerMounts();
    }

    /**
     * Handles {@code GET /nested-composition}; never actually reachable in this proof, since the
     * proof only resolves the resource, it never routes a request to it.
     *
     * @return the fixed body {@code "nested-composition"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String nestedComposition() {
        return "nested-composition";
    }
}
