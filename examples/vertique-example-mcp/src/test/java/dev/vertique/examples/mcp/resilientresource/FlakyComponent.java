// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp.resilientresource;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.json.JsonRuntimeModule;
import dev.vertique.resilience.aop.ResilienceAopModule;
import dev.vertique.resilience.dagger.ResilienceModule;
import dev.vertique.resilience.dagger.ResiliencePoliciesModule;
import dev.vertique.rest.jaxrs.RestModule;
import jakarta.inject.Singleton;

/**
 * Test-only application whose single JAX-RS resource implements an interface that carries the
 * resilience anchor, so the generated router and the generated AOP proxy meet in one graph.
 */
@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            RestModule.class,
            JsonRuntimeModule.class,
            ResilienceModule.class,
            ResiliencePoliciesModule.class,
            ResilienceAopModule.class,
            DeployerModule.class,
            CoreLifecycleStepsModule.class,
            FlakyModule.class,
            GeneratedJaxRsResourcesModule.class,
            GeneratedAopModule.class
        })
interface FlakyComponent extends VertiqueApplicationComponent {

    /**
     * Returns the attempt counter of the flaky resource.
     *
     * @return the counter
     */
    Attempts attempts();
}
