// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.rest;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.management.ManagementVerticle;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.starter.rest.RestApplicationModule;
import jakarta.inject.Singleton;

/**
 * Real {@code @VertiqueApp} application component naming only {@link RestApplicationModule}.
 *
 * <p>This fixture is the load-bearing proof of the REST starter's composition contract: the
 * component names no REST, validation, security, or management module directly, yet Dagger must
 * resolve the complete REST application graph from the aggregate alone.
 *
 * <p>The provision methods below request one representative binding per composed concern so a
 * missing member of the aggregate fails at annotation-processing time rather than at first request:
 * {@link HttpVerticle} for JAX-RS routing, {@link OperationSchemaSource} for request validation,
 * {@link SecurityPolicyValidator} for security-policy enforcement, and {@link ManagementVerticle}
 * for the management surface. Requesting them builds the objects only — no server is bound.
 */
@VertiqueApp
@Singleton
@Component(modules = {RestApplicationModule.class})
public interface RestStarterConsumer extends VertiqueApplicationComponent {

    /**
     * Returns the routing host verticle composed from the JAX-RS routing runtime.
     *
     * <p>Representative binding for {@code dev.vertique.rest.jaxrs.RestModule}: constructing it
     * resolves the router mounts, middlewares, and router customizers the REST runtime declares.
     *
     * @return the HTTP verticle; never {@code null}
     */
    HttpVerticle httpVerticle();

    /**
     * Returns the operation schema source backing the {@code web-validation} request-validation
     * strategy.
     *
     * <p>Representative binding for {@code dev.vertique.rest.validation.RestValidationModule}: the
     * binding exists only when the validation module is a member of the aggregate.
     *
     * @return the operation schema source; never {@code null}
     */
    OperationSchemaSource operationSchemaSource();

    /**
     * Returns the security policy validator used to check route security policies at startup.
     *
     * <p>Representative binding for {@code dev.vertique.rest.security.AuthModule}.
     *
     * @return the security policy validator; never {@code null}
     */
    SecurityPolicyValidator securityPolicyValidator();

    /**
     * Returns the management verticle exposing health and management endpoints.
     *
     * <p>Representative binding for {@code dev.vertique.management.ManagementModule}: constructing
     * it resolves the parsed {@code ManagementConfig}, the health-check sets, and the endpoint
     * contributor set.
     *
     * @return the management verticle; never {@code null}
     */
    ManagementVerticle managementVerticle();
}
