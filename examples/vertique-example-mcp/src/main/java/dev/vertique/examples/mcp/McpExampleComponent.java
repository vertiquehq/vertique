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
import dev.vertique.json.JsonRuntimeModule;
import dev.vertique.mcp.server.McpServerModule;
import dev.vertique.rest.core.dagger.RestCoreModule;
import dev.vertique.rest.security.AuthModule;
import dev.vertique.rest.security.SecurityModule;
import dev.vertique.sanitization.SanitizationModule;
import jakarta.inject.Singleton;

/** Dagger application graph for the public MCP example. */
@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            RestCoreModule.class,
            JsonRuntimeModule.class,
            SecurityModule.class,
            AuthModule.class,
            McpServerModule.class,
            SanitizationModule.class,
            DeployerModule.class,
            CoreLifecycleStepsModule.class,
            McpExampleModule.class,
            GeneratedMcpToolsModule.class
        })
interface McpExampleComponent extends VertiqueApplicationComponent {}
