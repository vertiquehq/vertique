// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.auth.jwt.JwtClaimsValidator;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.Observations;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.SharedDeployment;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.TraceContributors;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.jwt.JWTAuth;
import jakarta.inject.Singleton;

/**
 * The JWT fixtures of the protected document tests, beside {@code JwtAuthModule} (scheme {@code
 * bearerAuth}): the JWT provider of {@link SharedDeployment}, which verifies the tokens {@link
 * SharedDeployment#alice} ({@code [admin]}) and {@link SharedDeployment#bob} ({@code [user]}) mint;
 * the {@link SharedDeployment.BlockedTenantValidator} as the {@link JwtClaimsValidator}; the
 * component's one {@link Observations}; and the probes at priorities 45, 90, 200, and 400 and the
 * application contributor at priority 60, which write each request's trace into it. The
 * configuration must set {@code jwt.validation.issuer} to {@value SharedDeployment#ISSUER}.
 */
@Module
public final class ContractJwtModule {

    private ContractJwtModule() {}

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
}
