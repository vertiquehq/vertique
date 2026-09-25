// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.legacy;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Legacy-shaped JAX-RS resource fixture gated by {@link GeneratedJaxRsResourcesModule}'s
 * {@code PropertyCondition}, mirroring the conditional binding shape
 * {@code GeneratedJaxRsResourcesModuleEmitter} emits for a {@code @ConditionalOnProperty}-annotated
 * resource (the runtime mirror of the compile-time annotation, {@code PropertyCondition.matchesAll}).
 * Every characterization test configuration leaves {@code legacy.disabledResource.enabled} unset, so
 * the condition never matches and {@link #CONSTRUCTIONS} must stay {@code 0}.
 */
@Path("/disabled")
public class DisabledResource {

    /** Construction count; must stay 0 across every test — the condition never matches. */
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
    @Operation(operationId = "zeroDeclarationDisabled")
    public String get() {
        return "disabled";
    }
}
