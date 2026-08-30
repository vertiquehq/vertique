// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.webhook;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.management.ManagementVerticle;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import jakarta.annotation.Nullable;
import jakarta.inject.Provider;

/**
 * Application-specific Dagger module providing configuration and security stub bindings.
 *
 * <p>This example does not use authentication or authorization. {@code SecurityRuntime} resolves
 * to {@code Optional.empty()} automatically via {@code RestCoreModule}'s
 * {@code @BindsOptionalOf SecurityRuntime} when no security module is on the component;
 * {@link SecurityPolicyValidator} is provided as a {@code null} below since
 * {@code JaxRsRouterMount.Factory} still consumes it as {@code @Nullable}.
 */
@Module
public abstract class AppModule {

    /**
     * Provides a {@code null} {@link SecurityPolicyValidator} since this example does not use auth.
     *
     * @return always {@code null}
     */
    @Provides
    @Nullable
    static SecurityPolicyValidator securityPolicyValidator() {
        return null;
    }

    /**
     * Registers the management verticle for deployment in the {@link LifecyclePhase#INFRA} phase.
     *
     * @param provider Dagger provider creating fresh instances per deployment
     * @return the deployment descriptor
     */
    @Provides
    @IntoSet
    static VerticleDeployment managementVerticle(Provider<ManagementVerticle> provider) {
        return VerticleDeployment.of("management", provider::get, LifecyclePhase.INFRA);
    }

    /**
     * Registers the HTTP verticle for deployment in the {@link LifecyclePhase#EDGE} phase.
     *
     * @param provider Dagger provider creating fresh instances per deployment
     * @return the deployment descriptor
     */
    @Provides
    @IntoSet
    static VerticleDeployment httpVerticle(Provider<HttpVerticle> provider) {
        return VerticleDeployment.of("http", provider::get, LifecyclePhase.EDGE);
    }
}
