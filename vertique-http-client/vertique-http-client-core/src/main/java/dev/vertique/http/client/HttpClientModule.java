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
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.dagger.ResilienceModule;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dagger module of the governed HTTP client.
 *
 * <p>Include it in the application component next to the configuration parser module and a module
 * that provides {@code Vertx} and the {@code @VertxConfig JsonObject}. It provides
 * {@link HttpClientFactory}, the parsed {@code httpClient} index, a startup validator and the shutdown
 * step that closes every factory client. Applications contribute global interceptors with
 * {@code @Provides @IntoSet HttpClientInterceptor}; they are seeded into every client and builder the
 * factory hands out. The {@code resilience.policies} registry is used when the graph provides it.
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
     * Contributes the startup validator, which fails startup for a client whose {@code resiliencePolicy}
     * names a tier that {@code resilience.policies} does not define.
     *
     * @param index the parsed configuration
     * @param policies the optional policy registry
     * @return the validator
     */
    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator httpClientConfigValidator(
            Map<String, HttpClientConfig> index, Optional<ResiliencePolicyRegistry> policies) {
        return new HttpClientConfigValidator(index, policies.orElseGet(ResiliencePolicyRegistry::empty));
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

    /** Fails construction, and so startup, when a configured resilience policy is not defined. */
    private static final class HttpClientConfigValidator implements ComposeValidator {

        HttpClientConfigValidator(Map<String, HttpClientConfig> index, ResiliencePolicyRegistry policies) {
            for (HttpClientConfig config : index.values()) {
                String tier = config.resiliencePolicy();
                if (tier == null) {
                    continue;
                }
                try {
                    policies.require(tier);
                } catch (RuntimeException undefined) {
                    throw new HttpClientConfigurationException("httpClient." + config.name()
                            + ".resiliencePolicy names a resilience policy that is not defined");
                }
            }
        }
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
