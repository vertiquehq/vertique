// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.middleware;

import dev.vertique.rest.core.config.DefaultHeadersConfig;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;

/**
 * ROOT-scoped middleware that sets configurable security and cache-control headers on all responses.
 *
 * <p>Header values are driven by {@link DefaultHeadersConfig}, which allows applications to
 * override the defaults and add arbitrary custom headers from the {@code "jaxrs.defaultHeaders"}
 * config section. Known built-in headers and their defaults:
 * <ul>
 *   <li>{@code Cache-Control: no-store} — prevents caching of API responses</li>
 *   <li>{@code X-Content-Type-Options: nosniff} — prevents MIME type sniffing</li>
 *   <li>{@code X-Frame-Options: DENY} — prevents clickjacking</li>
 * </ul>
 *
 * <p>Setting a known field to {@code null} or blank in the config suppresses the corresponding
 * header. Additional custom headers are applied after the built-in set.
 *
 * <p>Priority: 10 (APPLICATION phase; runs after contextual logging, priority 0)
 */
public class DefaultHeadersMiddleware implements Middleware {

    private final Map<String, String> headers;

    /**
     * Creates a middleware instance with the given header configuration.
     *
     * @param config the header configuration supplying which headers to set; must not be {@code null}
     */
    public DefaultHeadersMiddleware(DefaultHeadersConfig config) {
        this.headers = config.toHeaderMap();
    }

    /**
     * Returns the execution priority for this middleware.
     *
     * @return {@code 10} — runs after contextual logging ({@code 0}) in the APPLICATION phase
     */
    @Override
    public int priority() {
        return 10;
    }

    @Override
    public MiddlewareScope scope() {
        return MiddlewareScope.ROOT;
    }

    @Override
    public void handle(RoutingContext ctx) {
        var response = ctx.response();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            response.putHeader(entry.getKey(), entry.getValue());
        }
        ctx.next();
    }
}
