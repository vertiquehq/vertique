// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.http.client.config.HttpClientConfig;
import dev.vertique.http.client.exception.HttpClientConfigurationException;
import dev.vertique.http.client.interceptor.HttpClientInterceptor;
import dev.vertique.resilience.dagger.ResilienceModule;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Set;

/**
 * Dagger module of the governed HTTP client.
 *
 * <p>Include it in the application component next to the configuration parser module and a module
 * that provides {@code Vertx} and the {@code @VertxConfig JsonObject}. It provides
 * {@link HttpClientFactory}, the parsed {@code httpClient} index, a startup validator and the shutdown step that closes every
 * factory client. Applications contribute global interceptors with
 * {@code @Provides @IntoSet HttpClientInterceptor}; they are seeded into every client and builder the
 * factory hands out.
 */
@Module(includes = ResilienceModule.class)
public abstract class HttpClientModule {

    private HttpClientModule() {}

    /**
     * Declares the global interceptors, empty by default.
     *
     * @return an empty set; applications contribute elements with {@code @IntoSet}
     */
    @Multibinds
    abstract Set<HttpClientInterceptor> globalHttpClientInterceptors();

    /**
     * Parses and validates the {@code httpClient} section at the module boundary.
     *
     * @param vertxConfig the root application configuration
     * @param parser the configuration parser
     * @return the immutable client-name to configuration index
     * @throws HttpClientConfigurationException when the section is invalid
     */
    @Provides
    @Singleton
    static Map<String, HttpClientConfig> httpClientConfigIndex(
            @VertxConfig JsonObject vertxConfig, ConfigParser parser) {
        return HttpClientConfig.indexFromConfig(vertxConfig, parser);
    }

    /**
     * Contributes the startup validator, which applies every configured client through the builder's setters
     * without building a client, so a value the builder rejects fails startup.
     *
     * @param index the parsed configuration
     * @return the validator
     */
    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator httpClientConfigValidator(Map<String, HttpClientConfig> index) {
        return HttpClientConfigValidator.of(index);
    }

    /**
     * Contributes the shutdown step that closes every client the factory handed out.
     *
     * @param factory the factory
     * @return the shutdown step
     */
    @Provides
    @Singleton
    @IntoSet
    static ApplicationShutdownStep httpClientShutdownStep(HttpClientFactory factory) {
        return new HttpClientShutdownStep(factory);
    }

    /** Closes the factory's clients when the application stops. */
    private static final class HttpClientShutdownStep implements ApplicationShutdownStep {

        private final HttpClientFactory factory;

        HttpClientShutdownStep(HttpClientFactory factory) {
            this.factory = factory;
        }

        @Override
        public LifecyclePhase phase() {
            return LifecyclePhase.INFRA;
        }

        @Override
        public Future<Void> stop() {
            return factory.close();
        }
    }
}
