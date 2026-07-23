// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.validation;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * The {@code none} {@link RequestValidationStrategy}: installs no validation gate for any operation
 * (FR-004). Requests flow straight to dispatch, so a body or parameter that would violate a schema
 * constraint is never rejected by a 400 gate — it is deserialized and dispatched as-is. Applications
 * that want no request-validation overhead select this strategy by its {@link #id()}.
 */
@Singleton
public final class NoneValidationStrategy implements RequestValidationStrategy {

    /** The selection id for the no-validation strategy. */
    public static final String ID = "none";

    /**
     * Creates the no-validation strategy.
     */
    @Inject
    public NoneValidationStrategy() {}

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ExtensionPhase phase() {
        return ExtensionPhase.SYSTEM_FIRST;
    }

    @Override
    public int priority() {
        return 0;
    }

    /**
     * Always returns {@link Optional#empty()} — the {@code none} strategy never installs a gate, so
     * every request reaches dispatch without schema validation.
     *
     * @param op      the operation descriptor (unused)
     * @param schemas the synthesized schemas (unused)
     * @return {@link Optional#empty()} always
     */
    @Override
    public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
        return Optional.empty();
    }
}
