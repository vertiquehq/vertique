// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp.resilientresource;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import jakarta.annotation.Nullable;
import jakarta.inject.Provider;

/** Deploys the HTTP verticle of the flaky resource fixture. */
@Module
public final class FlakyModule {

    private FlakyModule() {}

    /**
     * Provides no security policy validator, since this fixture does not use authentication.
     *
     * @return always {@code null}
     */
    @Provides
    @Nullable
    static SecurityPolicyValidator securityPolicyValidator() {
        return null;
    }

    /**
     * Registers the HTTP verticle for deployment in the edge phase.
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
