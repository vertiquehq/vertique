// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.validation;

import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.Optional;

/**
 * Pluggable strategy that decides whether — and how — a REST operation's request is validated before
 * dispatch (FR-004/FR-005). A strategy is selected by its {@link #id()} (a string, not an enum, so
 * applications can contribute their own strategies) and asked, at router-build time, to produce an
 * optional per-operation validation gate.
 *
 * <p>The gate is a Vert.x {@link Handler} installed on the operation's route ahead of dispatch. When
 * a strategy returns {@link Optional#empty()} from {@link #gateFor}, no gate is installed and the
 * request flows straight to dispatch (the {@code none} strategy behaves this way for every
 * operation). When it returns a handler, that handler either {@code ctx.next()}s a conforming request
 * or {@code ctx.fail(...)}s a non-conforming one with a validation exception that the REST error
 * pipeline maps to a 400 {@code application/problem+json} response.
 *
 * <p>Strategies are {@link OrderedExtension}s so their relative ordering is deterministic when more
 * than one is registered. Built-in strategy ids are {@code "web-validation"} (the default
 * vertx-json-schema gate), {@code "none"} (no validation), and the opt-in {@code "openapi-contract"}
 * strategy (provided by {@code vertique-rest-openapi-validation}).
 */
public interface RequestValidationStrategy extends OrderedExtension {

    /**
     * Returns the stable string identifier under which this strategy is selected by configuration. The
     * identifier is a string rather than an enum so that applications can register additional
     * strategies without modifying the framework.
     *
     * @return the non-null strategy id, e.g. {@code "web-validation"}, {@code "none"}, or
     *     {@code "openapi-contract"}
     */
    String id();

    /**
     * Reports whether this strategy executes bound {@link FileContentVerifier}s as part of its
     * request-validation gate.
     *
     * <p>The default is {@code false}: strategies must opt in explicitly so a mount can warn when
     * verifiers are bound but inactive under the selected strategy.
     *
     * @return {@code true} when this strategy runs bound file-content verifiers
     */
    default boolean runsFileVerifiers() {
        return false;
    }

    /**
     * Produces the validation gate for a single operation, or {@link Optional#empty()} when this
     * strategy installs no gate for the operation.
     *
     * <p>This is invoked once per operation at router-build time (startup), so a strategy that builds
     * heavyweight validators (e.g. compiled JSON-schema validators) should build them here, once, and
     * close over them in the returned handler — not per request.
     *
     * @param op      the operation descriptor whose parameters and body are validated
     * @param schemas the synthesized parameter and body schemas for the operation
     * @return the validation gate handler, or {@link Optional#empty()} when no gate is installed
     */
    Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas);

    /**
     * Notifies the strategy, at router-build time, of the mount metadata for the mount this strategy
     * instance is being used for. A mount calls this once per mount, before any {@link #gateFor} call
     * for that mount's operations, so a strategy whose validation is driven by a <em>per-mount</em>
     * OpenAPI contract can verify that every mount it serves agrees on a single contract path — and
     * fail-fast at startup otherwise, rather than silently validating one mount's operations against a
     * different mount's contract.
     *
     * <p>The mount metadata carries all identifying information for the mount: its stable id, the path
     * prefix where it is mounted, the classpath location of the associated OpenAPI spec (accessible via
     * {@link MountMeta#openapiPath()}), and the set of registered resource types. A strategy that only
     * needs the OpenAPI path reads {@code mountMeta.openapiPath()}; a future per-mount-aware strategy
     * has the full context available without a further SPI change.
     *
     * <p>The default implementation is a no-op: strategies whose validation does not depend on an
     * OpenAPI contract path (e.g. {@code web-validation}, {@code none}) ignore the mount metadata
     * entirely.
     *
     * @param mountMeta the metadata for the mount being bound; {@link MountMeta#openapiPath()} is the
     *                  classpath location of the mount's OpenAPI spec (may be {@code null} for a
     *                  non-JAX-RS mount); strategies that ignore the contract path disregard it
     * @throws dev.vertique.rest.core.RestConfigurationException when this strategy resolves a per-mount
     *     contract and the {@code openapiPath} carried by {@code mountMeta} diverges from the contract
     *     path it is bound to, so that no operation is ever validated against a different mount's contract
     *
     * <p>Exceptions thrown by this callback propagate and are fatal to the enclosing operation;
     * processing does not continue.
     */
    default void bindToMount(MountMeta mountMeta) {
        // No-op by default: contract-path-agnostic strategies (web-validation, none) ignore mount metadata.
    }
}
