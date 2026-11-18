// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.config.HttpClientConfig;
import dev.vertique.http.client.config.HttpClientEgressConfig;
import dev.vertique.http.client.config.HttpClientPoolConfig;
import dev.vertique.http.client.exception.HttpClientConfigurationException;
import dev.vertique.http.client.interceptor.HttpClientInterceptor;
import dev.vertique.http.client.internal.CredentialInterceptor;
import dev.vertique.http.client.internal.FrameworkClientOptions;
import dev.vertique.http.client.internal.RedirectRules;
import dev.vertique.http.client.internal.WebClientOptionsHardening;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientBuilder;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.annotation.Nullable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds {@link VertiqueHttpClient} instances.
 *
 * <p>Setters take effect at {@link #build()}: each build snapshots the builder's current values, so
 * later changes to the builder do not reach clients already built, and every build creates its own
 * HTTP agent. {@link WebClientOptions} and {@link PoolOptions} are copied when they are set. A build
 * that is rejected leaves the builder usable once the setting is corrected.
 */
public final class VertiqueHttpClientBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(VertiqueHttpClientBuilder.class);
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final String DEFAULT_NAME = "default";
    private static final long DEFAULT_READ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);

    private static final long DEFAULT_MAX_RESPONSE_BYTES = 10_485_760L;
    private static final int DEFAULT_MAX_EVENT_BYTES = 1_048_576;
    private static final long DEFAULT_STREAM_IDLE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(60);

    static {
        FrameworkClientOptions.register(VertiqueHttpClientBuilder::disableHeaderValidation);
    }

    private final Vertx vertx;
    private final Resilience resilience;
    private final List<VertiqueHttpClient> built = new ArrayList<>();
    private final AtomicReference<Future<Void>> closeFuture = new AtomicReference<>();

    private String name = DEFAULT_NAME;
    private String baseUrl;
    private long readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
    private long maxRequestBytes;
    private long maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
    private long streamIdleTimeoutMs = DEFAULT_STREAM_IDLE_TIMEOUT_MS;
    private int maxEventBytes = DEFAULT_MAX_EVENT_BYTES;
    private WebClientOptions webClientOptions;
    private PoolOptions poolOptions;
    private final Map<String, String> defaultHeaders = new LinkedHashMap<>();
    private boolean validateHeaders = true;
    private EgressPolicy egress = EgressPolicy.allowAll();
    private CredentialSource credentials;
    private boolean allowCredentialsOverPlainHttp;
    private List<String> credentialOrigins = List.of();
    private final List<HttpClientInterceptor> interceptors = new ArrayList<>();
    private ResiliencePolicyRegistry policyRegistry = ResiliencePolicyRegistry.empty();
    private String resiliencePolicy;
    private HttpClientConfig config;

    private VertiqueHttpClientBuilder(Vertx vertx, @Nullable Resilience resilience) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.resilience = resilience;
    }

    /**
     * Creates a builder.
     *
     * @param vertx the Vert.x instance the clients run on
     * @return a new builder
     */
    public static VertiqueHttpClientBuilder create(Vertx vertx) {
        return new VertiqueHttpClientBuilder(vertx, null);
    }

    /**
     * Creates a builder that uses a resilience runtime the caller owns. Closing the builder does not
     * close that runtime.
     *
     * @param vertx the Vert.x instance the clients run on
     * @param resilience the resilience runtime to use
     * @return a new builder
     */
    public static VertiqueHttpClientBuilder create(Vertx vertx, Resilience resilience) {
        return new VertiqueHttpClientBuilder(vertx, Objects.requireNonNull(resilience, "resilience"));
    }

    /**
     * Sets the client name, which must match {@code [A-Za-z0-9._~-]{1,128}}. The default is
     * {@code "default"}. An invalid name is rejected by {@link #build()}.
     *
     * @param name the client name
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder name(String name) {
        this.name = Objects.requireNonNull(name, "name");
        return this;
    }

    /**
     * Sets the base URL. Its scheme, host and port become the defaults of the {@code WebClient}, and
     * its path prefixes the URI of relative requests. A client without a base URL fails relative
     * requests.
     *
     * @param baseUrl an {@code http} or {@code https} URL without user information, query or fragment
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder baseUrl(String baseUrl) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        return this;
    }

    /**
     * Sets the default attempt timeout. The default is 30 seconds; {@code 0} means no attempt timer.
     *
     * @param timeout the timeout, not negative
     * @param unit the unit of {@code timeout}
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder readTimeout(long timeout, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        this.readTimeoutMs = timeout < 0 ? -1 : unit.toMillis(timeout);
        return this;
    }

    /**
     * Sets how long a stream may go without receiving a body byte before it fails with a
     * {@link java.util.concurrent.TimeoutException} and its exchange is reset. The timer also runs while
     * the consumer has paused the body. The default is 60 seconds; {@code 0} disables it.
     *
     * @param timeout the timeout, not negative; {@code 0} disables the idle timer
     * @param unit the unit of {@code timeout}
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder streamIdleTimeout(long timeout, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        this.streamIdleTimeoutMs = timeout < 0 ? -1 : unit.toMillis(timeout);
        return this;
    }

    /**
     * Sets the most bytes of a request body the client sends. Buffer, JSON and form bodies are measured
     * encoded and refused over the bound before anything is sent; stream and multipart bodies cannot be
     * measured and are refused while the bound is set. The default is {@code 0}, no bound.
     *
     * @param bytes the bound in bytes, not negative; {@code 0} means no bound
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder maxRequestBytes(long bytes) {
        this.maxRequestBytes = bytes;
        return this;
    }

    /**
     * Sets the most bytes of a response body the client reads, whatever body codec the request uses. A
     * declared {@code Content-Length} over the bound, or more received bytes, fail the request and
     * reset the exchange. The default is 10485760 bytes; {@code 0} means no bound.
     *
     * @param bytes the bound in bytes, not negative; {@code 0} means no bound
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder maxResponseBytes(long bytes) {
        this.maxResponseBytes = bytes;
        return this;
    }

    /**
     * Sets the most raw bytes of one server-sent event that {@link HttpStream#events()} accepts. Every byte
     * since the last dispatched event counts, comments, ignored fields and a partial line included; above
     * the bound the stream fails with {@code HttpBodyLimitException} (direction {@code EVENT}) and its
     * exchange is reset. The default is 1048576 bytes.
     *
     * @param bytes the bound in bytes, above 0; checked when the client is built
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder maxEventBytes(int bytes) {
        this.maxEventBytes = bytes;
        return this;
    }

    /**
     * Sets the {@code WebClient} options. They are copied and replace the base options, which follow no
     * redirects.
     *
     * @param options the options to copy
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder webClientOptions(WebClientOptions options) {
        this.webClientOptions = new WebClientOptions(Objects.requireNonNull(options, "options"));
        return this;
    }

    /**
     * Sets the connection pool options. They are copied.
     *
     * @param options the options to copy
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder poolOptions(PoolOptions options) {
        this.poolOptions = new PoolOptions(Objects.requireNonNull(options, "options"));
        return this;
    }

    /**
     * Adds a header to every request that does not set it. A second call with the same name replaces
     * the value. Name and value follow the request header rules; a violation is rejected by
     * {@link #build()}.
     *
     * @param name the header name
     * @param value the header value
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder defaultHeader(String name, String value) {
        defaultHeaders.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
        return this;
    }

    /**
     * Sets the egress policy every request target is checked against, before any connection and on
     * every redirect hop. The default is {@link EgressPolicy#allowAll()}, which still refuses a
     * domain-socket server address and a target with user information.
     *
     * <p>With redirects followed, {@code allowAll()} lets a redirect from an allowed server lead to any
     * host, including internal addresses. Use {@link EgressPolicy#allowHosts(List, boolean)} for a
     * client that calls untrusted servers.
     *
     * @param policy the policy to apply
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder egress(EgressPolicy policy) {
        this.egress = Objects.requireNonNull(policy, "policy");
        return this;
    }

    /**
     * Sets the source of per-exchange credential headers. The client resolves it once for every hop of
     * an exchange to the original origin, which is the origin of the base URL (or, without a base URL,
     * of the exchange's first hop) widened by {@link #credentialOrigins(List)}, and never for a hop to
     * another origin or after the exchange left the original origin. Each returned header replaces the
     * request's header of that name. Requests with a server address, a virtual host or a request-level
     * proxy get no credentials. Plain HTTP to a non-loopback host is refused unless
     * {@link #allowCredentialsOverPlainHttp(boolean)} says otherwise.
     *
     * @param source the credential source
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder credentials(CredentialSource source) {
        this.credentials = Objects.requireNonNull(source, "source");
        return this;
    }

    /**
     * Sets whether a client with a credential source may send requests over plain HTTP to a host that is
     * not loopback. The default is {@code false}: such a request fails with {@code
     * HttpEgressDeniedException} (reason {@code PLAIN_HTTP_CREDENTIALS}) before any connection.
     *
     * @param allow whether to allow credentials over plain HTTP
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder allowCredentialsOverPlainHttp(boolean allow) {
        this.allowCredentialsOverPlainHttp = allow;
        return this;
    }

    /**
     * Sets the origins besides the base URL's origin that credentials may go to. Each entry is {@code
     * scheme://host[:port]} with an {@code http} or {@code https} scheme; a missing port is the
     * scheme's default. An invalid entry is rejected by {@link #build()} without echoing it.
     *
     * @param origins the origins to add
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder credentialOrigins(List<String> origins) {
        this.credentialOrigins = List.copyOf(Objects.requireNonNull(origins, "origins"));
        return this;
    }

    /**
     * Registers an interceptor for the clients this builder builds. Interceptors run in
     * {@link dev.vertique.core.extension.OrderedExtension} order, whatever the registration order: their
     * {@code beforeRequest} prepares each request before credentials are applied, and their
     * {@code onAttemptCompleted} is told how each exchange ended. Registering the same instance twice
     * calls it twice. The REST client does not use the interceptors of this builder.
     *
     * @param interceptor the interceptor to add
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder register(HttpClientInterceptor interceptor) {
        interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
        return this;
    }

    /**
     * Sets the named resilience policy every call of the built clients runs in unless its options say
     * otherwise. The policy is looked up in the client's policy registry when the client is built, so an
     * undefined policy is rejected by {@link #build()}. A client without a resilience policy runs each
     * call as one attempt.
     *
     * @param tier the policy name
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder resiliencePolicy(String tier) {
        this.resiliencePolicy = Objects.requireNonNull(tier, "tier");
        return this;
    }

    /**
     * Sets the external configuration of the clients this builder builds. At {@link #build()} every
     * key the configuration sets wins over the matching builder setter, and a key it leaves out keeps
     * the builder's value, which in turn wins over the default. The {@code webClient} bag is merged over
     * the base options (those of {@link #webClientOptions(WebClientOptions)}, or the default options
     * that follow no redirects), so a bag without {@code followRedirects} keeps the base value. Default
     * headers of the configuration are added over the builder's. {@code null} removes a configuration
     * set earlier.
     *
     * @param config the parsed {@code httpClient.{name}} section, or {@code null}
     * @return this builder
     */
    public synchronized VertiqueHttpClientBuilder config(@Nullable HttpClientConfig config) {
        this.config = config;
        return this;
    }

    /**
     * Sets the registry the named resilience policies are resolved from. The default is an empty
     * registry, which defines no policy.
     *
     * @param registry the policy registry
     * @return this builder
     */
    synchronized VertiqueHttpClientBuilder policyRegistry(ResiliencePolicyRegistry registry) {
        this.policyRegistry = Objects.requireNonNull(registry, "registry");
        return this;
    }

    /**
     * Builds a client from the current settings.
     *
     * @return a new client with its own HTTP agent
     * @throws HttpClientConfigurationException when a setting is invalid
     * @throws IllegalStateException when the builder is closed
     */
    public synchronized VertiqueHttpClient build() {
        if (closeFuture.get() != null) {
            throw new IllegalStateException("The HTTP client builder is closed");
        }
        if (!NAME.matcher(name).matches()) {
            throw new HttpClientConfigurationException("The client name must match [A-Za-z0-9._~-]{1,128}");
        }
        HttpClientConfig external = config;
        long effectiveReadTimeoutMs =
                external != null && external.readTimeoutMs() != null ? external.readTimeoutMs() : readTimeoutMs;
        long effectiveStreamIdleTimeoutMs = external != null && external.streamIdleTimeoutMs() != null
                ? external.streamIdleTimeoutMs()
                : streamIdleTimeoutMs;
        long effectiveMaxRequestBytes =
                external != null && external.maxRequestBytes() != null ? external.maxRequestBytes() : maxRequestBytes;
        long effectiveMaxResponseBytes = external != null && external.maxResponseBytes() != null
                ? external.maxResponseBytes()
                : maxResponseBytes;
        int effectiveMaxEventBytes =
                external != null && external.maxEventBytes() != null ? external.maxEventBytes() : maxEventBytes;
        if (effectiveReadTimeoutMs < 0) {
            throw new HttpClientConfigurationException("The read timeout must not be negative");
        }
        if (effectiveStreamIdleTimeoutMs < 0) {
            throw new HttpClientConfigurationException("The stream idle timeout must not be negative");
        }
        if (effectiveMaxRequestBytes < 0 || effectiveMaxResponseBytes < 0) {
            throw new HttpClientConfigurationException("A body byte bound must not be negative");
        }
        if (effectiveMaxEventBytes <= 0) {
            throw new HttpClientConfigurationException("The event byte bound must be above 0");
        }
        Map<String, String> headers = validatedDefaultHeaders(external);
        String effectiveBaseUrl = external != null && external.baseUrl() != null ? external.baseUrl() : baseUrl;
        BaseUrl base = effectiveBaseUrl == null ? null : parseBaseUrl(effectiveBaseUrl);

        WebClientOptions effective = effectiveWebClientOptions(external);
        if (base != null) {
            effective.setDefaultHost(base.host()).setDefaultPort(base.port()).setSsl(base.secure());
        }
        if (WebClientOptionsHardening.isInsecure(effective)) {
            LOG.warn("The HTTP client options trust all certificates, skip host verification or enable TLS"
                    + " below 1.2");
        }
        boolean effectiveAllowPlain = external != null && external.allowCredentialsOverPlainHttp() != null
                ? external.allowCredentialsOverPlainHttp()
                : allowCredentialsOverPlainHttp;
        List<String> effectiveOrigins = external != null && external.credentialOrigins() != null
                ? external.credentialOrigins()
                : credentialOrigins;
        String effectivePolicy = external != null && external.resiliencePolicy() != null
                ? external.resiliencePolicy()
                : resiliencePolicy;
        ClientSettings settings = new ClientSettings(
                base == null ? "" : base.path(),
                base != null,
                validateHeaders,
                headers,
                effectiveReadTimeoutMs,
                effectiveMaxRequestBytes,
                effectiveMaxResponseBytes,
                effectiveStreamIdleTimeoutMs,
                effectiveMaxEventBytes,
                effectiveEgress(external),
                credentials,
                effectiveAllowPlain,
                credentialOriginSet(base, effectiveOrigins));
        PoolOptions effectivePool = effectivePoolOptions(external);

        HttpClientBuilder agentBuilder =
                vertx.httpClientBuilder().with(effective).withRedirectHandler(new RedirectRules());
        if (effectivePool != null) {
            agentBuilder.with(effectivePool);
        }
        HttpClientAgent agent = agentBuilder.build();
        try {
            VertiqueHttpClient client = new DefaultVertiqueHttpClient(
                    vertx,
                    name,
                    agent,
                    effective,
                    settings,
                    List.copyOf(interceptors),
                    resilience,
                    policyRegistry,
                    effectivePolicy);
            built.add(client);
            return client;
        } catch (RuntimeException failure) {
            agent.close();
            throw failure;
        }
    }

    /**
     * Closes the clients this builder built. Closing again returns the same future. A resilience
     * runtime passed to {@link #create(Vertx, Resilience)} stays open.
     *
     * @return a future that completes when every client is closed
     */
    public Future<Void> close() {
        Future<Void> existing = closeFuture.get();
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            existing = closeFuture.get();
            if (existing != null) {
                return existing;
            }
            List<Future<Void>> closures =
                    built.stream().map(VertiqueHttpClient::close).toList();
            Future<Void> result = closures.isEmpty()
                    ? Future.succeededFuture()
                    : Future.all(closures).mapEmpty();
            closeFuture.set(result);
            return result;
        }
    }

    /** Resilience runtime supplied by the caller, or {@code null} when none was supplied. */
    @Nullable
    Resilience resilience() {
        return resilience;
    }

    private static synchronized void disableHeaderValidation(VertiqueHttpClientBuilder builder) {
        builder.validateHeaders = false;
    }

    private Map<String, String> validatedDefaultHeaders(@Nullable HttpClientConfig external) {
        Map<String, String> merged = new LinkedHashMap<>(defaultHeaders);
        if (external != null) {
            merged.putAll(external.defaultHeaders());
        }
        for (Map.Entry<String, String> header : merged.entrySet()) {
            if (HeaderRules.violation(header.getKey(), header.getValue()) != null) {
                throw new HttpClientConfigurationException("A default header is not acceptable");
            }
        }
        return Collections.unmodifiableMap(merged);
    }

    /** The base options with the configured {@code webClient} bag merged over them. */
    private WebClientOptions effectiveWebClientOptions(@Nullable HttpClientConfig external) {
        WebClientOptions base = webClientOptions == null
                ? new WebClientOptions().setFollowRedirects(false)
                : new WebClientOptions(webClientOptions);
        JsonObject bag = external == null ? null : external.webClient();
        if (bag == null || bag.isEmpty()) {
            return base;
        }
        JsonObject merged = base.toJson();
        merged.mergeIn(bag);
        return new WebClientOptions(merged);
    }

    /** The builder's pool options with the configured pool keys applied over them, or {@code null} for none. */
    @Nullable
    private PoolOptions effectivePoolOptions(@Nullable HttpClientConfig external) {
        HttpClientPoolConfig pool = external == null ? null : external.pool();
        if (pool == null) {
            return poolOptions == null ? null : new PoolOptions(poolOptions);
        }
        PoolOptions options = poolOptions == null ? new PoolOptions() : new PoolOptions(poolOptions);
        if (pool.http1MaxSize() != null) {
            options.setHttp1MaxSize(pool.http1MaxSize());
        }
        if (pool.http2MaxSize() != null) {
            options.setHttp2MaxSize(pool.http2MaxSize());
        }
        if (pool.maxWaitQueueSize() != null) {
            options.setMaxWaitQueueSize(pool.maxWaitQueueSize());
        }
        if (pool.eventLoopSize() != null) {
            options.setEventLoopSize(pool.eventLoopSize());
        }
        if (pool.cleanerPeriodMs() != null) {
            options.setCleanerPeriod(pool.cleanerPeriodMs());
        }
        if (pool.maxLifetimeSeconds() != null) {
            options.setMaxLifetime(pool.maxLifetimeSeconds());
            options.setMaxLifetimeUnit(TimeUnit.SECONDS);
        }
        return options;
    }

    private EgressPolicy effectiveEgress(@Nullable HttpClientConfig external) {
        HttpClientEgressConfig configured = external == null ? null : external.egress();
        if (configured == null || configured.allowHosts() == null) {
            return egress;
        }
        return EgressPolicy.allowHosts(configured.allowHosts(), Boolean.TRUE.equals(configured.allowPlainHttp()));
    }

    private List<String> credentialOriginSet(BaseUrl base, List<String> credentialOrigins) {
        Set<String> origins = new TreeSet<>();
        if (base != null) {
            origins.add(CredentialInterceptor.origin(base.secure(), base.host(), base.port()));
        }
        for (String entry : credentialOrigins) {
            origins.add(parseCredentialOrigin(entry));
        }
        return List.copyOf(origins);
    }

    private static String parseCredentialOrigin(String text) {
        URI uri;
        try {
            uri = new URI(Objects.requireNonNull(text, "origin"));
        } catch (URISyntaxException invalid) {
            throw invalidCredentialOrigin();
        }
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        String path = uri.getRawPath();
        if (!secure && !"http".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || path != null && !path.isEmpty()) {
            throw invalidCredentialOrigin();
        }
        return CredentialInterceptor.origin(
                secure, uri.getHost(), uri.getPort() >= 0 ? uri.getPort() : secure ? 443 : 80);
    }

    private static HttpClientConfigurationException invalidCredentialOrigin() {
        return new HttpClientConfigurationException("A credential origin must be scheme://host[:port]");
    }

    private static BaseUrl parseBaseUrl(String text) {
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException invalid) {
            throw invalidBaseUrl();
        }
        String scheme = uri.getScheme();
        boolean secure = "https".equalsIgnoreCase(scheme);
        if (!secure && !"http".equalsIgnoreCase(scheme)
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw invalidBaseUrl();
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : secure ? 443 : 80;
        return new BaseUrl(uri.getHost(), port, secure, path);
    }

    private static HttpClientConfigurationException invalidBaseUrl() {
        return new HttpClientConfigurationException(
                "The base URL must be an http or https URL with a host and without user information, query or fragment");
    }

    private record BaseUrl(String host, int port, boolean secure, String path) {}
}
