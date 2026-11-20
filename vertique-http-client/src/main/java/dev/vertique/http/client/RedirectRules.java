// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpEgressDeniedException;
import dev.vertique.http.client.exception.HttpEgressDeniedException.Reason;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.impl.DefaultRedirectHandler;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * INTERNAL, framework use only. The redirect handler of the client's HTTP agent.
 *
 * <p>Method handling and the redirect limit are Vert.x's own: a 303 becomes a {@code GET} without a
 * body, and 301, 302, 307 and 308 are followed only for {@code GET}, {@code HEAD} and {@code QUERY}.
 * On top of that:
 *
 * <ul>
 *   <li>a redirect {@code Location} with user information is refused with {@code USER_INFO};
 *   <li>a hop from {@code https} to {@code http} is refused with {@code SCHEME};
 *   <li>a 307 or 308 that would resend a request body to another origin is not followed, so the caller
 *       receives the redirect response;
 *   <li>a hop to another origin (scheme, host or port) forwards only the safelisted request headers;
 *       a hop to the same origin keeps the request headers except {@code Cookie} and {@code
 *       Content-Length}, as Vert.x does.
 * </ul>
 *
 * <p>The host of each hop is checked afterwards, by {@link EgressInterceptor}.
 */
final class RedirectRules implements Function<HttpClientResponse, Future<RequestOptions>> {

    private static final Set<String> SAFELISTED = Set.of(
            "accept",
            "accept-language",
            "accept-encoding",
            "user-agent",
            "cache-control",
            "pragma",
            "if-none-match",
            "if-modified-since");

    private static final List<String> SAME_ORIGIN_STRIPPED = List.of("cookie", "content-length");

    // Header stripping is done here on a copy; the Vert.x handler would change the request's own headers.
    private final DefaultRedirectHandler vertxRules = new DefaultRedirectHandler(List.of(), List.of());

    @Override
    public Future<RequestOptions> apply(HttpClientResponse response) {
        Future<RequestOptions> next;
        try {
            next = vertxRules.apply(response);
        } catch (RuntimeException unreadable) {
            // The Vert.x rules failed on the Location: never carry its text
            return refuse(response.request(), Reason.HOST);
        }
        if (next != null && next.failed()) {
            return refuse(response.request(), Reason.HOST);
        }
        if (next == null || !next.succeeded()) {
            return next;
        }
        RequestOptions hop = next.result();
        HttpClientRequest request = response.request();
        URI origin;
        URI location;
        try {
            origin = URI.create(request.absoluteURI());
            location = origin.resolve(response.getHeader("Location"));
        } catch (RuntimeException unparsable) {
            // A redirect the checks cannot read is never followed: fail closed
            return refuse(request, Reason.HOST);
        }
        String rawAuthority = location.getRawAuthority();
        String authority = location.getAuthority();
        if (rawAuthority != null && rawAuthority.indexOf('@') >= 0
                || authority != null && authority.indexOf('@') >= 0) {
            return refuse(request, Reason.USER_INFO);
        }
        if (location.getHost() == null) {
            // A registry-based authority: Vert.x may read its host differently from the checks
            return refuse(request, Reason.HOST);
        }
        boolean secureOrigin = "https".equalsIgnoreCase(origin.getScheme());
        if (secureOrigin && !Boolean.TRUE.equals(hop.isSsl())) {
            return refuse(request, Reason.SCHEME);
        }
        boolean sameOrigin = sameOrigin(origin, hop);
        if (!sameOrigin && resendsBody(response, request)) {
            return null;
        }
        hop.setHeaders(filtered(hop.getHeaders(), sameOrigin));
        return next;
    }

    private static Future<RequestOptions> refuse(HttpClientRequest request, Reason reason) {
        HttpEgressDeniedException refusal = new HttpEgressDeniedException(reason);
        request.reset(0, refusal);
        return Future.failedFuture(refusal);
    }

    private static boolean resendsBody(HttpClientResponse response, HttpClientRequest request) {
        int status = response.statusCode();
        if (status != 307 && status != 308) {
            return false;
        }
        MultiMap headers = request.headers();
        if (headers.contains("Transfer-Encoding")) {
            return true;
        }
        String length = headers.get("Content-Length");
        if (length == null) {
            return false;
        }
        try {
            return Long.parseLong(length.trim()) > 0;
        } catch (NumberFormatException invalid) {
            return true;
        }
    }

    private static boolean sameOrigin(URI origin, RequestOptions hop) {
        boolean secure = "https".equalsIgnoreCase(origin.getScheme());
        int port = origin.getPort() >= 0 ? origin.getPort() : secure ? 443 : 80;
        return secure == Boolean.TRUE.equals(hop.isSsl())
                && normalizedHost(origin.getHost()).equals(normalizedHost(hop.getHost()))
                && hop.getPort() != null
                && hop.getPort() == port;
    }

    private static String normalizedHost(String host) {
        if (host == null) {
            return "";
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    private static MultiMap filtered(MultiMap headers, boolean sameOrigin) {
        MultiMap copy = MultiMap.caseInsensitiveMultiMap();
        if (headers == null) {
            return copy;
        }
        for (String name : headers.names()) {
            String key = name.toLowerCase(Locale.ROOT);
            boolean keep = sameOrigin ? !SAME_ORIGIN_STRIPPED.contains(key) : SAFELISTED.contains(key);
            if (keep) {
                copy.add(name, headers.getAll(name));
            }
        }
        return copy;
    }
}
