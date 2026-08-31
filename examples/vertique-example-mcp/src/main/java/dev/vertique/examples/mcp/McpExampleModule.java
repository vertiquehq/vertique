// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.mcp.server.McpServerConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/** Application-owned MCP configuration, profile, and HTTP deployment bindings. */
@Module
public final class McpExampleModule {
    private McpExampleModule() {}

    @Provides
    @Singleton
    static McpServerConfig mcpServerConfig(@VertxConfig JsonObject root, ConfigParser parser) {
        return parser.parse(root.getJsonObject("mcp", new JsonObject()), McpServerConfig.class);
    }

    @Provides
    @IntoSet
    static JsonMapperProfile exampleJsonProfile() {
        return ExampleJsonProfile.create();
    }

    @Provides
    @IntoSet
    static VerticleDeployment httpVerticle(Provider<HttpVerticle> provider) {
        return VerticleDeployment.of("http", provider::get, LifecyclePhase.EDGE);
    }
}
