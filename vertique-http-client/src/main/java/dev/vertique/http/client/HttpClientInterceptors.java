// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.core.async.Combinators;
import dev.vertique.core.extension.ObserverFailureReporter;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.http.client.interceptor.HttpClientAttemptCompletion;
import dev.vertique.http.client.interceptor.HttpClientAttemptTarget;
import dev.vertique.http.client.interceptor.HttpClientInterceptor;
import dev.vertique.http.client.interceptor.HttpClientRequestContext;
import dev.vertique.http.client.interceptor.HttpClientResponseHead;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.ext.web.client.HttpRequest;
import jakarta.annotation.Nullable;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The registered {@link HttpClientInterceptor}s of one client in {@link OrderedExtension} order, with
 * the two things the client does with them: the {@code beforeRequest} fold and the isolated
 * {@code onAttemptCompleted} notification.
 *
 * <p>It also turns a Vert.x request into the context and target the interceptors see and applies a
 * changed URI back to the request.
 */
final class HttpClientInterceptors {

    /** Failure message of an interceptor that returned no request context. */
    static final String NO_CONTEXT = "An interceptor returned no request context";

    /** Failure message of a request URI an interceptor changed to one the client cannot use. */
    static final String INVALID_URI = "An interceptor returned a request URI the client cannot use";

    private static final Logger LOG = LoggerFactory.getLogger(HttpClientInterceptors.class);

    /** One reporter for every client, so a repeated {@code LinkageError} is rate limited across exchanges. */
    private static final ObserverFailureReporter REPORTER = new ObserverFailureReporter(LOG, "HTTP client interceptor");

    private static final String ON_ATTEMPT_COMPLETED = "onAttemptCompleted";
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;

    private final List<HttpClientInterceptor> ordered;
    private final String clientName;
    private final boolean defaultSecure;

    private HttpClientInterceptors(List<HttpClientInterceptor> ordered, String clientName, boolean defaultSecure) {
        this.ordered = ordered;
        this.clientName = clientName;
        this.defaultSecure = defaultSecure;
    }

    /**
     * Orders the registered interceptors.
     *
     * @param registered the interceptors in registration order
     * @param clientName the client name framework identity carries
     * @param defaultSecure whether a request that does not choose a scheme uses TLS
     * @return the ordered interceptors
     */
    static HttpClientInterceptors of(List<HttpClientInterceptor> registered, String clientName, boolean defaultSecure) {
        List<HttpClientInterceptor> sorted = new ArrayList<>(registered);
        sorted.sort(OrderedExtension.comparator());
        return new HttpClientInterceptors(List.copyOf(sorted), clientName, defaultSecure);
    }

    boolean isEmpty() {
        return ordered.isEmpty();
    }

    // --- preparation ---

    /**
     * Describes a request the way the interceptors see it, with the framework's identity.
     *
     * @param request the request as it stands
     * @param callId the id of the call
     * @return the context
     */
    HttpClientRequestContext describe(HttpRequest<?> request, String callId) {
        return new HttpClientRequestContext(
                request.method().name(), uriOf(request), request.headers(), clientName, callId, Map.of());
    }

    /**
     * Folds the context through every {@code beforeRequest} in order. The identity of each result is
     * the framework's, whatever the interceptor returned. The first failure stops the fold.
     *
     * @param seed the context the first interceptor receives
     * @return the final context, or the first failure
     */
    Future<HttpClientRequestContext> prepare(HttpClientRequestContext seed) {
        return Combinators.foldSequential(ordered, seed, (interceptor, current) -> {
            Future<HttpClientRequestContext> next = interceptor.beforeRequest(current);
            if (next == null) {
                return Future.failedFuture(new IllegalStateException(NO_CONTEXT));
            }
            return next.compose(result -> {
                if (result == null) {
                    return Future.failedFuture(new IllegalStateException(NO_CONTEXT));
                }
                return Future.succeededFuture(new HttpClientRequestContext(
                        seed.method(),
                        result.uri(),
                        result.headers(),
                        seed.clientName(),
                        seed.callId(),
                        result.attributes()));
            });
        });
    }

    // --- completion ---

    /**
     * Calls {@code onAttemptCompleted} of every interceptor. A throwable of the kinds the observation
     * contract names is reported and swallowed per interceptor, so the next one still runs; each
     * interceptor gets its own copy of the snapshot and of the response head.
     */
    void completed(
            HttpClientRequestContext request,
            @Nullable HttpClientResponseHead response,
            HttpClientAttemptCompletion completion) {
        for (HttpClientInterceptor interceptor : ordered) {
            try {
                HttpClientResponseHead own = response == null
                        ? null
                        : new HttpClientResponseHead(
                                response.statusCode(), response.statusMessage(), response.headers());
                interceptor.onAttemptCompleted(request.withHeaders(request.headers()), own, completion);
            } catch (Exception | LinkageError | AssertionError | StackOverflowError failure) {
                REPORTER.report(interceptor.getClass(), ON_ATTEMPT_COMPLETED, failure);
            }
        }
    }

    // --- request views ---

    /**
     * Returns the target of a request: scheme, host and port, or the parts the request does not know.
     */
    HttpClientAttemptTarget targetOf(HttpRequest<?> request) {
        return target(request.ssl(), request.host(), request.port());
    }

    /**
     * Returns the target of a hop from the options Vert.x connects with; a scheme that was not chosen is
     * the client's default.
     */
    HttpClientAttemptTarget target(@Nullable Boolean ssl, @Nullable String host, @Nullable Integer port) {
        if (host == null) {
            return new HttpClientAttemptTarget(null, null, -1);
        }
        boolean secure = ssl != null ? ssl : defaultSecure;
        int resolved = port == null || port <= 0 ? secure ? HTTPS_PORT : HTTP_PORT : port;
        return new HttpClientAttemptTarget(secure ? "https" : "http", host, resolved);
    }

    /** The URI of a request as the interceptors see it: absolute when the request knows its host. */
    private String uriOf(HttpRequest<?> request) {
        String raw = request.uri() == null ? "" : request.uri();
        String query = "";
        if (raw.indexOf('?') < 0) {
            MultiMap params = request.queryParams();
            if (!params.isEmpty()) {
                query = "?" + encode(params);
            }
        }
        String host = request.host();
        if (host == null) {
            return raw + query;
        }
        HttpClientAttemptTarget target = targetOf(request);
        String literal = host.indexOf(':') >= 0 && host.charAt(0) != '[' ? "[" + host + "]" : host;
        String separator = raw.isEmpty() || raw.charAt(0) == '/' || raw.charAt(0) == '?' ? "" : "/";
        return target.scheme() + "://" + literal + ":" + target.port() + separator + raw + query;
    }

    private static String encode(MultiMap params) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> param : params) {
            if (text.length() > 0) {
                text.append('&');
            }
            text.append(URLEncoder.encode(param.getKey(), StandardCharsets.UTF_8));
            if (param.getValue() != null) {
                text.append('=').append(URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8));
            }
        }
        return text.toString();
    }

    /**
     * Applies a URI an interceptor changed to the request: an absolute {@code http} or {@code https} URI
     * replaces scheme, host, port and path, a relative one replaces the path. User information is
     * handed to the egress check as the refusal it is.
     *
     * @param request the request to change
     * @param changed the new URI
     * @throws IllegalArgumentException with a constant message when the URI cannot be used
     */
    static void applyUri(HttpRequest<?> request, String changed) {
        URI parsed;
        try {
            parsed = URI.create(EgressInterceptor.guardUserInfo(changed));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException(INVALID_URI);
        }
        if (parsed.getScheme() == null) {
            request.uri(changed);
            return;
        }
        String scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
        String host = parsed.getHost();
        boolean secure = "https".equals(scheme);
        if (!secure && !"http".equals(scheme) || host == null) {
            throw new IllegalArgumentException(INVALID_URI);
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int port = parsed.getPort() >= 0 ? parsed.getPort() : secure ? HTTPS_PORT : HTTP_PORT;
        String path = parsed.getRawPath() == null || parsed.getRawPath().isEmpty() ? "/" : parsed.getRawPath();
        String target = parsed.getRawQuery() == null ? path : path + "?" + parsed.getRawQuery();
        request.ssl(secure).host(host).port(port).uri(target);
    }
}
