// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpClientConfigurationException;
import dev.vertique.http.client.internal.FrameworkClientOptions;
import dev.vertique.http.client.internal.RedirectRules;
import dev.vertique.resilience.Resilience;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientBuilder;
import io.vertx.core.http.PoolOptions;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Builds {@link VertiqueHttpClient} instances.
 *
 * <p>Setters take effect at {@link #build()}: each build snapshots the builder's current values, so
 * later changes to the builder do not reach clients already built, and every build creates its own
 * HTTP agent. {@link WebClientOptions} and {@link PoolOptions} are copied when they are set. A build
 * that is rejected leaves the builder usable once the setting is corrected.
 */
public final class VertiqueHttpClientBuilder {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final String DEFAULT_NAME = "default";
    private static final long DEFAULT_READ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);

    private static final long DEFAULT_MAX_RESPONSE_BYTES = 10_485_760L;

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
    private WebClientOptions webClientOptions;
    private PoolOptions poolOptions;
    private final Map<String, String> defaultHeaders = new LinkedHashMap<>();
    private boolean validateHeaders = true;
    private EgressPolicy egress = EgressPolicy.allowAll();

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
        if (readTimeoutMs < 0) {
            throw new HttpClientConfigurationException("The read timeout must not be negative");
        }
        if (maxRequestBytes < 0 || maxResponseBytes < 0) {
            throw new HttpClientConfigurationException("A body byte bound must not be negative");
        }
        Map<String, String> headers = validatedDefaultHeaders();
        BaseUrl base = baseUrl == null ? null : parseBaseUrl(baseUrl);

        WebClientOptions effective = webClientOptions == null
                ? new WebClientOptions().setFollowRedirects(false)
                : new WebClientOptions(webClientOptions);
        if (base != null) {
            effective.setDefaultHost(base.host()).setDefaultPort(base.port()).setSsl(base.secure());
        }
        ClientSettings settings = new ClientSettings(
                base == null ? "" : base.path(),
                base != null,
                validateHeaders,
                headers,
                readTimeoutMs,
                maxRequestBytes,
                maxResponseBytes,
                egress);

        HttpClientBuilder agentBuilder =
                vertx.httpClientBuilder().with(effective).withRedirectHandler(new RedirectRules());
        if (poolOptions != null) {
            agentBuilder.with(new PoolOptions(poolOptions));
        }
        HttpClientAgent agent = agentBuilder.build();
        try {
            VertiqueHttpClient client = new DefaultVertiqueHttpClient(vertx, name, agent, effective, settings);
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

    private Map<String, String> validatedDefaultHeaders() {
        for (Map.Entry<String, String> header : defaultHeaders.entrySet()) {
            if (HeaderRules.violation(header.getKey(), header.getValue()) != null) {
                throw new HttpClientConfigurationException("A default header is not acceptable");
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(defaultHeaders));
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
