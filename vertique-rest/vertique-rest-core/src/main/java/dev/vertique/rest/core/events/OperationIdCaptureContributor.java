// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * INTERNAL framework seam — HTTP-runtime collaborator consumed by sibling framework modules; not
 * an application contract and outside the maturity promise. An application uses the extension
 * points and configuration this module documents and never names this type.
 *
 * <p>{@link OperationHandlerContributor} that captures the OpenAPI {@code operationId} and route
 * template for the current request into routing context data keys consumed by
 * {@link RestRequestCompletionEmitter}.
 *
 * <p>This contributor fires only for requests that reach operation dispatch. Pre-operation failures
 * (e.g., correlation rejection, validation errors before routing) will have a {@code null}
 * {@code operationId} and {@code routeTemplate} in the emitted event.
 *
 * <p>Priority 350 places this in the post-context range (300+), after SecurityContext bridging
 * (200–299), so it never interferes with authentication or authorization contributors.
 *
 * <p>The route template is read from
 * {@link dev.vertique.rest.core.routing.RestOperationDescriptor#routeTemplate()} on the context's
 * neutral operation descriptor (e.g., {@code "/users/{id}"}), not from the Vert.x OpenAPI
 * {@code Operation}.
 */
@Singleton
public final class OperationIdCaptureContributor implements OperationHandlerContributor {

    /** Constructs the contributor. Intended for Dagger constructor injection. */
    @Inject
    public OperationIdCaptureContributor() {}

    /**
     * Returns the priority of this contributor.
     * Runs at priority 350 — in the post-context range, after all authorization and
     * context-bridging contributors.
     *
     * @return {@code 350}
     */
    @Override
    public int priority() {
        return 350;
    }

    /**
     * Adds a routing handler that stores the {@code operationId} and route template on the routing
     * context for later retrieval by {@link RestRequestCompletionEmitter}.
     *
     * @param context the operation registration context providing route metadata and the target route
     */
    @Override
    public void contribute(OperationRegistrationContext context) {
        String operationId = context.operationId();
        String routeTemplate = context.operation().routeTemplate();
        context.route().addHandler(rc -> {
            rc.put(RestRequestCompletionEmitter.KEY_OPERATION_ID, operationId);
            rc.put(RestRequestCompletionEmitter.KEY_ROUTE_TEMPLATE, routeTemplate);
            rc.next();
        });
    }
}
