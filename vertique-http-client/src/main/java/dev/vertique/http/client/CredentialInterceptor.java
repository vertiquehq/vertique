// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpCredentialException;
import dev.vertique.http.client.exception.HttpEgressDeniedException;
import dev.vertique.http.client.exception.HttpEgressDeniedException.Reason;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.internal.FutureInternal;
import io.vertx.ext.web.client.impl.ClientPhase;
import io.vertx.ext.web.client.impl.HttpContext;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * INTERNAL, framework use only. Applies the client's {@link CredentialSource} to the hops of an exchange
 * that go to the original origin, and refuses plain HTTP to a non-loopback host before connecting.
 *
 * <p>In the request-creation phase it fails a hop to plain HTTP on a non-loopback host unless the client
 * allows that. In the send phase, which runs once per hop after the request was prepared and the
 * connection chosen, it first re-applies the redirect header safelist when the hop changed origin, so a
 * header added after the redirect handler ran does not travel to another origin. It then resolves the
 * source and replaces the request's header of each returned name, but only while every hop of the
 * exchange so far was to the original origin, which is the origin of the client's base URL or, without a
 * base URL, of the exchange's first hop, widened by the configured origins. A hop with a server
 * address, a virtual host (including a caller-set {@code Host} header) or a request-level proxy never
 * gets credentials.
 *
 * <p>A source failure, a null result and an invalid header name or value fail the exchange once with an
 * {@link HttpCredentialException} and send nothing. The returned values are never put into a message or
 * a log. Per-exchange state lives in the {@link HttpContext}, never on this instance.
 */
final class CredentialInterceptor implements Handler<HttpContext<?>> {

    private static final String FIRST_ORIGIN = "vertique.credentials.first";
    private static final String LAST_ORIGIN = "vertique.credentials.last";
    private static final String CHAIN_LEFT = "vertique.credentials.left";

    private static final String HOST_HEADER = "Host";

    private static final Set<String> TRANSPORT_OWNED =
            Set.of("host", "content-length", "transfer-encoding", "connection", "upgrade", "te");

    private static final Set<String> KEPT_ON_ANOTHER_ORIGIN = Set.of(
            "accept",
            "accept-language",
            "accept-encoding",
            "user-agent",
            "cache-control",
            "pragma",
            "if-none-match",
            "if-modified-since",
            "host",
            "connection",
            "content-length");

    private final CredentialSource source;
    private final boolean allowPlainHttp;
    private final Set<String> originsAllowed;
    private final boolean hasBaseUrl;
    private final boolean defaultSecure;
    private final String defaultHost;
    private final int defaultPort;
    private final Predicate<String> loopback;

    /**
     * Creates the interceptor.
     *
     * @param source the credential source
     * @param allowPlainHttp whether credentials may be used over plain HTTP to a non-loopback host
     * @param originsAllowed the origins, in the {@link #origin(boolean, String, int)} form, credentials
     *     may go to
     * @param hasBaseUrl whether the client has a base URL; without one the first hop's origin is the
     *     original origin too
     * @param defaultSecure whether a request that does not choose a scheme uses TLS
     * @param defaultHost the host of a request that does not name one, or null
     * @param defaultPort the port of a request that does not name one
     * @param loopback tells whether a host name or IP literal is loopback
     */
    CredentialInterceptor(
            CredentialSource source,
            boolean allowPlainHttp,
            Set<String> originsAllowed,
            boolean hasBaseUrl,
            boolean defaultSecure,
            String defaultHost,
            int defaultPort,
            Predicate<String> loopback) {
        this.source = Objects.requireNonNull(source, "source");
        this.allowPlainHttp = allowPlainHttp;
        this.originsAllowed = Set.copyOf(originsAllowed);
        this.hasBaseUrl = hasBaseUrl;
        this.defaultSecure = defaultSecure;
        this.defaultHost = defaultHost;
        this.defaultPort = defaultPort;
        this.loopback = Objects.requireNonNull(loopback, "loopback");
    }

    /**
     * Returns the comparable form of an origin: lower-case scheme and host without a trailing dot, and the
     * port.
     *
     * @param secure whether the scheme is {@code https}
     * @param host the host name or IP literal
     * @param port the port
     * @return {@code scheme://host:port}
     */
    static String origin(boolean secure, String host, int port) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".")) {
            lower = lower.substring(0, lower.length() - 1);
        }
        if (lower.indexOf(':') >= 0 && !lower.startsWith("[")) {
            lower = "[" + lower + "]";
        }
        return (secure ? "https" : "http") + "://" + lower + ":" + port;
    }

    @Override
    public void handle(HttpContext<?> ctx) {
        ClientPhase phase = ctx.phase();
        if (phase == ClientPhase.CREATE_REQUEST && ctx.requestOptions() != null) {
            if (refusesPlainHttp(ctx.requestOptions())) {
                ctx.fail(new HttpEgressDeniedException(Reason.PLAIN_HTTP_CREDENTIALS));
                return;
            }
        } else if (phase == ClientPhase.SEND_REQUEST && ctx.requestOptions() != null && ctx.clientRequest() != null) {
            send(ctx);
            return;
        }
        ctx.next();
    }

    private boolean refusesPlainHttp(RequestOptions options) {
        if (allowPlainHttp || secure(options)) {
            return false;
        }
        String host = options.getServer() instanceof io.vertx.core.net.SocketAddress address && address.isInetSocket()
                ? address.host()
                : hostOf(options);
        return host == null || !loopback.test(host);
    }

    private boolean secure(RequestOptions options) {
        return options.isSsl() != null ? options.isSsl() : defaultSecure;
    }

    private String hostOf(RequestOptions options) {
        return options.getHost() != null ? options.getHost() : defaultHost;
    }

    private String originOf(RequestOptions options) {
        String host = hostOf(options);
        if (host == null) {
            return "";
        }
        boolean secure = secure(options);
        int port = options.getPort() != null ? options.getPort() : defaultPort;
        return origin(secure, host, port);
    }

    private void send(HttpContext<?> ctx) {
        RequestOptions options = ctx.requestOptions();
        HttpClientRequest request = ctx.clientRequest();
        String hop = originOf(options);
        String first = ctx.get(FIRST_ORIGIN);
        boolean left;
        if (first == null) {
            first = hop;
            ctx.set(FIRST_ORIGIN, hop);
            left = false;
        } else {
            left = Boolean.TRUE.equals(ctx.get(CHAIN_LEFT));
            if (!hop.equals(ctx.get(LAST_ORIGIN))) {
                retainSafelisted(request.headers());
            }
        }
        // the chain leaves the original origins at the first hop outside them, and never comes back
        left = left || !(originsAllowed.contains(hop) || !hasBaseUrl && hop.equals(first));
        ctx.set(LAST_ORIGIN, hop);
        left = left || hasOwnTransportTarget(ctx, options);
        ctx.set(CHAIN_LEFT, left);
        if (left) {
            ctx.next();
            return;
        }
        Future<Map<String, String>> resolved;
        try {
            resolved = source.resolve(URI.create(request.absoluteURI()));
        } catch (RuntimeException failure) {
            fail(ctx, request, failure);
            return;
        }
        if (resolved == null) {
            fail(ctx, request, null);
            return;
        }
        Context owner = ctx.future() instanceof FutureInternal<?> future ? future.context() : null;
        resolved.onComplete(result -> {
            if (owner != null && Vertx.currentContext() != owner) {
                owner.runOnContext(ignored -> apply(ctx, request, result));
            } else {
                apply(ctx, request, result);
            }
        });
    }

    private void apply(
            HttpContext<?> ctx, HttpClientRequest request, io.vertx.core.AsyncResult<Map<String, String>> result) {
        if (ctx.future().isComplete()) {
            return;
        }
        if (result.failed()) {
            fail(ctx, request, result.cause());
            return;
        }
        Map<String, String> headers;
        try {
            Map<String, String> returned = result.result();
            headers = returned == null ? null : new LinkedHashMap<>(returned);
        } catch (RuntimeException failure) {
            fail(ctx, request, failure);
            return;
        }
        if (headers == null || !valid(headers)) {
            fail(ctx, request, null);
            return;
        }
        MultiMap target = request.headers();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            target.set(header.getKey(), header.getValue());
        }
        ctx.next();
    }

    private static boolean valid(Map<String, String> headers) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey();
            String value = header.getValue();
            if (!isToken(name) || TRANSPORT_OWNED.contains(name.toLowerCase(Locale.ROOT)) || !isValue(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isToken(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean token = c >= 'a' && c <= 'z'
                    || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9'
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!token) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValue(String value) {
        if (value == null) {
            return false;
        }
        if (!value.isEmpty()
                && (Character.isWhitespace(value.charAt(0))
                        || Character.isWhitespace(value.charAt(value.length() - 1)))) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether the hop names its own connect address, virtual host or proxy, so no credentials go with it. */
    private static boolean hasOwnTransportTarget(HttpContext<?> ctx, RequestOptions options) {
        return options.getServer() != null
                || options.getProxyOptions() != null
                || ctx.request().virtualHost() != null
                || options.getHeaders() != null && options.getHeaders().contains(HOST_HEADER)
                || ctx.request().headers().contains(HOST_HEADER);
    }

    private static void retainSafelisted(MultiMap headers) {
        for (String name : new ArrayList<>(headers.names())) {
            if (!KEPT_ON_ANOTHER_ORIGIN.contains(name.toLowerCase(Locale.ROOT))) {
                headers.remove(name);
            }
        }
    }

    /** Fails the exchange once with a credential failure that keeps only the cause's class name. */
    private static void fail(HttpContext<?> ctx, HttpClientRequest request, Throwable cause) {
        HttpCredentialException failure = new HttpCredentialException(cause);
        // the reset releases the connection and nothing was written; the context is failed only when the
        // reset did not already do so, so the failure is dispatched once
        request.reset(0, failure);
        if (!ctx.future().isComplete()) {
            ctx.fail(failure);
        }
    }
}
