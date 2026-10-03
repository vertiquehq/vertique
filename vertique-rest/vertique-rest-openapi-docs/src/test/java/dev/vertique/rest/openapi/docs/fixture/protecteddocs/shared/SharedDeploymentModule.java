// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.auth.jwt.JwtClaimsValidator;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.core.events.HttpRequestCompletedListener;
import dev.vertique.rest.core.events.RestRequestCompletedListener;
import dev.vertique.rest.core.interceptor.ErrorInterceptor;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.security.events.SecurityEventObserver;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.jwt.JWTAuth;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * The shared deployment's fixtures, beside {@code RestModule}, {@code RestValidationModule}, {@code
 * JwtAuthModule} (scheme {@code bearerAuth}), and the documentation module.
 *
 * <ul>
 *   <li>Registers {@link OpenCatalogApi} and {@link GuardedManagementApi} exactly as the generated
 *       registration module does ({@code GeneratedRestApplicationRegistration.of(declaringType, name,
 *       path, resources, false, "", true)}), since the annotation processor does not run on framework
 *       test sources, and contributes their resources as manual {@code @JaxRsResources} instances.
 *   <li>Provides the JWT provider of {@link SharedDeployment} and the {@link
 *       SharedDeployment.BlockedTenantValidator} as the {@link JwtClaimsValidator}.
 *   <li>Contributes the probes at priorities 45, 90, 200, and 400 and the application contributor at
 *       priority 60.
 *   <li>Contributes the security-event observer, the REST and HTTP completion listeners, the barrier
 *       middleware, the later mount at {@code /apidocs/*}, and the counting error interceptor, all
 *       writing into the component's one {@link Observations}.
 * </ul>
 */
@Module
public final class SharedDeploymentModule {

    private SharedDeploymentModule() {}

    /**
     * Provides the component's observation hub.
     *
     * @return a new hub, one per component
     */
    @Provides
    @Singleton
    static Observations observations() {
        return new Observations();
    }

    /**
     * Registers {@link OpenCatalogApi}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration openCatalogRegistration() {
        return GeneratedRestApplicationRegistration.of(
                OpenCatalogApi.class,
                OpenCatalogApi.NAME,
                OpenCatalogApi.PATH,
                List.of(CatalogResource.class),
                false,
                "",
                true);
    }

    /**
     * Registers {@link GuardedManagementApi}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration guardedManagementRegistration() {
        return GeneratedRestApplicationRegistration.of(
                GuardedManagementApi.class,
                GuardedManagementApi.NAME,
                GuardedManagementApi.PATH,
                List.of(TwinResource.class),
                false,
                "",
                true);
    }

    /**
     * Contributes {@link CatalogResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object catalogResource() {
        return new CatalogResource();
    }

    /**
     * Contributes {@link TwinResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object twinResource() {
        return new TwinResource();
    }

    /**
     * Provides the JWT provider the JWT authentication module's handler verifies tokens with.
     *
     * @param vertx the Vert.x instance
     * @return the provider
     */
    @Provides
    @Singleton
    static JWTAuth jwtAuth(Vertx vertx) {
        return SharedDeployment.jwtAuth(vertx);
    }

    /**
     * Provides the claims validator that rejects the blocked tenant.
     *
     * @return the validator
     */
    @Provides
    static JwtClaimsValidator claimsValidator() {
        return new SharedDeployment.BlockedTenantValidator();
    }

    /**
     * Contributes the probe at priority 45.
     *
     * @param observations the component's observation hub
     * @return the probe
     */
    @Provides
    @IntoSet
    static OperationHandlerContributor probe45(Observations observations) {
        return new TraceContributors.Probe(45, observations);
    }

    /**
     * Contributes the probe at priority 90.
     *
     * @param observations the component's observation hub
     * @return the probe
     */
    @Provides
    @IntoSet
    static OperationHandlerContributor probe90(Observations observations) {
        return new TraceContributors.Probe(90, observations);
    }

    /**
     * Contributes the probe at priority 200.
     *
     * @param observations the component's observation hub
     * @return the probe
     */
    @Provides
    @IntoSet
    static OperationHandlerContributor probe200(Observations observations) {
        return new TraceContributors.Probe(200, observations);
    }

    /**
     * Contributes the probe at priority 400, the last fixture contributor of every chain.
     *
     * @param observations the component's observation hub
     * @return the probe
     */
    @Provides
    @IntoSet
    static OperationHandlerContributor probe400(Observations observations) {
        return new TraceContributors.Probe(400, observations);
    }

    /**
     * Contributes the application contributor at priority 60.
     *
     * @param observations the component's observation hub
     * @return the contributor
     */
    @Provides
    @IntoSet
    static OperationHandlerContributor applicationRejecter(Observations observations) {
        return new TraceContributors.ApplicationRejecter(observations);
    }

    /**
     * Contributes the security-event observer.
     *
     * @param observations the component's observation hub
     * @return the observer
     */
    @Provides
    @IntoSet
    static SecurityEventObserver securityEvents(Observations observations) {
        return new Recorders.SecurityEvents(observations);
    }

    /**
     * Contributes the REST completion listener.
     *
     * @param observations the component's observation hub
     * @return the listener
     */
    @Provides
    @IntoSet
    static RestRequestCompletedListener restCompletions(Observations observations) {
        return new Recorders.RestCompletions(observations);
    }

    /**
     * Contributes the HTTP completion listener.
     *
     * @param observations the component's observation hub
     * @return the listener
     */
    @Provides
    @IntoSet
    static HttpRequestCompletedListener httpCompletions(Observations observations) {
        return new Recorders.HttpCompletions(observations);
    }

    /**
     * Contributes the barrier middleware.
     *
     * @param observations the component's observation hub
     * @return the middleware
     */
    @Provides
    @IntoSet
    static Middleware requestBarrier(Observations observations) {
        return new Recorders.RequestBarrier(observations);
    }

    /**
     * Contributes the counting error interceptor.
     *
     * @param observations the component's observation hub
     * @return the interceptor
     */
    @Provides
    @IntoSet
    static ErrorInterceptor countingErrorInterceptor(Observations observations) {
        return new Recorders.CountingErrorInterceptor(observations);
    }

    /**
     * Contributes the later plain mount at the documentation prefix.
     *
     * @param observations the component's observation hub
     * @return the mount
     */
    @Provides
    @IntoSet
    static RouterMount laterDocsPrefixMount(Observations observations) {
        return new LaterDocsPrefixMount(observations);
    }
}
