// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.failure.FailureTranslator;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.resilience.BackoffStrategy;
import dev.vertique.core.resilience.CircuitBreaker;
import dev.vertique.core.util.GeneratedCompanions;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.json.JsonConfig;
import dev.vertique.rest.client.config.RestClientCircuitBreakerConfig;
import dev.vertique.rest.client.config.RestClientConfig;
import dev.vertique.rest.client.config.RestClientPoolConfig;
import dev.vertique.rest.client.config.RestClientRetryConfig;
import dev.vertique.rest.client.convert.ClientConversionContexts;
import dev.vertique.rest.client.exception.RestClientConfigurationException;
import dev.vertique.rest.client.exception.RestClientException;
import dev.vertique.rest.client.interceptor.RestClientContextCapturer;
import dev.vertique.rest.client.interceptor.RestClientInterceptor;
import dev.vertique.rest.client.meta.ClientInterfaceScanner;
import dev.vertique.rest.client.meta.ClientMethodMeta;
import dev.vertique.rest.client.meta.ClientParamMeta;
import dev.vertique.rest.core.convert.ConversionContext;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.convert.ParamConverterBinding;
import dev.vertique.rest.core.convert.ParamConverterRegistry;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Expectation;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpResponseHead;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.ext.ParamConverterProvider;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Fluent builder for creating declarative REST client proxies.
 *
 * <p>This is the primary API for constructing REST client proxies. Obtain an instance via
 * {@link RestClientBuilder#create(Vertx)} for standalone use, or via {@link RestClientFactory#builder()}
 * when using Dagger (which pre-seeds global interceptors and configuration):
 *
 * <pre>{@code
 * // Standalone
 * UserClient client = RestClientBuilder.create(vertx)
 *     .baseUrl("http://user-service:8080")
 *     .readTimeout(5, TimeUnit.SECONDS)
 *     .objectMapper(customMapper)
 *     .register(new LoggingInterceptor())
 *     .build(UserClient.class);
 *
 * // Dagger-injected
 * UserClient client = factory.builder()
 *     .baseUrl("http://user-service:8080")
 *     .build(UserClient.class);
 * }</pre>
 *
 * <p>Each call to {@link #build(Class)} creates a fresh {@link WebClient} instance. Pool sharing
 * is explicit via passing the same {@link WebClientOptions} instance to multiple builders.
 *
 * <p>Circuit breaker priority (highest to lowest):
 * <ol>
 *   <li>External configuration (via {@link #config(RestClientConfig)})</li>
 *   <li>{@link CircuitBreaker} annotation on the interface</li>
 *   <li>Builder-level {@link #circuitBreaker(CircuitBreakerOptions)}</li>
 * </ol>
 * Method-level {@link CircuitBreaker} annotations are resolved per-invocation in the proxy.
 *
 * <p>WebClientOptions config priority (highest to lowest):
 * <ol>
 *   <li>External config {@code restClient.{name}.webClient.*} — merged on top of the builder-level options via
 *       Vert.x's built-in {@link io.vertx.ext.web.client.WebClientOptions#WebClientOptions(io.vertx.core.json.JsonObject)}
 *       constructor; individual fields can be selectively overridden without replacing the entire baseline</li>
 *   <li>Builder-level {@link #webClientOptions(WebClientOptions)}</li>
 * </ol>
 *
 * <p>Exception translation priority (last registered wins for a given type):
 * <ol>
 *   <li>Defaults pre-registered in {@link DefaultRestClientExceptionMapper}</li>
 *   <li>Per-client translators added via {@link #onFailure(Class, FailureTranslator)}</li>
 * </ol>
 */
@Slf4j
public final class RestClientBuilder {

    // --- Static meta cache shared across all builder instances ---
    private static final ConcurrentHashMap<Class<?>, Map<Method, ClientMethodMeta>> META_CACHE =
            new ConcurrentHashMap<>();

    // --- Builder state ---

    private final Vertx vertx;

    @Nullable
    private String baseUrl;

    private long readTimeoutMs = 30_000L;

    @Nullable
    private ObjectMapper objectMapper;

    @Nullable
    private BeanValidator beanValidator;

    @Nullable
    private WebClientOptions webClientOptions;

    @Nullable
    private PoolOptions poolOptions;

    private final Map<String, String> defaultHeaders = new LinkedHashMap<>();
    private final List<RestClientInterceptor> interceptors = new ArrayList<>();
    private final List<RestClientContextCapturer<?>> contextCapturers = new ArrayList<>();
    private RestClientExceptionMapper exceptionMapper = new DefaultRestClientExceptionMapper();

    @Nullable
    private Expectation<HttpResponseHead> defaultExpectation;

    @Nullable
    private CircuitBreakerOptions circuitBreakerOptions;

    @Nullable
    private RestClientConfig config;

    private Map<String, RestClientConfig> configIndex = Map.of();

    private RestClientRetryPolicy retryPolicy = new DefaultRestClientRetryPolicy();
    private BackoffStrategy backoffStrategy = BackoffStrategy.exponential(500, 2.0, 30_000);
    private BeanParamAccessorRegistry beanParamAccessorRegistry = BeanParamAccessorRegistry.shared();

    @Nullable
    private JsonProfileId jsonProfile;

    @Nullable
    private JsonMapperProfileRegistry jsonMapperProfileRegistry;

    /**
     * The resolved {@code restClient.defaults.jsonProfile} id, or {@code null} when absent.
     * Seeded by {@link RestClientFactory#builder()} from the Dagger-provided
     * {@link dev.vertique.rest.client.config.RestClientDefaults} binding. Tier 5 in the precedence
     * chain.
     */
    @Nullable
    private String defaultsJsonProfileId;

    /**
     * The parsed global {@link JsonConfig} section, providing the {@code json.jsonProfile} default.
     * Injected by {@link RestClientFactory#builder()}. Tier 6 in the precedence chain.
     */
    @Nullable
    private JsonConfig jsonConfig;

    /**
     * An explicit {@link ParamConversionResolver} override for this client. When non-null, it is
     * used as the effective resolver directly (bypassing the accumulator lists). Set by
     * {@link RestClientFactory#builder()} from the Dagger-provided singleton, which includes all
     * application-registered {@link ParamConverterBinding}s and {@link ParamConverterProvider}s.
     * Also available for standalone use via {@link #paramConversionResolver(ParamConversionResolver)}.
     */
    @Nullable
    private ParamConversionResolver conversionResolverOverride;

    /**
     * Accumulates {@link ParamConverterBinding}s contributed via
     * {@link #paramConverterBinding(ParamConverterBinding)} for clients built without a
     * Dagger-provided resolver. Insertion order is preserved (iteration order matches registration
     * order) using a {@link LinkedHashSet} at build time.
     */
    private final List<ParamConverterBinding<?>> converterBindings = new ArrayList<>();

    /**
     * Accumulates JAX-RS {@link ParamConverterProvider}s contributed via
     * {@link #paramConverterProvider(ParamConverterProvider)} for clients built without a
     * Dagger-provided resolver. Insertion order is preserved at build time.
     */
    private final List<ParamConverterProvider> converterProviders = new ArrayList<>();

    /**
     * Creates a new builder for the given Vert.x instance. Use {@link #create(Vertx)} as a
     * more readable static factory alternative.
     *
     * <p>For Dagger-managed applications, prefer {@link RestClientFactory#builder()} which
     * pre-seeds global interceptors and configuration from the DI container.
     *
     * @param vertx the Vert.x instance used to create the underlying WebClient
     */
    public RestClientBuilder(Vertx vertx) {
        this.vertx = vertx;
    }

    // --- Static factory ---

    /**
     * Creates a new builder for the given Vert.x instance. Equivalent to
     * {@code new RestClientBuilder(vertx)} but more readable in fluent chains.
     *
     * <p>For Dagger-managed applications, prefer {@link RestClientFactory#builder()} which
     * pre-seeds global interceptors and configuration from the DI container.
     *
     * @param vertx the Vert.x instance used to create the underlying WebClient
     * @return a new builder instance
     */
    public static RestClientBuilder create(Vertx vertx) {
        return new RestClientBuilder(vertx);
    }

    // --- Fluent setters ---

    /**
     * Sets the base URL for all requests made through this client (e.g.
     * {@code http://user-service:8080}). Overrides any default URL from the
     * {@link RestClient#value()} annotation.
     *
     * @param baseUrl the base URL, must not be blank
     * @return this builder
     */
    public RestClientBuilder baseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }

    /**
     * Sets the per-request read timeout, converting the given duration to milliseconds.
     * Overrides the default of 30 seconds.
     *
     * @param timeout the timeout duration; must be positive
     * @param unit the time unit for {@code timeout}
     * @return this builder
     */
    public RestClientBuilder readTimeout(long timeout, TimeUnit unit) {
        this.readTimeoutMs = unit.toMillis(timeout);
        return this;
    }

    /**
     * Sets a per-client {@link ObjectMapper} for JSON serialization and deserialization. When
     * not set, the shared {@link DatabindCodec#mapper()} is used.
     *
     * @param mapper the ObjectMapper to use
     * @return this builder
     */
    public RestClientBuilder objectMapper(ObjectMapper mapper) {
        this.objectMapper = mapper;
        return this;
    }

    /**
     * Sets a {@link BeanValidator} to validate response objects after deserialization. When not
     * set, no validation is performed.
     *
     * @param validator the BeanValidator to use
     * @return this builder
     */
    public RestClientBuilder beanValidator(BeanValidator validator) {
        this.beanValidator = validator;
        return this;
    }

    /**
     * Sets the Vert.x {@link WebClientOptions} for connection configuration (TLS, connect
     * timeout, keep-alive, etc.). A fresh {@link WebClient} is created per {@link #build(Class)}
     * call using these options.
     *
     * @param options the WebClientOptions to use
     * @return this builder
     */
    public RestClientBuilder webClientOptions(WebClientOptions options) {
        this.webClientOptions = options;
        return this;
    }

    /**
     * Sets the {@link PoolOptions} for HTTP connection pool sizing. Applied on top of the
     * {@link WebClientOptions} when building the WebClient.
     *
     * @param options the PoolOptions to use
     * @return this builder
     */
    public RestClientBuilder poolOptions(PoolOptions options) {
        this.poolOptions = options;
        return this;
    }

    /**
     * Adds a default header to every request. Multiple calls with the same name overwrite the
     * previous value.
     *
     * @param name the header name
     * @param value the header value
     * @return this builder
     */
    public RestClientBuilder defaultHeader(String name, String value) {
        this.defaultHeaders.put(name, value);
        return this;
    }

    /**
     * Registers an interceptor. Interceptors are applied in ascending
     * {@link RestClientInterceptor#priority()} order.
     *
     * @param interceptor the interceptor to register
     * @return this builder
     */
    public RestClientBuilder register(RestClientInterceptor interceptor) {
        this.interceptors.add(interceptor);
        return this;
    }

    /**
     * Registers a system-owned {@link RestClientContextCapturer}. Capturers are invoked by the dispatcher
     * once at request entry (before application interceptors) and observe each physical attempt with the
     * captured value — applied in {@link dev.vertique.core.extension.OrderedExtension} order.
     *
     * @param capturer the context capturer to register
     * @return this builder
     */
    public RestClientBuilder registerCapturer(RestClientContextCapturer<?> capturer) {
        this.contextCapturers.add(capturer);
        return this;
    }

    /**
     * Replaces the exception mapper used to translate transport and HTTP exceptions. The given
     * mapper takes full responsibility for all exception translation; any translators previously
     * registered via {@link #onFailure(Class, FailureTranslator)} are discarded.
     *
     * <p>Use this method when you need a completely custom translation strategy. For adding
     * individual translators on top of the defaults, prefer {@link #onFailure(Class, FailureTranslator)}.
     *
     * @param mapper the exception mapper to use; must not be {@code null}
     * @return this builder
     */
    public RestClientBuilder exceptionMapper(RestClientExceptionMapper mapper) {
        this.exceptionMapper = mapper;
        return this;
    }

    /**
     * Registers a failure translator for the given exception type on the current
     * {@link RestClientExceptionMapper}. When the proxy encounters a {@link Throwable} of this type
     * (or a subtype), the translator is invoked to convert it before the failure is propagated.
     *
     * <p>Translators added here override any existing translation for the same type (including
     * defaults from {@link DefaultRestClientExceptionMapper}).
     *
     * @param <T> the exception type
     * @param type the exception class to translate
     * @param translator the translator function
     * @return this builder
     */
    public <T extends Throwable> RestClientBuilder onFailure(Class<T> type, FailureTranslator<T> translator) {
        this.exceptionMapper.on(type, translator);
        return this;
    }

    /**
     * Sets the default response {@link Expectation} applied to all methods that do not have a
     * method-level {@link ExpectedStatus} annotation. Commonly used to require 2xx status codes
     * or a specific {@code Content-Type}.
     *
     * @param expectation the default expectation
     * @return this builder
     */
    public RestClientBuilder expecting(Expectation<HttpResponseHead> expectation) {
        this.defaultExpectation = expectation;
        return this;
    }

    /**
     * Sets the circuit breaker options as the builder-level baseline. These options are overridden
     * by interface-level {@link CircuitBreaker} annotations and external configuration.
     *
     * @param options the circuit breaker options
     * @return this builder
     */
    public RestClientBuilder circuitBreaker(CircuitBreakerOptions options) {
        this.circuitBreakerOptions = options;
        return this;
    }

    /**
     * Sets the builder-level {@link RestClientRetryPolicy} used when a method's {@code @Retry}
     * annotation has no {@code retryOn} filter. Defaults to {@link DefaultRestClientRetryPolicy}.
     *
     * @param policy the retry policy; must not be {@code null}
     * @return this builder
     */
    public RestClientBuilder retryPolicy(RestClientRetryPolicy policy) {
        this.retryPolicy = policy;
        return this;
    }

    /**
     * Sets the builder-level {@link BackoffStrategy} used when a method's {@code @Retry}
     * annotation uses {@link BackoffStrategy.Default}. Defaults to exponential backoff
     * ({@code 500ms, ×2, max 30s}).
     *
     * @param strategy the backoff strategy; must not be {@code null}
     * @return this builder
     */
    public RestClientBuilder backoffStrategy(BackoffStrategy strategy) {
        this.backoffStrategy = strategy;
        return this;
    }

    /**
     * Sets a custom {@link BeanParamAccessorRegistry} for bean-param field resolution.
     * Defaults to {@link BeanParamAccessorRegistry#shared()} so the process-wide generated-
     * accessor cache is shared across all clients.
     *
     * <p>Override only in tests that need a fresh cache or a custom fallback.
     *
     * @param registry the registry to use; must not be {@code null}
     * @return this builder
     */
    public RestClientBuilder beanParamAccessorRegistry(BeanParamAccessorRegistry registry) {
        this.beanParamAccessorRegistry = registry;
        return this;
    }

    /**
     * Selects a named JSON mapper profile for this client by its {@link JsonProfileId}.
     *
     * <p>The profile is resolved at {@link #build(Class)} time from the injected
     * {@link JsonMapperProfileRegistry}. An explicit
     * {@link #objectMapper(com.fasterxml.jackson.databind.ObjectMapper)} still wins over any profile
     * selection (FR-JSON-027).
     *
     * <p>Precedence at build time (highest to lowest): explicit {@code objectMapper} &gt; config
     * {@code restClient.&lt;name&gt;.jsonProfile} &gt; this builder-level profile &gt;
     * interface-level {@link JsonProfile} annotation &gt; {@code restClient.defaults.jsonProfile}
     * boundary default &gt; global {@code json.jsonProfile} &gt; {@code vertx} default.
     *
     * <p>Passing {@code null} clears any builder-level selection; the effective profile then falls
     * through to the config {@code jsonProfile}, the interface-level {@link JsonProfile} annotation,
     * the {@code restClient.defaults.jsonProfile} boundary default, the global
     * {@code json.jsonProfile}, and finally the {@code vertx} default (per the precedence above).
     *
     * @param profileId the profile to select, or {@code null} to clear the builder-level selection
     * @return this builder
     */
    public RestClientBuilder jsonProfile(JsonProfileId profileId) {
        this.jsonProfile = profileId;
        return this;
    }

    /**
     * Returns the builder-level {@link JsonProfileId}, or {@code null} if none has been set.
     * Package-private for test inspection.
     *
     * @return the current builder-level profile id, or {@code null}
     */
    @Nullable
    JsonProfileId jsonProfileId() {
        return jsonProfile;
    }

    /**
     * Seeds the {@link JsonMapperProfileRegistry} so {@link #build(Class)} can resolve a named
     * profile id to an {@link com.fasterxml.jackson.databind.ObjectMapper}.
     *
     * <p>Called by {@link RestClientFactory#builder()} alongside the other seed methods
     * ({@link #configIndex(Map)}, etc.). A standalone {@code new RestClientBuilder(vertx)} leaves
     * this {@code null}; the precedence-resolution logic (slice 3.2) will handle the null case.
     *
     * @param registry the registry to use; {@code null} is accepted (standalone builder case)
     * @return this builder
     */
    RestClientBuilder jsonMapperProfileRegistry(@Nullable JsonMapperProfileRegistry registry) {
        this.jsonMapperProfileRegistry = registry;
        return this;
    }

    /**
     * Seeds the resolved {@code restClient.defaults.jsonProfile} id (tier 5 in the precedence chain).
     * Called by {@link RestClientFactory#builder()} after parsing the reserved {@code defaults}
     * sub-object from the root config. A {@code null}/blank id means no boundary-level default is
     * configured; resolution falls through to the global {@code json.jsonProfile} tier or the
     * {@code vertx} floor.
     *
     * @param defaultsJsonProfileId the resolved defaults profile id, or {@code null}
     * @return this builder
     */
    RestClientBuilder defaultsJsonProfileId(@Nullable String defaultsJsonProfileId) {
        this.defaultsJsonProfileId = defaultsJsonProfileId;
        return this;
    }

    /**
     * Seeds the parsed {@link JsonConfig} (tier 6 in the precedence chain, the global
     * {@code json.jsonProfile} default). Called by {@link RestClientFactory#builder()} from the
     * Dagger-provided {@link JsonConfig} binding. A {@code null} config or a {@code null}/blank
     * {@link JsonConfig#jsonProfile()} means no global default; resolution falls through to the
     * {@code vertx} floor.
     *
     * @param jsonConfig the parsed global JSON config, or {@code null}
     * @return this builder
     */
    RestClientBuilder jsonConfig(@Nullable JsonConfig jsonConfig) {
        this.jsonConfig = jsonConfig;
        return this;
    }

    /**
     * Sets an explicit {@link ParamConversionResolver} to use for outbound parameter serialization.
     *
     * <p>When set, this resolver is used directly for all path/query/header/cookie parameter
     * conversions, bypassing any bindings or providers added via
     * {@link #paramConverterBinding(ParamConverterBinding)} or
     * {@link #paramConverterProvider(ParamConverterProvider)}.
     *
     * <p>Called by {@link RestClientFactory#builder()} with the Dagger-provided singleton resolver,
     * which already incorporates all application-registered converters. In standalone use
     * (without Dagger), callers can supply a custom resolver directly.
     *
     * @param resolver the resolver to use; {@code null} clears any previously set override
     * @return this builder
     */
    public RestClientBuilder paramConversionResolver(@Nullable ParamConversionResolver resolver) {
        this.conversionResolverOverride = resolver;
        return this;
    }

    /**
     * Adds a typed {@link ParamConverterBinding} for outbound parameter serialization.
     *
     * <p>When no explicit {@link #paramConversionResolver(ParamConversionResolver)} override is
     * set, the accumulated bindings are used to construct the resolver at {@link #build(Class)} time
     * via {@link ParamConverterRegistry#of(java.util.Set)}.
     *
     * <p>Has no effect when an explicit resolver override is present (the override wins).
     *
     * @param <T> the parameter type
     * @param binding the type-keyed converter binding
     * @return this builder
     */
    public <T> RestClientBuilder paramConverterBinding(ParamConverterBinding<T> binding) {
        this.converterBindings.add(binding);
        return this;
    }

    /**
     * Adds a JAX-RS {@link ParamConverterProvider} for outbound parameter serialization.
     *
     * <p>When no explicit {@link #paramConversionResolver(ParamConversionResolver)} override is
     * set, the accumulated providers are used to construct the resolver at {@link #build(Class)} time
     * alongside any accumulated {@link #paramConverterBinding(ParamConverterBinding) bindings}.
     *
     * <p>Has no effect when an explicit resolver override is present (the override wins).
     *
     * @param provider the JAX-RS param converter provider
     * @return this builder
     */
    public RestClientBuilder paramConverterProvider(ParamConverterProvider provider) {
        this.converterProviders.add(provider);
        return this;
    }

    /**
     * Provides the typed, validated external configuration for this client's runtime overrides.
     *
     * <p>The config is the {@link RestClientConfig} parsed at the {@code RestClientModule} provider
     * boundary from the {@code restClient.{name}} section and resolved for this client by name. Its
     * fields override the builder-level baselines when present (see the class javadoc for the
     * per-concern priority). Passing the config for a different client name is a no-op for this
     * client's effective values beyond what the config carries.
     *
     * @param config the typed per-client configuration, or {@code null} for no external overrides
     * @return this builder
     */
    public RestClientBuilder config(@Nullable RestClientConfig config) {
        this.config = config;
        return this;
    }

    /**
     * Seeds the full {@code name -> RestClientConfig} index so {@link #build(Class)} can resolve the
     * per-client config by the client's resolved name. Used by {@link RestClientFactory#builder()} to
     * pre-seed every builder from the DI-parsed index. An explicit {@link #config(RestClientConfig)}
     * always takes precedence over a name lookup in this index.
     *
     * @param configIndex the immutable client-name → config index; {@code null} is treated as empty
     * @return this builder
     */
    RestClientBuilder configIndex(@Nullable Map<String, RestClientConfig> configIndex) {
        this.configIndex = configIndex != null ? configIndex : Map.of();
        return this;
    }

    // --- Terminal operation ---

    /**
     * Builds and returns a typed proxy implementing {@code clientInterface}.
     *
     * <p>Build steps:
     * <ol>
     *   <li>Resolve client name from {@link RestClient#name()} or interface simple name</li>
     *   <li>Snapshot builder state into local effective variables</li>
     *   <li>Apply external config overrides to local variables (NOT builder fields) — builder
     *       state is never mutated, so the builder can safely be reused</li>
     *   <li>Resolve base URL from effective value or {@link RestClient#value()} annotation;
     *       fail fast if still blank</li>
     *   <li>Scan interface metadata (cached per JVM lifetime)</li>
     *   <li>Create a fresh {@link WebClient}</li>
     *   <li>Resolve circuit breaker: builder-level → annotation override → external config
     *       override</li>
     *   <li>Return the generated {@code {Client}_RestClientProxy} static proxy when one is present on
     *       the classpath (resolved via {@link dev.vertique.core.util.GeneratedCompanions}); otherwise
     *       fall back to a JDK dynamic proxy backed by {@link RestClientProxy}</li>
     * </ol>
     *
     * @param <T> the client interface type
     * @param clientInterface the JAX-RS-annotated interface to proxy; must be an interface
     * @return a ready-to-use proxy instance
     * @throws IllegalArgumentException if no base URL can be resolved or the interface is not
     *     annotated with JAX-RS HTTP verb annotations
     * @throws RestClientException if external config values fail validation
     */
    @SuppressWarnings("unchecked")
    public <T> T build(Class<T> clientInterface) {
        if (clientInterface == null) {
            throw new IllegalArgumentException("Client interface must not be null");
        }
        if (!clientInterface.isInterface()) {
            throw new IllegalArgumentException("REST client type must be an interface: " + clientInterface.getName());
        }

        String clientName = resolveClientName(clientInterface);

        // Resolve the per-client typed config: an explicitly-set config() wins; otherwise look it up
        // by the resolved client name in the seeded index (RestClientFactory.builder()).
        RestClientConfig effectiveConfig = this.config != null ? this.config : this.configIndex.get(clientName);

        // --- Snapshot builder state into local effective variables ---
        // External config overrides are applied to the local vars only; builder fields
        // are never mutated so the builder can be reused across multiple build() calls.
        String effectiveBaseUrl = this.baseUrl;
        long effectiveReadTimeoutMs = this.readTimeoutMs;
        WebClientOptions effectiveWebClientOptions = this.webClientOptions;
        PoolOptions effectivePoolOptions = this.poolOptions;
        RestClientRetryPolicy effectiveRetryPolicy = this.retryPolicy;
        BackoffStrategy effectiveBackoffStrategy = this.backoffStrategy;

        // --- Level 1: Start with builder-level circuit breaker (lowest priority) ---
        CircuitBreakerOptions effectiveCb = this.circuitBreakerOptions;

        // --- Level 2: Interface annotation overrides builder-level ---
        CircuitBreaker cbAnn = clientInterface.getAnnotation(CircuitBreaker.class);
        if (cbAnn != null) {
            effectiveCb = new CircuitBreakerOptions()
                    .setTimeout(cbAnn.timeoutMs())
                    .setMaxFailures(cbAnn.maxFailures())
                    .setResetTimeout(cbAnn.resetTimeoutMs());
        }

        // --- Level 3: External config overrides everything (highest priority) ---
        // The config is the typed, validated RestClientConfig parsed at the module boundary; all
        // bounds/positivity/loadability checks already fired at parse time, so this block only
        // applies present values. Each null field means "not overridden".
        if (effectiveConfig != null) {
            // Override baseUrl
            if (effectiveConfig.baseUrl() != null && !effectiveConfig.baseUrl().isBlank()) {
                effectiveBaseUrl = effectiveConfig.baseUrl();
            }

            // Override readTimeoutMs (validated > 0 at parse time)
            if (effectiveConfig.readTimeoutMs() != null) {
                effectiveReadTimeoutMs = effectiveConfig.readTimeoutMs();
            }

            // Override circuitBreaker (individual field overrides, not a full replacement)
            RestClientCircuitBreakerConfig cbConfig = effectiveConfig.circuitBreaker();
            if (cbConfig != null) {
                if (effectiveCb == null) {
                    effectiveCb = new CircuitBreakerOptions();
                }
                if (cbConfig.maxFailures() != null) {
                    effectiveCb.setMaxFailures(cbConfig.maxFailures());
                }
                if (cbConfig.timeoutMs() != null) {
                    effectiveCb.setTimeout(cbConfig.timeoutMs());
                }
                if (cbConfig.resetTimeoutMs() != null) {
                    effectiveCb.setResetTimeout(cbConfig.resetTimeoutMs());
                }
                if (cbConfig.maxRetries() != null) {
                    effectiveCb.setMaxRetries(cbConfig.maxRetries());
                }
            }

            // Override pool
            RestClientPoolConfig poolConfig = effectiveConfig.pool();
            if (poolConfig != null) {
                PoolOptions opts = effectivePoolOptions != null ? effectivePoolOptions : new PoolOptions();
                if (poolConfig.http1MaxSize() != null) {
                    opts.setHttp1MaxSize(poolConfig.http1MaxSize());
                }
                if (poolConfig.http2MaxSize() != null) {
                    opts.setHttp2MaxSize(poolConfig.http2MaxSize());
                }
                if (poolConfig.maxWaitQueueSize() != null) {
                    opts.setMaxWaitQueueSize(poolConfig.maxWaitQueueSize());
                }
                if (poolConfig.eventLoopSize() != null) {
                    opts.setEventLoopSize(poolConfig.eventLoopSize());
                }
                if (poolConfig.cleanerPeriodMs() != null) {
                    opts.setCleanerPeriod(poolConfig.cleanerPeriodMs());
                }
                if (poolConfig.maxLifetimeSeconds() != null) {
                    opts.setMaxLifetime(poolConfig.maxLifetimeSeconds());
                    opts.setMaxLifetimeUnit(java.util.concurrent.TimeUnit.SECONDS);
                }
                effectivePoolOptions = opts;
            }

            // Override webClient options. The bag is already normalized to Vert.x-native field names
            // at parse time (RestClientConfig.normalizeWebClientKeys), so it merges on top of the
            // builder-level options directly. Non-duration fields (ssl, verifyHost, keepAlive, etc.)
            // pass through unchanged.
            JsonObject webClientConfig = effectiveConfig.webClient();
            if (webClientConfig != null) {
                effectiveWebClientOptions = applyWebClientConfig(effectiveWebClientOptions, webClientConfig);
            }

            // Override retry config (backoff strategy only — maxRetries/retryOn/abortOn are
            // per-method via @Retry annotation; client-level override is a future enhancement). The
            // FQCN was validated loadable + assignable at parse time, so instantiation here only fails
            // on a no-arg-constructor problem, which is surfaced as a RestClientException.
            RestClientRetryConfig retryConfig = effectiveConfig.retry();
            if (retryConfig != null
                    && retryConfig.backoffStrategy() != null
                    && !retryConfig.backoffStrategy().isBlank()) {
                String backoffFqcn = retryConfig.backoffStrategy();
                try {
                    Class<?> cls = Class.forName(backoffFqcn);
                    effectiveBackoffStrategy =
                            (BackoffStrategy) cls.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    throw new RestClientException(
                            "Failed to instantiate backoffStrategy '" + backoffFqcn + "' for client '" + clientName
                                    + "'",
                            e);
                }
            }
        }

        // Scan interface metadata first — needed to check if all methods use @Url and to
        // validate that each convertible parameter has a resolvable converter.
        Map<Method, ClientMethodMeta> methodMetas =
                META_CACHE.computeIfAbsent(clientInterface, ClientInterfaceScanner::scan);

        // --- Compute effective ParamConversionResolver ---
        // If an override was set (either by RestClientFactory.builder() seeding the Dagger singleton
        // or by an explicit builder.paramConversionResolver() call), use it as-is. Otherwise build
        // one from the accumulated bindings + providers, preserving insertion order.
        ParamConversionResolver effectiveResolver = this.conversionResolverOverride != null
                ? this.conversionResolverOverride
                : ParamConversionResolver.of(
                        ParamConverterRegistry.of(new LinkedHashSet<>(converterBindings)),
                        new LinkedHashSet<>(converterProviders));

        // --- Build-time converter validation ---
        // For each method, probe each conversion-applicable param (PATH, QUERY, HEADER, COOKIE)
        // to ensure the effective resolver can serialize it. Fail fast at build time rather than at
        // first invocation.
        for (ClientMethodMeta method : methodMetas.values()) {
            for (ClientParamMeta param : method.params()) {
                ClientParamMeta.ParamSource src = param.source();
                if (src == ClientParamMeta.ParamSource.PATH
                        || src == ClientParamMeta.ParamSource.QUERY
                        || src == ClientParamMeta.ParamSource.HEADER
                        || src == ClientParamMeta.ParamSource.COOKIE) {
                    // Scalar param: validate the param type itself.
                    validateConvertible(effectiveResolver, clientName, method, param, "param");
                } else if (src == ClientParamMeta.ParamSource.BEAN_PARAM) {
                    // Bean param: validate each nested conversion-applicable field.
                    for (ClientParamMeta field : param.beanFields()) {
                        ClientParamMeta.ParamSource fsrc = field.source();
                        if (fsrc == ClientParamMeta.ParamSource.PATH
                                || fsrc == ClientParamMeta.ParamSource.QUERY
                                || fsrc == ClientParamMeta.ParamSource.HEADER
                                || fsrc == ClientParamMeta.ParamSource.COOKIE) {
                            validateConvertible(effectiveResolver, clientName, method, field, "bean-param field");
                        }
                    }
                }
            }
        }

        // Skip base URL validation when ALL methods use @Url (empty interfaces must not bypass)
        boolean allMethodsUseUrl =
                !methodMetas.isEmpty() && methodMetas.values().stream().allMatch(ClientMethodMeta::hasUrlParam);

        // --- Resolve base URL ---
        String resolvedBaseUrl = resolveBaseUrl(clientInterface, effectiveBaseUrl);
        if (!allMethodsUseUrl && resolvedBaseUrl.isBlank()) {
            throw new IllegalArgumentException("No base URL configured for REST client '"
                    + clientName
                    + "'. Set it via builder.baseUrl(...), @RestClient(value = \"...\"), or external config.");
        }

        // Effective ObjectMapper resolved by JSON-profile precedence (FR-JSON-030A):
        // explicit objectMapper > config jsonProfile > builder jsonProfile > @JsonProfile > vertx.
        ObjectMapper effectiveMapper = resolveEffectiveMapper(effectiveConfig, clientInterface, clientName);

        // Build WebClient
        WebClient webClient = buildWebClient(effectiveWebClientOptions, effectivePoolOptions);

        // Create circuit breaker if configured
        io.vertx.circuitbreaker.CircuitBreaker circuitBreaker = null;
        if (effectiveCb != null) {
            circuitBreaker = io.vertx.circuitbreaker.CircuitBreaker.create(clientName, vertx, effectiveCb);
            log.debug(
                    "Created circuit breaker '{}' for {} (maxFailures={})",
                    clientName,
                    clientInterface.getSimpleName(),
                    effectiveCb.getMaxFailures());
        }

        log.debug(
                "Building REST client proxy for {} with baseUrl={}", clientInterface.getSimpleName(), resolvedBaseUrl);

        // Build shared pipeline components
        io.vertx.core.MultiMap cachedDefaultHeaders = io.vertx.core.MultiMap.caseInsensitiveMultiMap();
        defaultHeaders.forEach(cachedDefaultHeaders::set);

        RestClientInterceptorChain interceptorChain =
                new RestClientInterceptorChain(clientName, sortedByPriority(List.copyOf(interceptors)));
        RestClientResilienceResolver resilienceResolver = new RestClientResilienceResolver(
                clientName,
                effectiveReadTimeoutMs,
                defaultExpectation,
                circuitBreaker,
                effectiveRetryPolicy,
                effectiveBackoffStrategy,
                vertx);

        RestClientDispatcher dispatcher = new DefaultRestClientDispatcher(
                webClient,
                resolvedBaseUrl,
                cachedDefaultHeaders,
                interceptorChain,
                exceptionMapper,
                effectiveMapper,
                resilienceResolver,
                beanValidator,
                clientName,
                sortedCapturers(List.copyOf(contextCapturers)),
                effectiveResolver);

        // --- Try generated proxy first, fall back to JDK reflective proxy ---
        // GeneratedCompanions.instantiate uses GeneratedNames.companionFqn (origin package,
        // '$' → '_') so nested clients (Outer$Inner) resolve to Outer_Inner_RestClientProxy,
        // matching exactly what the annotation processor emits. Catches both
        // ReflectiveOperationException and LinkageError (static-initialiser failures).
        return GeneratedCompanions.instantiate(
                        clientInterface,
                        "_RestClientProxy",
                        new Class<?>[] {RestClientDispatcher.class, BeanParamAccessorRegistry.class, Map.class},
                        new Object[] {dispatcher, beanParamAccessorRegistry, methodMetas},
                        (fqn, e) -> new RestClientConfigurationException(
                                "Generated proxy %s present but failed to instantiate".formatted(fqn), e))
                .map(proxy -> {
                    log.debug("Using generated static proxy for {}", clientInterface.getSimpleName());
                    return proxy;
                })
                .orElseGet(() -> {
                    log.debug(
                            "Generated proxy not found; falling back to JDK reflective proxy for {}",
                            clientInterface.getSimpleName());
                    RestClientProxy handler = new RestClientProxy(
                            dispatcher,
                            methodMetas,
                            effectiveMapper,
                            beanParamAccessorRegistry,
                            clientName,
                            effectiveResolver);
                    return (T) Proxy.newProxyInstance(
                            clientInterface.getClassLoader(), new Class<?>[] {clientInterface}, handler);
                });
    }

    /**
     * Validates that {@code resolver} can serialize {@code param}, throwing a
     * {@link RestClientConfigurationException} if not. Shared by {@link #build(Class)}'s build-time
     * converter validation for both top-level conversion-applicable params (PATH/QUERY/HEADER/COOKIE)
     * and {@code @BeanParam} fields of the same sources — the two call sites differ only in
     * {@code kindLabel} (used in the error message to distinguish "param" from "bean-param field").
     *
     * @param resolver the effective {@link ParamConversionResolver} to probe
     * @param clientName the resolved client name, used in the error message
     * @param method the method metadata, used for the error message's method name
     * @param param the parameter (or bean field) to validate; its {@link ClientParamMeta#source()}
     *     must be one of PATH/QUERY/HEADER/COOKIE
     * @param kindLabel the label describing {@code param} in the error message ({@code "param"} or
     *     {@code "bean-param field"})
     * @throws RestClientConfigurationException if no converter can be resolved for {@code param}
     */
    private static void validateConvertible(
            ParamConversionResolver resolver,
            String clientName,
            ClientMethodMeta method,
            ClientParamMeta param,
            String kindLabel) {
        ClientParamMeta.ParamSource src = param.source();
        Class<?> rawType = param.componentType() != null ? param.componentType() : param.type();
        ConversionContext ctx = new ConversionContext(
                param.name(), ClientConversionContexts.toSource(src), rawType, rawType, null, param.annotationsLazy());
        if (!resolver.canResolve(ctx)) {
            throw new RestClientConfigurationException("REST client '" + clientName + "' method '"
                    + method.methodMetadata().name() + "': no ParamConverter registered for "
                    + src.name().toLowerCase(java.util.Locale.ROOT) + " " + kindLabel + " '"
                    + param.name() + "' of type '" + rawType.getName()
                    + "'. Register a ParamConverterBinding or ParamConverterProvider via the"
                    + " builder (paramConverterBinding/paramConverterProvider) or the"
                    + " Dagger application module.");
        }
    }

    // --- Internal helpers ---

    /**
     * Sorts interceptors in {@link dev.vertique.core.extension.OrderedExtension} order (phase, then
     * priority, then orderKey).
     *
     * @param interceptors the interceptors to sort
     * @return a new sorted list
     */
    private static List<RestClientInterceptor> sortedByPriority(List<RestClientInterceptor> interceptors) {
        return interceptors.stream()
                .sorted(dev.vertique.core.extension.OrderedExtension.comparator())
                .toList();
    }

    /**
     * Sorts context capturers in {@link dev.vertique.core.extension.OrderedExtension} order.
     *
     * @param capturers the capturers to sort
     * @return a new sorted list
     */
    private static List<RestClientContextCapturer<?>> sortedCapturers(List<RestClientContextCapturer<?>> capturers) {
        return capturers.stream()
                .sorted(dev.vertique.core.extension.OrderedExtension.comparator())
                .toList();
    }

    /**
     * Resolves the logical client name from the {@link RestClient#name()} annotation, falling
     * back to the interface's simple class name.
     *
     * @param clientInterface the client interface class
     * @return the resolved client name, never blank
     */
    private String resolveClientName(Class<?> clientInterface) {
        RestClient annotation = clientInterface.getAnnotation(RestClient.class);
        if (annotation != null && !annotation.name().isBlank()) {
            return annotation.name();
        }
        return clientInterface.getSimpleName();
    }

    /**
     * Resolves the base URL from the effective (possibly config-overridden) value, falling back
     * to the {@link RestClient#value()} annotation if the effective value is blank.
     *
     * @param clientInterface the client interface class
     * @param effectiveBaseUrl the current effective base URL (may be {@code null} or blank)
     * @return the resolved base URL, may be blank if neither source provides a value
     */
    private String resolveBaseUrl(Class<?> clientInterface, @Nullable String effectiveBaseUrl) {
        if (effectiveBaseUrl != null && !effectiveBaseUrl.isBlank()) {
            return effectiveBaseUrl;
        }
        RestClient annotation = clientInterface.getAnnotation(RestClient.class);
        if (annotation != null && !annotation.value().isBlank()) {
            return annotation.value();
        }
        return "";
    }

    /**
     * Resolves the effective {@link ObjectMapper} for this client by the frozen JSON-profile
     * precedence (FR-JSON-030A), highest to lowest:
     *
     * <ol>
     *   <li>an explicit {@link #objectMapper(ObjectMapper)} — used outright, the registry is never
     *       consulted (FR-JSON-027);</li>
     *   <li>the external per-client config {@code restClient.&lt;name&gt;.jsonProfile}
     *       ({@code effectiveConfig.jsonProfile()}, when non-blank);</li>
     *   <li>the builder-level {@link #jsonProfile(JsonProfileId)} (when set);</li>
     *   <li>the interface-level {@link JsonProfile} annotation (when non-blank);</li>
     *   <li>the {@code restClient.defaults.jsonProfile} boundary default
     *       ({@link #defaultsJsonProfileId}, when non-blank);</li>
     *   <li>the global {@code json.jsonProfile} default ({@link #jsonConfig}, when non-blank);</li>
     *   <li>the reserved {@code vertx} profile ({@link DatabindCodec#mapper()}) — the zero-config
     *       floor, never requires a registry.</li>
     * </ol>
     *
     * <p>The resolved profile id is then mapped to a mapper: a {@code null} id or
     * {@link JsonProfileId#VERTX} resolves to {@link DatabindCodec#mapper()} <strong>without</strong>
     * requiring a registry (FR-JSON-031); any other id is resolved through the seeded
     * {@link JsonMapperProfileRegistry}. A non-{@code vertx} profile with no seeded registry (the
     * standalone-builder case) fails fast with a {@link RestClientConfigurationException}; an id the
     * registry does not know fails fast with a {@link JsonProfileConfigurationException} — both at
     * client-build time, never at first request.
     *
     * <p>Package-private so it is unit-testable in isolation.
     *
     * @param effectiveConfig the per-client external config resolved earlier in {@link #build(Class)},
     *     or {@code null} when no external config applies
     * @param clientInterface the client interface, read for the interface-level {@link JsonProfile}
     * @param clientName the resolved client name, used in error messages
     * @return the effective {@link ObjectMapper}; never {@code null}
     * @throws RestClientConfigurationException if a non-{@code vertx} profile is selected but no
     *     {@link JsonMapperProfileRegistry} was seeded (standalone builder)
     * @throws dev.vertique.core.json.JsonProfileConfigurationException if the selected profile id is
     *     not registered in the seeded registry
     * @throws IllegalStateException if a method-level {@link JsonProfile} is present (FR-JSON-066)
     */
    ObjectMapper resolveEffectiveMapper(
            @Nullable RestClientConfig effectiveConfig, Class<?> clientInterface, String clientName) {
        // FR-JSON-066: reject a method-level @JsonProfile before any early-return. This must run even
        // when an explicit objectMapper short-circuits resolution below, otherwise an explicit-mapper
        // client could carry a silently-ignored method-level @JsonProfile.
        validateNoMethodLevelJsonProfile(clientInterface);

        // --- Level 1: explicit objectMapper wins outright; registry is never consulted. ---
        if (this.objectMapper != null) {
            return this.objectMapper;
        }

        // --- Levels 2–4: resolve the effective profile id (first non-blank source wins). ---
        JsonProfileId effectiveId = resolveEffectiveProfileId(effectiveConfig, clientInterface);

        // --- Resolve the id to a mapper. ---
        // null id or the reserved vertx id → the zero-config default, no registry required.
        if (effectiveId == null || JsonProfileId.VERTX.equals(effectiveId)) {
            return DatabindCodec.mapper();
        }

        // A non-vertx profile requires the seeded registry.
        if (this.jsonMapperProfileRegistry == null) {
            throw new RestClientConfigurationException(
                    "REST client '" + clientName + "' selects JSON profile '" + effectiveId.value()
                            + "' but no JsonMapperProfileRegistry is available. Build this client via the"
                            + " Dagger-provided RestClientFactory (which seeds the registry), or set an explicit"
                            + " objectMapper(...) on the builder.");
        }
        // Throws JsonProfileConfigurationException for an unknown id (desired fail-fast at build time).
        return this.jsonMapperProfileRegistry.mapper(effectiveId);
    }

    /**
     * Resolves the effective {@link JsonProfileId} for this client from the first non-blank source
     * in precedence order:
     * <ol>
     *   <li>external config {@code restClient.&lt;name&gt;.jsonProfile}</li>
     *   <li>builder-level {@link #jsonProfile}</li>
     *   <li>interface-level {@link JsonProfile @JsonProfile}
     *       (see {@link #resolveInterfaceProfileId(Class)})</li>
     *   <li>{@code restClient.defaults.jsonProfile} boundary default ({@link #defaultsJsonProfileId})</li>
     *   <li>global {@code json.jsonProfile} default ({@link #jsonConfig})</li>
     * </ol>
     * Returns {@code null} when no source selects a profile (meaning the {@code vertx} default applies).
     *
     * @param effectiveConfig the per-client external config, or {@code null}
     * @param clientInterface the client interface, read for the interface-level {@link JsonProfile}
     * @return the resolved profile id, or {@code null} when none is selected (vertx floor)
     */
    @Nullable
    private JsonProfileId resolveEffectiveProfileId(
            @Nullable RestClientConfig effectiveConfig, Class<?> clientInterface) {
        // Level 2: external per-client config.
        if (effectiveConfig != null
                && effectiveConfig.jsonProfile() != null
                && !effectiveConfig.jsonProfile().isBlank()) {
            return JsonProfileId.of(effectiveConfig.jsonProfile());
        }
        // Level 3: builder-level profile.
        if (this.jsonProfile != null) {
            return this.jsonProfile;
        }
        // Level 4: interface-level @JsonProfile.
        JsonProfileId interfaceId = resolveInterfaceProfileId(clientInterface);
        if (interfaceId != null) {
            return interfaceId;
        }
        // Level 5: restClient.defaults.jsonProfile boundary default.
        if (this.defaultsJsonProfileId != null && !this.defaultsJsonProfileId.isBlank()) {
            return JsonProfileId.of(this.defaultsJsonProfileId);
        }
        // Level 6: global json.jsonProfile default.
        if (this.jsonConfig != null) {
            String globalId = this.jsonConfig.jsonProfile();
            if (globalId != null && !globalId.isBlank()) {
                return JsonProfileId.of(globalId);
            }
        }
        // No selection → vertx default (represented as null here).
        return null;
    }

    /**
     * Resolves the interface-level profile id (Level 4) from the universal {@link JsonProfile}
     * annotation on the client interface TYPE.
     *
     * <p>{@code @JsonProfile} is the sole per-binding selection annotation for a REST client:
     * a non-blank {@link JsonProfile#value()} selects that profile; an absent or blank
     * {@code @JsonProfile} selects nothing and falls through to the next precedence level.
     *
     * @param clientInterface the client interface to read
     * @return the resolved interface-level profile id, or {@code null} when {@code @JsonProfile} is
     *     absent or blank
     */
    @Nullable
    private JsonProfileId resolveInterfaceProfileId(Class<?> clientInterface) {
        JsonProfile jsonProfileAnn = clientInterface.getAnnotation(JsonProfile.class);
        String annValue = jsonProfileAnn != null ? jsonProfileAnn.value() : null;
        if (annValue != null && !annValue.isBlank()) {
            return JsonProfileId.of(annValue);
        }
        return null;
    }

    /**
     * FR-JSON-066: rejects a method-level {@link JsonProfile} on a {@link RestClient} interface.
     *
     * <p>{@code @JsonProfile} on a rest-client interface is honored only at TYPE level — the builder
     * resolves a single {@link ObjectMapper} at client-build time, so a method-level annotation would
     * be a silent no-op. This guard runs unconditionally at build time (before the explicit-mapper
     * early-return in {@link #resolveEffectiveMapper}) so it cannot be bypassed.
     *
     * @param clientInterface the client interface to scan
     * @throws IllegalStateException if any method of the interface carries a {@link JsonProfile}
     */
    private void validateNoMethodLevelJsonProfile(Class<?> clientInterface) {
        for (Method method : clientInterface.getMethods()) {
            if (method.isAnnotationPresent(JsonProfile.class)) {
                throw new IllegalStateException("REST client interface '" + clientInterface.getName()
                        + "' declares @JsonProfile on method '" + method.getName()
                        + "'. @JsonProfile is honored only at the TYPE level on a @RestClient interface"
                        + " — move it to the interface declaration.");
            }
        }
    }

    /**
     * Creates a fresh {@link WebClient} using the effective {@link WebClientOptions} and
     * {@link PoolOptions}. If no options were provided, defaults are used.
     *
     * <p>The effective options are already fully resolved at the call site (builder state
     * overridden by external config), so this method simply creates the client without
     * any further resolution.
     *
     * @param effectiveWebClientOptions the effective WebClient options to apply; {@code null} for defaults
     * @param effectivePoolOptions the pool options to apply; {@code null} for defaults
     * @return a new WebClient instance
     */
    private WebClient buildWebClient(
            @Nullable WebClientOptions effectiveWebClientOptions, @Nullable PoolOptions effectivePoolOptions) {
        WebClientOptions opts = effectiveWebClientOptions != null ? effectiveWebClientOptions : new WebClientOptions();
        if (effectivePoolOptions != null) {
            return WebClient.create(vertx, opts, effectivePoolOptions);
        }
        return WebClient.create(vertx, opts);
    }

    /**
     * Merges the per-client {@code webClient} config bag onto the base {@link WebClientOptions} and
     * reconstructs the options.
     *
     * <p>The bag is already normalized to Vert.x's native field names at the module boundary (see
     * {@link RestClientConfig#normalizeWebClientKeys(JsonObject)}) — the 7 framework duration keys
     * (e.g. {@code connectTimeoutMs}, {@code keepAliveTimeoutSeconds}) have been translated to their
     * Vert.x option names — so this method merges the bag directly without further renaming.
     * Non-duration fields ({@code ssl}, {@code verifyHost}, {@code keepAlive}, …) pass through.
     *
     * @param base   the base options; {@code null} is treated as a fresh default instance
     * @param config the normalized {@code webClient} config bag (Vert.x-native field names)
     * @return new {@link WebClientOptions} with the config merged on top of base
     */
    private static WebClientOptions applyWebClientConfig(@Nullable WebClientOptions base, JsonObject config) {
        JsonObject baseJson = base != null ? base.toJson() : new JsonObject();
        baseJson.mergeIn(config);
        return new WebClientOptions(baseJson);
    }
}
