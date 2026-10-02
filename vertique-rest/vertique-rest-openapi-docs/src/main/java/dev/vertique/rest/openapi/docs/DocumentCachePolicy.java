// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.security.ApiKey;
import dev.vertique.rest.core.security.Http;
import dev.vertique.rest.core.security.MutualTls;
import dev.vertique.rest.core.security.OAuth2;
import dev.vertique.rest.core.security.OpenIdConnect;
import dev.vertique.rest.core.security.SecuritySchemeDescription;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The caching headers of the document responses. A protected document is never stored by any cache
 * and varies on the header that carries its scheme's credential; a public document follows the
 * configured default, never weaker than it and never shared-cacheable.
 */
final class DocumentCachePolicy {

    /** The {@code Cache-Control} value of every protected document response. */
    static final String PROTECTED_CACHE_CONTROL = "private, no-store";

    /** The {@code Vary} value of a scheme whose credential travels in the {@code Authorization} header. */
    private static final String AUTHORIZATION = "Authorization";

    /** The {@code Vary} value of a scheme whose credential travels in a cookie. */
    private static final String COOKIE = "Cookie";

    private DocumentCachePolicy() {}

    /**
     * Computes the {@code Cache-Control} value of the public documents from the effective default: the
     * last default header named {@code Cache-Control}, in any letter case. The value is
     * {@code no-store} when that default has a {@code no-store} directive and {@code no-cache}
     * otherwise, preceded by {@code private, } when the default has a {@code private} directive. It is
     * never public.
     *
     * @param jaxRsConfig the JAX-RS routing configuration holding the default headers
     * @return the {@code Cache-Control} value of every public document response
     */
    static String publicCacheControl(JaxRsConfig jaxRsConfig) {
        String effective = null;
        if (jaxRsConfig.defaultHeaders() != null) {
            for (Map.Entry<String, String> header :
                    jaxRsConfig.defaultHeaders().toHeaderMap().entrySet()) {
                if ("Cache-Control".equalsIgnoreCase(header.getKey())) {
                    effective = header.getValue();
                }
            }
        }
        boolean noStore = false;
        boolean isPrivate = false;
        if (effective != null) {
            for (String directive : effective.split(",")) {
                int equals = directive.indexOf('=');
                String name = (equals < 0 ? directive : directive.substring(0, equals))
                        .trim()
                        .toLowerCase(Locale.ROOT);
                noStore |= name.equals("no-store");
                isPrivate |= name.equals("private");
            }
        }
        return (isPrivate ? "private, " : "") + (noStore ? "no-store" : "no-cache");
    }

    /**
     * Computes the {@code Vary} value of a protected document from its scheme's description: the
     * request header that carries the credential. An undescribed scheme, an HTTP, OAuth 2, or OpenID
     * Connect scheme, and any other kind vary on {@code Authorization}; a header API key on its name;
     * a cookie API key on {@code Cookie}. A query API key and mutual TLS carry their credential in no
     * request header, so they have no {@code Vary}.
     *
     * @param description the scheme handler's description, empty when the scheme is undescribed
     * @return the {@code Vary} value, or empty when the response carries none
     */
    static Optional<String> protectedVary(Optional<SecuritySchemeDescription> description) {
        if (description.isEmpty()) {
            return Optional.of(AUTHORIZATION);
        }
        SecuritySchemeDescription kind = description.get();
        if (kind instanceof Http || kind instanceof OAuth2 || kind instanceof OpenIdConnect) {
            return Optional.of(AUTHORIZATION);
        }
        if (kind instanceof ApiKey apiKey) {
            if (apiKey.in() == ApiKey.Location.HEADER) {
                return Optional.of(apiKey.name());
            }
            if (apiKey.in() == ApiKey.Location.COOKIE) {
                return Optional.of(COOKIE);
            }
            if (apiKey.in() == ApiKey.Location.QUERY) {
                return Optional.empty();
            }
        }
        if (kind instanceof MutualTls) {
            return Optional.empty();
        }
        return Optional.of(AUTHORIZATION);
    }
}
