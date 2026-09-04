// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.json.JsonConfig;
import dev.vertique.json.JsonRuntimeModule;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.dagger.ResilienceModule;
import dev.vertique.rest.client.config.RestClientConfig;
import dev.vertique.rest.client.config.RestClientDefaults;
import dev.vertique.rest.client.interceptor.RestClientContextCapturer;
import dev.vertique.rest.client.interceptor.RestClientInterceptor;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.dagger.RestCoreModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dagger module providing REST client infrastructure bindings.
 *
 * <p>Include this module in the application {@code @Component} to enable declarative REST client
 * proxies. Pair with {@link dev.vertique.core.VertxModule} which provides the {@link Vertx}
 * instance and the {@code @VertxConfig JsonObject}.
 *
 * <pre>{@code
 * @Component(modules = {VertxModule.class, RestClientModule.class, AppModule.class})
 * @Singleton
 * interface AppComponent {
 *     RestClientFactory restClientFactory();
 * }
 * }</pre>
 *
 * <p>This module includes {@link JsonRuntimeModule} (FR-JSON-007B), which makes the
 * {@link JsonMapperProfileRegistry} binding available so that every builder produced by
 * {@link RestClientFactory#builder()} can resolve named JSON mapper profiles.
 *
 * <p>Extension points via multibinding and optional binding:
 * <ul>
 *   <li>{@code Set<RestClientInterceptor>} — contribute global interceptors applied to all
 *       clients via {@code @Provides @IntoSet RestClientInterceptor}</li>
 *   <li>{@code Set<RestClientContextCapturer<?>>} — framework-owned; contributes system-level
 *       context capturers (e.g. audit span capture) applied to all clients before any application
 *       interceptor runs</li>
 *   <li>{@code BeanValidator} — optional; present when the {@code validation} module (or any
 *       module providing {@link BeanValidator}) is included in the component. Response objects
 *       will be validated after deserialization if a validator is available.</li>
 *   <li>{@code Set<dev.vertique.core.json.JsonMapperProfile>} — contributed by the application via
 *       {@code @Provides @IntoSet JsonMapperProfile} to register named mapper profiles; the empty
 *       set is the default (only the built-in {@code vertx} profile).</li>
 * </ul>
 */
@Module(includes = {JsonRuntimeModule.class, RestCoreModule.class, ResilienceModule.class})
public abstract class RestClientModule {

    // --- Multibindings ---

    /**
     * Declares the empty-by-default multibinding for global REST client interceptors.
     *
     * <p>Applications contribute interceptors via:
     * <pre>{@code
     * @Provides @IntoSet
     * static RestClientInterceptor myInterceptor(MyInterceptor interceptor) {
     *     return interceptor;
     * }
     * }</pre>
     *
     * @return an empty set; applications contribute elements via {@code @IntoSet}
     */
    @Multibinds
    abstract Set<RestClientInterceptor> globalRestClientInterceptors();

    /**
     * Declares the empty-by-default multibinding for system-owned REST client context capturers.
     *
     * <p>Framework modules (e.g. {@code rest-audit}) contribute capturers via:
     * <pre>{@code
     * @Provides @IntoSet
     * static RestClientContextCapturer<?> auditCapturer(AuditContextCapturer capturer) {
     *     return capturer;
     * }
     * }</pre>
     *
     * <p>Application code should not contribute to this set directly; use
     * {@link RestClientInterceptor} multibinding instead.
     *
     * @return an empty set; framework modules contribute elements via {@code @IntoSet}
     */
    @Multibinds
    abstract Set<RestClientContextCapturer<?>> restClientContextCapturers();

    // --- Optional bindings ---

    /**
     * Declares {@link BeanValidator} as an optional binding. When the {@code validation} module
     * is present in the component, Dagger will inject the real validator; otherwise an empty
     * {@link Optional} is injected and response validation is skipped.
     *
     * @return optional BeanValidator; satisfied by the validation module if present
     */
    @BindsOptionalOf
    abstract BeanValidator optionalBeanValidator();

    // --- Providers ---

    /**
     * Provides the process-wide {@link BeanParamAccessorRegistry} singleton. This binding
     * ensures that DI-constructed clients and standalone-builder clients share the same
     * generated-accessor lookup cache.
     *
     * @return the shared registry instance
     */
    @Provides
    @Singleton
    static BeanParamAccessorRegistry beanParamAccessorRegistry() {
        return BeanParamAccessorRegistry.shared();
    }

    /**
     * Parses the {@code restClient} section of the root config into the typed
     * {@code name -> RestClientConfig} index at the Dagger provider boundary.
     *
     * <p>This is the only place the raw {@link VertxConfig @VertxConfig JsonObject} is read in this
     * module: the section-root-keyed {@code restClient.{name}} shape is parsed and validated here, and
     * module internals ({@link RestClientFactory}, {@link RestClientBuilder}) depend only on the typed
     * index. Malformed config fails fast at startup via {@link RestClientConfig#indexFromConfig}.
     *
     * @param vertxConfig the root application config (boundary-only raw config)
     * @param parser the injected config parser
     * @return the immutable client-name → config index
     */
    @Provides
    @Singleton
    static Map<String, RestClientConfig> restClientConfigIndex(
            @VertxConfig JsonObject vertxConfig, ConfigParser parser) {
        return RestClientConfig.indexFromConfig(vertxConfig, parser);
    }

    /**
     * Provides the singleton {@link RestClientFactory} for creating REST client proxies.
     *
     * @param vertx the Vert.x instance for WebClient creation
     * @param interceptors the global interceptors from multibinding
     * @param contextCapturers the system-owned context capturers from multibinding
     * @param configIndex the typed {@code name -> RestClientConfig} index seeded into every builder
     * @param beanValidator optional bean validator; present when the validation module is included
     * @param registry the bean-param accessor registry for generated-accessor resolution
     * @param jsonMapperProfileRegistry the JSON mapper profile registry (provided by the included
     *     {@link JsonRuntimeModule}); seeded into every builder to enable named profile resolution
     * @param restClientDefaults the parsed {@code restClient.defaults} record; seeded into every
     *     builder for tier-5 precedence; {@link RestClientDefaults#jsonProfile()} is {@code null}
     *     when unconfigured
     * @param jsonConfig the parsed global {@link JsonConfig}; seeded into every builder for tier-6
     *     precedence
     * @param paramConversionResolver the Dagger-managed conversion resolver (from the included
     *     {@link RestCoreModule}); seeded into every builder to enable outbound serialization of
     *     typed path/query/header/cookie parameters via the full application converter set
     * @param resiliencePolicyRegistry the optional named resilience-policy registry; seeded into
     *     every builder when the resilience policy module is installed
     * @return the singleton factory instance
     */
    @Provides
    @Singleton
    static RestClientFactory restClientFactory(
            Vertx vertx,
            Set<RestClientInterceptor> interceptors,
            Set<RestClientContextCapturer<?>> contextCapturers,
            Map<String, RestClientConfig> configIndex,
            Optional<BeanValidator> beanValidator,
            BeanParamAccessorRegistry registry,
            JsonMapperProfileRegistry jsonMapperProfileRegistry,
            RestClientDefaults restClientDefaults,
            JsonConfig jsonConfig,
            ParamConversionResolver paramConversionResolver,
            Resilience resilience,
            Optional<ResiliencePolicyRegistry> resiliencePolicyRegistry) {
        return new RestClientFactory(
                vertx,
                interceptors,
                contextCapturers,
                configIndex,
                beanValidator.orElse(null),
                registry,
                jsonMapperProfileRegistry,
                restClientDefaults.jsonProfile(),
                jsonConfig,
                paramConversionResolver,
                resilience,
                resiliencePolicyRegistry.orElse(ResiliencePolicyRegistry.empty()));
    }

    /**
     * Parses the {@code restClient.defaults} reserved sub-object from the root config into a typed
     * {@link RestClientDefaults} record at the Dagger provider boundary via the canonical
     * {@link ConfigParser} (config rule R10).
     *
     * <p>The reserved key is excluded from the keyed client map by
     * {@link RestClientConfig#indexFromConfig} and parsed separately here, so that a client
     * literally named {@code "defaults"} can never be created.
     *
     * <p>Returns {@link RestClientDefaults#defaults()} when no {@code restClient.defaults}
     * sub-object is present in the config.
     *
     * @param vertxConfig the root application config
     * @param parser the injected config parser
     * @return the parsed {@link RestClientDefaults}; never {@code null}
     */
    @Provides
    @Singleton
    static RestClientDefaults restClientDefaults(@VertxConfig JsonObject vertxConfig, ConfigParser parser) {
        return RestClientConfig.defaultsFromConfig(vertxConfig, parser);
    }

    /**
     * Contributes the {@link RestClientDefaultProfileValidator} as a {@link ComposeValidator} so
     * that the {@code VALIDATE} phase (via {@code ComposeValidationStep}) forces it unconditionally
     * at startup — even when zero rest clients are configured (closing the inert-boundary gap,
     * {@code FR-JSON-050}).
     *
     * @param validator the validator singleton
     * @return the validator, added to the compose-validator set
     */
    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator restClientDefaultProfileValidator(RestClientDefaultProfileValidator validator) {
        return validator;
    }
}
