// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.internal;

import dev.vertique.http.client.exception.HttpEgressDeniedException;
import dev.vertique.http.client.exception.HttpEgressDeniedException.Reason;
import io.vertx.core.Handler;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.Address;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.client.impl.ClientPhase;
import io.vertx.ext.web.client.impl.HttpContext;
import java.net.URI;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * INTERNAL, framework use only. Checks the target of every hop of every request before a connection
 * opens, on the options Vert.x will use to connect. It runs in the request-creation phase, so a request
 * and each redirect hop pass through it.
 *
 * <p>A domain-socket server address is always refused. When the client restricts hosts, a server address
 * whose host does not pass, a virtual host (including a caller-set {@code Host} header) and a
 * request-level proxy whose host does not pass are refused as well. Every refusal fails the exchange
 * once with an {@link HttpEgressDeniedException} and connects to nothing.
 */
public final class EgressInterceptor implements Handler<HttpContext<?>> {

    /**
     * Host a template request carries when its expanded absolute URI contained user information. The
     * HTTP client's URL parsing drops user information, so {@link #guardUserInfo(String)} replaces such
     * a URI with this placeholder, which this interceptor refuses before anything is dialed.
     */
    private static final String USER_INFO_HOST = "user-info-refused.invalid";

    private static final String HOST_HEADER = "Host";

    private final Consumer<URI> check;
    private final boolean restrictsHosts;
    private final boolean defaultSecure;

    /**
     * Creates the interceptor.
     *
     * @param check throws {@link HttpEgressDeniedException} for a target the policy refuses
     * @param restrictsHosts whether the policy lists hosts, which also makes server addresses, virtual
     *     hosts and request-level proxies subject to the list
     * @param defaultSecure whether a request that does not choose a scheme uses TLS
     */
    public EgressInterceptor(Consumer<URI> check, boolean restrictsHosts, boolean defaultSecure) {
        this.check = Objects.requireNonNull(check, "check");
        this.restrictsHosts = restrictsHosts;
        this.defaultSecure = defaultSecure;
    }

    /**
     * Replaces an expanded absolute URI that carries user information with a placeholder the interceptor
     * refuses; any other URI is returned unchanged.
     *
     * @param expanded the expanded URI text
     * @return {@code expanded}, or the placeholder URI when it has user information
     */
    public static String guardUserInfo(String expanded) {
        int scheme = expanded.indexOf("://");
        if (scheme < 0) {
            return expanded;
        }
        int start = scheme + 3;
        int end = start;
        while (end < expanded.length() && "/?#".indexOf(expanded.charAt(end)) < 0) {
            end++;
        }
        return expanded.substring(start, end).indexOf('@') >= 0 ? "http://" + USER_INFO_HOST + "/" : expanded;
    }

    @Override
    public void handle(HttpContext<?> ctx) {
        if (ctx.phase() != ClientPhase.CREATE_REQUEST || ctx.requestOptions() == null) {
            ctx.next();
            return;
        }
        try {
            verify(ctx, ctx.requestOptions());
        } catch (HttpEgressDeniedException denied) {
            ctx.fail(denied);
            return;
        }
        ctx.next();
    }

    private void verify(HttpContext<?> ctx, RequestOptions options) {
        if (USER_INFO_HOST.equals(options.getHost())) {
            throw new HttpEgressDeniedException(Reason.USER_INFO);
        }
        boolean secure = options.isSsl() != null ? options.isSsl() : defaultSecure;
        if (restrictsHosts && hasVirtualHost(ctx, options)) {
            throw new HttpEgressDeniedException(Reason.VIRTUAL_HOST);
        }
        Address server = options.getServer();
        if (server != null) {
            verifyServer(server, secure);
        }
        ProxyOptions proxy = options.getProxyOptions();
        if (restrictsHosts && proxy != null) {
            verifyHost(proxy.getHost(), true, Reason.PROXY);
        }
        // with a connect address the scheme rule is judged there; the URL host only has to be listed
        verifyHost(options.getHost(), server != null || secure, null);
    }

    private static boolean hasVirtualHost(HttpContext<?> ctx, RequestOptions options) {
        return ctx.request().virtualHost() != null
                || options.getHeaders() != null && options.getHeaders().contains(HOST_HEADER)
                || ctx.request().headers().contains(HOST_HEADER);
    }

    private void verifyServer(Address server, boolean secure) {
        if (!(server instanceof SocketAddress address) || !address.isInetSocket()) {
            throw new HttpEgressDeniedException(Reason.CONNECT_ADDRESS);
        }
        if (restrictsHosts) {
            verifyHost(address.host(), true, Reason.CONNECT_ADDRESS);
            // loopback is judged from the connect address: the scheme rule applies to its host
            verifyHost(address.host(), secure, null);
        }
    }

    /**
     * Checks a host through the policy; {@code reason} replaces the policy's reason when not null. A
     * server address or proxy is judged by its host alone, so those checks pass {@code secure} as true.
     */
    private void verifyHost(String host, boolean secure, Reason reason) {
        try {
            check.accept(targetOf(host, secure));
        } catch (HttpEgressDeniedException denied) {
            throw reason == null ? denied : new HttpEgressDeniedException(reason);
        }
    }

    private static URI targetOf(String host, boolean secure) {
        if (host == null || host.isEmpty()) {
            throw new HttpEgressDeniedException(Reason.NO_HOST);
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            // a character that would end or reshape the authority of the URI judged below
            if (c == '/' || c == '?' || c == '#' || c == '\\' || Character.isWhitespace(c)) {
                throw new HttpEgressDeniedException(Reason.HOST);
            }
        }
        String literal = host.indexOf(':') >= 0 && host.charAt(0) != '[' ? "[" + host + "]" : host;
        try {
            return URI.create((secure ? "https://" : "http://") + literal + "/");
        } catch (IllegalArgumentException malformed) {
            throw new HttpEgressDeniedException(Reason.HOST);
        }
    }
}
