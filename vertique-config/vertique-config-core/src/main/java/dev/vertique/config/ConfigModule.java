// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config;

import dagger.Module;
import dagger.Provides;
import io.vertx.config.ConfigRetriever;
import jakarta.inject.Singleton;

/**
 * Dagger module providing {@link ConfigRetriever} for runtime configuration access.
 *
 * <p>Constructed with a {@link ConfigRetriever} created during the bootstrap phase
 * (via {@link ConfigBootstrap}). Include this module in your application's Dagger
 * component to make the retriever available for injection.
 *
 * <p>Example usage in an application component:
 * <pre>{@code
 * @Component(modules = {VertxModule.class, ConfigModule.class, ...})
 * interface AppComponent {
 *     // ...
 * }
 * }</pre>
 *
 * @see ConfigBootstrap
 * @deprecated Launcher-mode applications omit this module entirely — nothing in the framework
 *     consumes {@link ConfigRetriever} at the Dagger level in the launcher path. This module
 *     is kept for the legacy {@code MainVerticle} pattern and a future config-change-listener
 *     hybrid. New applications launched via {@link dev.vertique.launcher.VertiqueApplication}
 *     should not include this module in their component.
 */
@Deprecated
@Module
public class ConfigModule {

    private final ConfigRetriever retriever;

    /**
     * Creates a new config module with the given retriever.
     *
     * @param retriever the {@link ConfigRetriever} created during bootstrap
     */
    public ConfigModule(ConfigRetriever retriever) {
        this.retriever = retriever;
    }

    @Provides
    @Singleton
    ConfigRetriever configRetriever() {
        return retriever;
    }
}
