// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.resilience.Resilience;
import dev.vertique.rest.client.config.RestClientConfig;
import dev.vertique.rest.client.interceptor.RestClientContextCapturer;
import dev.vertique.rest.client.interceptor.RestClientInterceptor;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import io.vertx.core.Vertx;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates declarative HTTP client proxies from JAX-RS-annotated interfaces.
 *
 * <p>Use this factory to obtain typed client instances backed by Vert.x WebClient. The factory
 * is provided as a Dagger singleton via {@link RestClientModule} and pre-seeds every builder it
 * creates with global interceptors, external configuration, and an optional
 * {@link dev.vertique.core.validation.BeanValidator} from the DI container.
 *
 * <p>The preferred API is {@link #builder()}, which returns a {@link RestClientBuilder}
 * pre-seeded with those global values:
 *
 * <pre>{@code
 * @Inject RestClientFactory clientFactory;
 *
 * UserClient userClient = clientFactory.builder()
 *     .baseUrl("http://user-service:8080")
 *     .readTimeout(5, TimeUnit.SECONDS)
 *     .build(UserClient.class);
 * }</pre>
 *
 * <p>The base URL and timeout can also be provided through external configuration under the key
 * {@code restClient.{clientName}.baseUrl} and {@code restClient.{clientName}.readTimeoutMs}. The
 * {@code restClient} section is parsed into a typed {@code name -> RestClientConfig} index at the
 * {@link RestClientModule} provider boundary, and that index is seeded into every builder this
 * factory creates so {@link RestClientBuilder#build(Class)} can resolve each client's config by name.
 */
@Singleton
@Slf4j
public class RestClientFactory {

    private final Vertx vertx;

    @Nullable
    private final Resilience resilience;

    private final Set<RestClientInterceptor> globalInterceptors;
    private final Set<RestClientContextCapturer<?>> globalContextCapturers;
    private final Map<String, RestClientConfig> configIndex;

    @Nullable
    private final dev.vertique.core.validation.BeanValidator beanValidator;

    private final BeanParamAccessorRegistry beanParamAccessorRegistry;

    @Nullable
    private final JsonMapperProfileRegistry jsonMapperProfileRegistry;

    /**
     * The resolved {@code restClient.defaults.jsonProfile} id, or {@code null} when unconfigured.
     * Seeded into every builder for tier-5 precedence in profile resolution.
     */
    @Nullable
    private final String defaultsJsonProfileId;

    /**
     * The parsed global {@link JsonConfig} section. Seeded into every builder for tier-6 precedence
     * (the global {@code json.jsonProfile} default) in profile resolution.
     */
    @Nullable
    private final JsonConfig jsonConfig;

    /**
     * The Dagger-provided {@link ParamConversionResolver} seeded into every builder so that
     * the full application converter set (built-ins + application {@code ParamConverterBinding}s +
     * JAX-RS providers) is available for outbound serialization and build-time validation.
     *
     * <p>{@code null} when a test constructs the factory with the short constructors that omit it
     * (standalone/test path); the builder will construct a built-ins-only resolver in that case.
     */
    @Nullable
    private final ParamConversionResolver paramConversionResolver;

    /**
     * Creates a new factory using the shared {@link BeanParamAccessorRegistry} and an empty
     * capturer set. Intended for direct construction in tests or when the DI graph does not
     * provide the registry. The {@link JsonMapperProfileRegistry} is left {@code null}.
     *
     * @param vertx the Vert.x instance used to create WebClient instances
     * @param globalInterceptors global interceptors applied to all proxies; may be empty
     * @param configIndex the typed {@code name -> RestClientConfig} index seeded into every builder
     * @param beanValidator optional response validator; {@code null} if the validation module is
     *     not present
     */
    RestClientFactory(
            Vertx vertx,
            Set<RestClientInterceptor> globalInterceptors,
            Map<String, RestClientConfig> configIndex,
            @Nullable dev.vertique.core.validation.BeanValidator beanValidator) {
        this(
                vertx,
                globalInterceptors,
                Set.of(),
                configIndex,
                beanValidator,
                BeanParamAccessorRegistry.shared(),
                null,
                null,
                null,
                null);
    }

    /**
     * Creates a new factory. Intended to be called by Dagger via {@link RestClientModule}.
     *
     * @param vertx the Vert.x instance used to create WebClient instances
     * @param globalInterceptors global interceptors applied to all proxies; may be empty
     * @param globalContextCapturers system-owned context capturers applied to all proxies;
     *     may be empty
     * @param configIndex the typed {@code name -> RestClientConfig} index seeded into every builder
     * @param beanValidator optional response validator; {@code null} if the validation module is
     *     not present
     * @param beanParamAccessorRegistry the registry for resolving bean-param field accessors
     * @param jsonMapperProfileRegistry the JSON mapper profile registry seeded into every builder;
     *     {@code null} when the {@code vertique-json} module is not included in the DI graph
     * @param defaultsJsonProfileId the resolved {@code restClient.defaults.jsonProfile} id; seeded
     *     into every builder for tier-5 precedence; {@code null} when unconfigured
     * @param jsonConfig the parsed global {@link JsonConfig}; seeded into every builder for tier-6
     *     precedence; {@code null} when unconfigured
     * @param paramConversionResolver the Dagger-managed conversion resolver seeded into every
     *     builder; {@code null} in tests that use the short constructor
     */
    RestClientFactory(
            Vertx vertx,
            Set<RestClientInterceptor> globalInterceptors,
            Set<RestClientContextCapturer<?>> globalContextCapturers,
            Map<String, RestClientConfig> configIndex,
            @Nullable dev.vertique.core.validation.BeanValidator beanValidator,
            BeanParamAccessorRegistry beanParamAccessorRegistry,
            @Nullable JsonMapperProfileRegistry jsonMapperProfileRegistry,
            @Nullable String defaultsJsonProfileId,
            @Nullable JsonConfig jsonConfig,
            @Nullable ParamConversionResolver paramConversionResolver) {
        this(
                vertx,
                globalInterceptors,
                globalContextCapturers,
                configIndex,
                beanValidator,
                beanParamAccessorRegistry,
                jsonMapperProfileRegistry,
                defaultsJsonProfileId,
                jsonConfig,
                paramConversionResolver,
                null);
    }

    RestClientFactory(
            Vertx vertx,
            Set<RestClientInterceptor> globalInterceptors,
            Set<RestClientContextCapturer<?>> globalContextCapturers,
            Map<String, RestClientConfig> configIndex,
            @Nullable dev.vertique.core.validation.BeanValidator beanValidator,
            BeanParamAccessorRegistry beanParamAccessorRegistry,
            @Nullable JsonMapperProfileRegistry jsonMapperProfileRegistry,
            @Nullable String defaultsJsonProfileId,
            @Nullable JsonConfig jsonConfig,
            @Nullable ParamConversionResolver paramConversionResolver,
            @Nullable Resilience resilience) {
        this.vertx = vertx;
        this.resilience = resilience;
        this.globalInterceptors = globalInterceptors;
        this.globalContextCapturers = globalContextCapturers;
        this.configIndex = configIndex != null ? Map.copyOf(configIndex) : Map.of();
        this.beanValidator = beanValidator;
        this.beanParamAccessorRegistry = beanParamAccessorRegistry;
        this.jsonMapperProfileRegistry = jsonMapperProfileRegistry;
        this.defaultsJsonProfileId = defaultsJsonProfileId;
        this.jsonConfig = jsonConfig;
        this.paramConversionResolver = paramConversionResolver;
    }

    /**
     * Returns a new {@link RestClientBuilder} pre-seeded with global interceptors, external
     * configuration, and optional bean validator from the DI container. This is the preferred way
     * to create REST client proxies when using Dagger injection.
     *
     * <pre>{@code
     * UserClient client = factory.builder()
     *     .baseUrl("http://user-service:8080")
     *     .build(UserClient.class);
     * }</pre>
     *
     * @return a new builder with global interceptors, config, and optional validator pre-registered
     */
    public RestClientBuilder builder() {
        RestClientBuilder b =
                resilience == null ? new RestClientBuilder(vertx) : RestClientBuilder.create(vertx, resilience);
        globalInterceptors.forEach(b::register);
        globalContextCapturers.forEach(b::registerCapturer);
        b.configIndex(configIndex);
        if (beanValidator != null) {
            b.beanValidator(beanValidator);
        }
        b.beanParamAccessorRegistry(beanParamAccessorRegistry);
        b.jsonMapperProfileRegistry(jsonMapperProfileRegistry);
        b.defaultsJsonProfileId(defaultsJsonProfileId);
        b.jsonConfig(jsonConfig);
        if (paramConversionResolver != null) {
            b.paramConversionResolver(paramConversionResolver);
        }
        return b;
    }

    /**
     * Creates a proxy for the given client interface using default configuration.
     *
     * <p>The base URL is taken from the {@link RestClient#value()} annotation on the interface.
     * If neither the annotation nor any configuration provides a base URL, an
     * {@link IllegalArgumentException} is thrown.
     *
     * @param <T> the client interface type
     * @param clientInterface the JAX-RS-annotated interface to proxy
     * @return a proxy instance backed by Vert.x WebClient
     * @throws IllegalArgumentException if no base URL is resolved or the interface cannot be
     *     scanned
     * @deprecated Prefer {@link #builder()} which returns a fluent builder with full control over
     *     timeouts, ObjectMapper, WebClientOptions, and more. This method delegates to
     *     {@code builder().build(clientInterface)} and will be removed in a future release.
     */
    @Deprecated
    public <T> T create(Class<T> clientInterface) {
        return builder().build(clientInterface);
    }
}
