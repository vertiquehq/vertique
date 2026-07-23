// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable {@link Link} implementation backed by a URI and parameter map.
 *
 * <p>Produces RFC 5988 Web Linking format from {@link #toString()}, e.g.
 * {@code <http://example.com/next>; rel="next"; title="Next page"}.
 *
 * @see SimpleLinkBuilder
 */
class SimpleLink extends Link {

    private final URI uri;
    private final Map<String, String> params;

    SimpleLink(URI uri, Map<String, String> params) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    @Override
    public URI getUri() {
        return uri;
    }

    @Override
    public UriBuilder getUriBuilder() {
        return UriBuilder.fromUri(uri);
    }

    @Override
    public String getRel() {
        return params.get(REL);
    }

    @Override
    public List<String> getRels() {
        String rel = getRel();
        if (rel == null) {
            return List.of();
        }
        return List.of(rel.split("\\s+"));
    }

    @Override
    public String getTitle() {
        return params.get(TITLE);
    }

    @Override
    public String getType() {
        return params.get(TYPE);
    }

    @Override
    public Map<String, String> getParams() {
        return params;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('<').append(uri).append('>');
        for (Map.Entry<String, String> entry : params.entrySet()) {
            sb.append("; ").append(entry.getKey()).append("=\"");
            escapeRfc5988Value(sb, entry.getValue());
            sb.append('"');
        }
        return sb.toString();
    }

    /** Single-pass escape of backslashes and double-quotes for RFC 5988 parameter values. */
    private static void escapeRfc5988Value(StringBuilder sb, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\');
            }
            sb.append(c);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SimpleLink other)) return false;
        return uri.equals(other.uri) && params.equals(other.params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, params);
    }
}
