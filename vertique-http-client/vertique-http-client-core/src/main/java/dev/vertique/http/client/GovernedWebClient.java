// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.impl.HttpContext;
import io.vertx.uritemplate.ExpandOptions;
import io.vertx.uritemplate.UriTemplate;
import io.vertx.uritemplate.Variables;

/**
 * The {@code WebClient} the client hands out: a delegate over a governed Vert.x {@code WebClient}.
 * Relative requests to the default host get the base path prefixed, {@code close()} does nothing and
 * {@code addInterceptor} is refused. Absolute requests and requests that name their own host are
 * passed through unchanged.
 */
final class GovernedWebClient implements WebClient {

    /** Message of the exception {@link #addInterceptor(Handler)} throws. */
    static final String INTERCEPTOR_REFUSED = "Interceptors cannot be added to a web client handed out by the client";

    static final String TLS_UPDATE_REFUSED =
            "TLS options of a web client handed out by the client are set on the client builder";

    private final WebClient delegate;
    private final String basePath;
    private final boolean hasBaseUrl;

    GovernedWebClient(WebClient delegate, ClientSettings settings) {
        this.delegate = delegate;
        this.basePath = settings.basePath();
        this.hasBaseUrl = settings.hasBaseUrl();
    }

    // --- requests with an explicit target: unchanged ---

    @Override
    public HttpRequest<Buffer> request(
            HttpMethod method, SocketAddress serverAddress, int port, String host, String requestURI) {
        return delegate.request(method, serverAddress, port, host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> request(
            HttpMethod method, SocketAddress serverAddress, int port, String host, UriTemplate requestURI) {
        return delegate.request(method, serverAddress, port, host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, SocketAddress serverAddress, String host, String requestURI) {
        return delegate.request(method, serverAddress, host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> request(
            HttpMethod method, SocketAddress serverAddress, String host, UriTemplate requestURI) {
        return delegate.request(method, serverAddress, host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> requestAbs(HttpMethod method, SocketAddress serverAddress, String absoluteURI) {
        return delegate.requestAbs(method, serverAddress, absoluteURI);
    }

    @Override
    public HttpRequest<Buffer> requestAbs(HttpMethod method, SocketAddress serverAddress, UriTemplate absoluteURI) {
        return delegate.requestAbs(method, serverAddress, absoluteURI);
    }

    // --- relative requests to the default host: base path or refusal ---

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, SocketAddress serverAddress, String requestURI) {
        if (!hasBaseUrl) {
            return delegate.request(method, serverAddress, PrepareInterceptor.NO_BASE_HOST, requestURI);
        }
        return delegate.request(method, serverAddress, prefixed(requestURI));
    }

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, SocketAddress serverAddress, UriTemplate requestURI) {
        if (!hasBaseUrl) {
            return delegate.request(method, serverAddress, PrepareInterceptor.NO_BASE_HOST, requestURI);
        }
        return delegate.request(method, serverAddress, new PrefixedTemplate(requestURI));
    }

    @Override
    public HttpRequest<Buffer> request(RequestOptions options) {
        return delegate.request(relative(options));
    }

    @Override
    @SuppressWarnings("deprecation")
    public HttpRequest<Buffer> request(HttpMethod method, SocketAddress serverAddress, RequestOptions options) {
        return delegate.request(method, serverAddress, relative(options));
    }

    // --- lifecycle and extension: owned by the client ---

    @Override
    public WebClient addInterceptor(Handler<HttpContext<?>> interceptor) {
        throw new UnsupportedOperationException(INTERCEPTOR_REFUSED);
    }

    @Override
    public void close() {
        // the client owns the agent; closing a handed-out web client leaves the client usable
    }

    // --- TLS: shared by every call of the client and set on its builder ---

    @Override
    public Future<Boolean> updateSSLOptions(ClientSSLOptions options, boolean force) {
        throw new UnsupportedOperationException(TLS_UPDATE_REFUSED);
    }

    // --- helpers ---

    private RequestOptions relative(RequestOptions options) {
        if (options == null || options.getHost() != null) {
            return options;
        }
        RequestOptions copy = new RequestOptions(options);
        if (hasBaseUrl) {
            copy.setURI(prefixed(options.getURI()));
        } else {
            copy.setHost(PrepareInterceptor.NO_BASE_HOST);
        }
        return copy;
    }

    private String prefixed(String requestURI) {
        if (basePath.isEmpty()) {
            return requestURI;
        }
        if (requestURI == null || requestURI.isEmpty() || requestURI.charAt(0) == '?') {
            return basePath + (requestURI == null ? "" : requestURI);
        }
        return requestURI.charAt(0) == '/' ? basePath + requestURI : basePath + '/' + requestURI;
    }

    /** A URI template whose expansion gets the base path prefixed. */
    private final class PrefixedTemplate implements UriTemplate {

        private final UriTemplate template;

        private PrefixedTemplate(UriTemplate template) {
            this.template = template;
        }

        @Override
        public String expandToString(Variables variables) {
            return prefixed(template.expandToString(variables));
        }

        @Override
        public String expandToString(Variables variables, ExpandOptions options) {
            return prefixed(template.expandToString(variables, options));
        }
    }
}
