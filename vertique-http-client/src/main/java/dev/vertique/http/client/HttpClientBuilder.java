// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.config.HttpClientConfig;
import dev.vertique.http.client.config.HttpClientEgressConfig;
import dev.vertique.http.client.config.HttpClientPoolConfig;
import dev.vertique.http.client.exception.HttpClientConfigurationException;
import dev.vertique.http.client.interceptor.HttpClientInterceptor;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds {@link HttpClient} instances.
 *
 * <p>Setters take effect at {@link #build()}: each build snapshots the builder's current values, so
 * later changes to the builder do not reach clients already built, and every build creates its own
 * HTTP agent. {@link WebClientOptions} and {@link PoolOptions} are copied when they are set. A build
 * that is rejected leaves the builder usable once the setting is corrected.
 */
public final class HttpClientBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(HttpClientBuilder.class);
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final String DEFAULT_NAME = "default";
    private static final long DEFAULT_READ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);

    private static final long DEFAULT_MAX_RESPONSE_BYTES = 10_485_760L;
    private static final int DEFAULT_MAX_EVENT_BYTES = 1_048_576;
    private static final long DEFAULT_STREAM_IDLE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(60);

    private final Vertx vertx;
    private Consumer<HttpClient> onBuilt = client -> {};

    private String name = DEFAULT_NAME;
    private BaseUrl base;
    private long readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
    private long maxRequestBytes;
    private long maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
    private long streamIdleTimeoutMs = DEFAULT_STREAM_IDLE_TIMEOUT_MS;
    private int maxEventBytes = DEFAULT_MAX_EVENT_BYTES;
    private WebClientOptions webClientOptions;
    private PoolOptions poolOptions;
    private final Map<String, String> defaultHeaders = new LinkedHashMap<>();
    private EgressPolicy egress = EgressPolicy.allowAll();
    private CredentialSource credentials;
    private boolean allowCredentialsOverPlainHttp;
    private final List<HttpClientInterceptor> interceptors = new ArrayList<>();
    private HttpClientConfig config;

    private HttpClientBuilder(Vertx vertx) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
    }

    private HttpClientBuilder() {
        this.vertx = null;
    }

    /**
     * Applies a configuration record through the setters onto a fresh builder, creating no agent and no
     * client, so a value the builder rejects fails at once.
     *
     * @param external the parsed {@code httpClient.{name}} section
     * @throws HttpClientConfigurationException with a constant message naming the key path
     */
    static void validate(HttpClientConfig external) {
        new HttpClientBuilder().apply(external);
    }

    /**
     * Creates a builder.
     *
     * @param vertx the Vert.x instance the clients run on
     * @return a new builder
     */
    public static HttpClientBuilder create(Vertx vertx) {
        return new HttpClientBuilder(vertx);
    }

    /**
     * Sets the client name, which must match {@code [A-Za-z0-9._~-]{1,128}}. The default is
     * {@code "default"}. An invalid name is rejected by {@link #build()}.
     *
     * @param name the client name
     * @return this builder
     */
    public synchronized HttpClientBuilder name(String name) {
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
     * @throws HttpClientConfigurationException when the URL is not acceptable
     */
    public synchronized HttpClientBuilder baseUrl(String baseUrl) {
        this.base = parseBaseUrl(Objects.requireNonNull(baseUrl, "baseUrl"));
        return this;
    }

    /**
     * Sets the default attempt timeout. The default is 30 seconds; {@code 0} means no attempt timer.
     *
     * @param timeout the timeout, not negative
     * @param unit the unit of {@code timeout}
     * @return this builder
     */
    public synchronized HttpClientBuilder readTimeout(long timeout, TimeUnit unit) {
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
    public synchronized HttpClientBuilder streamIdleTimeout(long timeout, TimeUnit unit) {
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
    public synchronized HttpClientBuilder maxRequestBytes(long bytes) {
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
    public synchronized HttpClientBuilder maxResponseBytes(long bytes) {
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
    public synchronized HttpClientBuilder maxEventBytes(int bytes) {
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
    public synchronized HttpClientBuilder webClientOptions(WebClientOptions options) {
        this.webClientOptions = new WebClientOptions(Objects.requireNonNull(options, "options"));
        return this;
    }

    /**
     * Sets the connection pool options. They are copied.
     *
     * @param options the options to copy
     * @return this builder
     */
    public synchronized HttpClientBuilder poolOptions(PoolOptions options) {
        this.poolOptions = new PoolOptions(Objects.requireNonNull(options, "options"));
        return this;
    }

    /**
     * Adds a header to every request that does not set it. A second call with the same name replaces
     * the value. The name must be a token that the transport does not own ({@code Host},
     * {@code Content-Length}, {@code Transfer-Encoding}, {@code Connection}, {@code Upgrade},
     * {@code TE}), and neither name nor value may hold a control character.
     *
     * @param name the header name
     * @param value the header value
     * @return this builder
     * @throws HttpClientConfigurationException when the header is not acceptable
     */
    public synchronized HttpClientBuilder defaultHeader(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (!HeaderRules.isToken(name)
                || HeaderRules.isTransportOwned(name)
                || HeaderRules.hasControlCharacter(value)) {
            throw new HttpClientConfigurationException("A default header is not acceptable");
        }
        defaultHeaders.put(name, value);
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
    public synchronized HttpClientBuilder egress(EgressPolicy policy) {
        this.egress = Objects.requireNonNull(policy, "policy");
        return this;
    }

    /**
     * Sets the source of per-exchange credential headers. The client resolves it once for every hop of
     * an exchange to the original origin, which is the origin of the base URL (or, without a base URL,
     * of the exchange's first hop), and never for a hop to another origin or after the exchange left the original origin. Each returned header replaces the
     * request's header of that name. Requests with a server address, a virtual host or a request-level
     * proxy get no credentials. Plain HTTP to a non-loopback host is refused unless
     * {@link #allowCredentialsOverPlainHttp(boolean)} says otherwise.
     *
     * @param source the credential source
     * @return this builder
     */
    public synchronized HttpClientBuilder credentials(CredentialSource source) {
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
    public synchronized HttpClientBuilder allowCredentialsOverPlainHttp(boolean allow) {
        this.allowCredentialsOverPlainHttp = allow;
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
    public synchronized HttpClientBuilder register(HttpClientInterceptor interceptor) {
        interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
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
    public synchronized HttpClientBuilder config(@Nullable HttpClientConfig config) {
        this.config = config;
        return this;
    }

    /**
     * Builds a client from the current settings.
     *
     * @return a new client with its own HTTP agent
     * @throws HttpClientConfigurationException when a setting is invalid
     */
    public synchronized HttpClient build() {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new HttpClientConfigurationException("The client name must match [A-Za-z0-9._~-]{1,128}");
        }
        return (config == null ? this : configured(config)).assemble();
    }

    /** Registers the owner told about every client this builder builds; the factory uses it to close them. */
    synchronized HttpClientBuilder onBuilt(Consumer<HttpClient> owner) {
        this.onBuilt = Objects.requireNonNull(owner, "owner");
        return this;
    }

    /**
     * A copy of this builder with every key of the configuration applied through the setters, so the
     * configuration wins over what was set and this builder keeps its own values.
     */
    private HttpClientBuilder configured(HttpClientConfig external) {
        HttpClientBuilder copy = new HttpClientBuilder(vertx);
        copy.name = name;
        copy.base = base;
        copy.readTimeoutMs = readTimeoutMs;
        copy.maxRequestBytes = maxRequestBytes;
        copy.maxResponseBytes = maxResponseBytes;
        copy.streamIdleTimeoutMs = streamIdleTimeoutMs;
        copy.maxEventBytes = maxEventBytes;
        copy.webClientOptions = webClientOptions;
        copy.poolOptions = poolOptions;
        copy.defaultHeaders.putAll(defaultHeaders);
        copy.egress = egress;
        copy.credentials = credentials;
        copy.allowCredentialsOverPlainHttp = allowCredentialsOverPlainHttp;
        copy.interceptors.addAll(interceptors);
        copy.onBuilt = onBuilt;
        copy.apply(external);
        return copy;
    }

    /** Applies the configured keys through the setters; a setter's failure names the key path only. */
    private void apply(HttpClientConfig external) {
        String path = "httpClient." + external.name() + ".";
        if (external.baseUrl() != null) {
            applied(
                    path + "baseUrl must be an http or https URL with a host and no user information, query or"
                            + " fragment",
                    () -> baseUrl(external.baseUrl()));
        }
        if (external.readTimeoutMs() != null) {
            readTimeout(external.readTimeoutMs(), TimeUnit.MILLISECONDS);
        }
        if (external.streamIdleTimeoutMs() != null) {
            streamIdleTimeout(external.streamIdleTimeoutMs(), TimeUnit.MILLISECONDS);
        }
        if (external.maxRequestBytes() != null) {
            maxRequestBytes(external.maxRequestBytes());
        }
        if (external.maxResponseBytes() != null) {
            maxResponseBytes(external.maxResponseBytes());
        }
        if (external.maxEventBytes() != null) {
            maxEventBytes(external.maxEventBytes());
        }
        if (external.allowCredentialsOverPlainHttp() != null) {
            allowCredentialsOverPlainHttp(external.allowCredentialsOverPlainHttp());
        }
        external.defaultHeaders()
                .forEach((headerName, value) -> applied(
                        path + "defaultHeaders holds a header that is not acceptable",
                        () -> defaultHeader(headerName, value)));
        HttpClientEgressConfig configuredEgress = external.egress();
        if (configuredEgress != null && configuredEgress.allowHosts() != null) {
            applied(
                    path + "egress.allowHosts must list hosts, *.suffix entries or *, and not be empty",
                    () -> egress(allowedHosts(configuredEgress)));
        }
        JsonObject bag = external.webClient();
        if (bag != null && !bag.isEmpty()) {
            applied(path + "webClient cannot be converted to WebClient options", () -> webClientOptions(merged(bag)));
        }
        if (external.pool() != null) {
            poolOptions(pool(external.pool()));
        }
    }

    /** Runs a setter for a configured value; any failure becomes a configuration failure with {@code message}. */
    private static void applied(String message, Runnable setter) {
        try {
            setter.run();
        } catch (RuntimeException failure) {
            throw new HttpClientConfigurationException(message);
        }
    }

    private static EgressPolicy allowedHosts(HttpClientEgressConfig configured) {
        if (configured.allowHosts().isEmpty()) {
            throw new IllegalArgumentException("empty");
        }
        return EgressPolicy.allowHosts(configured.allowHosts(), Boolean.TRUE.equals(configured.allowPlainHttp()));
    }

    /** The builder's web client options (or the default ones) with the configured bag merged over them. */
    private WebClientOptions merged(JsonObject bag) {
        WebClientOptions base = webClientOptions == null
                ? new WebClientOptions().setFollowRedirects(false)
                : new WebClientOptions(webClientOptions);
        JsonObject merged = base.toJson();
        merged.mergeIn(bag);
        return new WebClientOptions(merged);
    }

    /** The builder's pool options with the configured pool keys applied over them. */
    private PoolOptions pool(HttpClientPoolConfig pool) {
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

    /** Builds the client from this builder's own values. */
    private HttpClient assemble() {
        if (readTimeoutMs < 0) {
            throw new HttpClientConfigurationException("The read timeout must not be negative");
        }
        if (streamIdleTimeoutMs < 0) {
            throw new HttpClientConfigurationException("The stream idle timeout must not be negative");
        }
        if (maxRequestBytes < 0 || maxResponseBytes < 0) {
            throw new HttpClientConfigurationException("A body byte bound must not be negative");
        }
        if (maxEventBytes <= 0) {
            throw new HttpClientConfigurationException("The event byte bound must be above 0");
        }
        WebClientOptions effective = webClientOptions == null
                ? new WebClientOptions().setFollowRedirects(false)
                : new WebClientOptions(webClientOptions);
        if (base != null) {
            effective.setDefaultHost(base.host()).setDefaultPort(base.port()).setSsl(base.secure());
        }
        if (WebClientOptionsHardening.isInsecure(effective)) {
            LOG.warn("The HTTP client options trust all certificates, skip host verification or enable TLS"
                    + " below 1.2");
        }
        ClientSettings settings = new ClientSettings(
                base == null ? "" : base.path(),
                base != null,
                Collections.unmodifiableMap(new LinkedHashMap<>(defaultHeaders)),
                readTimeoutMs,
                maxRequestBytes,
                maxResponseBytes,
                streamIdleTimeoutMs,
                maxEventBytes,
                egress,
                credentials,
                allowCredentialsOverPlainHttp);

        io.vertx.core.http.HttpClientBuilder agentBuilder =
                vertx.httpClientBuilder().with(effective).withRedirectHandler(new RedirectRules());
        if (poolOptions != null) {
            agentBuilder.with(new PoolOptions(poolOptions));
        }
        HttpClientAgent agent = agentBuilder.build();
        try {
            HttpClient client = new HttpClient(vertx, name, agent, effective, settings, List.copyOf(interceptors));
            onBuilt.accept(client);
            return client;
        } catch (RuntimeException failure) {
            agent.close();
            throw failure;
        }
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
