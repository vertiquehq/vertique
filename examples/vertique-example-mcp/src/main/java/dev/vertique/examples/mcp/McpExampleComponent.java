// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.examples.mcp.resource.GeneratedJaxRsResourcesModule;
import dev.vertique.json.JsonRuntimeModule;
import dev.vertique.mcp.server.McpServerModule;
import dev.vertique.ratelimit.aop.RateLimitAopModule;
import dev.vertique.ratelimit.dagger.RateLimitCoreModule;
import dev.vertique.ratelimit.spi.RateLimitObserver;
import dev.vertique.resilience.aop.ResilienceAopModule;
import dev.vertique.resilience.dagger.ResilienceModule;
import dev.vertique.resilience.dagger.ResiliencePoliciesModule;
import dev.vertique.rest.auth.jwt.JwtAuthModule;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.ratelimit.RestRateLimitModule;
import dev.vertique.sanitization.SanitizationModule;
import jakarta.inject.Singleton;
import java.util.Set;

/** Dagger application graph for the public MCP example. */
@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            RestModule.class,
            JsonRuntimeModule.class,
            JwtAuthModule.class,
            RateLimitCoreModule.class,
            RateLimitAopModule.class,
            ResilienceModule.class,
            ResiliencePoliciesModule.class,
            ResilienceAopModule.class,
            RestRateLimitModule.class,
            McpServerModule.class,
            SanitizationModule.class,
            DeployerModule.class,
            CoreLifecycleStepsModule.class,
            McpExampleModule.class,
            GeneratedMcpToolsModule.class,
            GeneratedJaxRsResourcesModule.class,
            GeneratedAopModule.class
        })
interface McpExampleComponent extends VertiqueApplicationComponent {
    Set<RateLimitObserver> rateLimitObservers();

    RateLimitObservationRecorder rateLimitObservationRecorder();

    McpResilienceObservationRecorder mcpResilienceObservationRecorder();

    McpResilienceProbe mcpResilienceProbe();
}
