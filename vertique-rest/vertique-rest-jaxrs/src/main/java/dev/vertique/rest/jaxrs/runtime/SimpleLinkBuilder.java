// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal {@link Link.Builder} implementation that constructs {@link SimpleLink} instances.
 *
 * <p>Supports building links with explicit URIs, parameters (rel, title, type), parsing
 * RFC 5988 link header format, {@link UriBuilder} integration, and URI template resolution
 * via {@link #build(Object...)}.
 *
 * @see SimpleLink
 */
class SimpleLinkBuilder implements Link.Builder {

    private UriBuilder uriBuilder;
    private URI baseUri;
    private final Map<String, String> params = new LinkedHashMap<>();

    @Override
    public Link.Builder link(Link link) {
        this.uriBuilder = UriBuilder.fromUri(link.getUri());
        this.params.clear();
        this.params.putAll(link.getParams());
        return this;
    }

    @Override
    public Link.Builder link(String link) {
        if (link == null) {
            throw new IllegalArgumentException("link must not be null");
        }
        String s = link.trim();
        // Parse RFC 5988 format: <uri>; param="value"; ...
        int uriEnd = s.indexOf('>');
        if (!s.startsWith("<") || uriEnd < 0) {
            throw new IllegalArgumentException("Invalid link header format: " + link);
        }
        this.uriBuilder = UriBuilder.fromUri(s.substring(1, uriEnd));
        this.params.clear();
        String remaining = s.substring(uriEnd + 1).trim();
        if (!remaining.isEmpty()) {
            for (String part : SimpleRuntimeDelegate.splitRespectingQuotes(remaining, ';')) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                int eq = p.indexOf('=');
                if (eq < 0) continue;
                String key = p.substring(0, eq).trim();
                String val = p.substring(eq + 1).trim();
                if (val.startsWith("\"") && val.endsWith("\"")) {
                    val = SimpleRuntimeDelegate.unescapeQuotedPair(val.substring(1, val.length() - 1));
                }
                params.put(key, val);
            }
        }
        return this;
    }

    @Override
    public Link.Builder uri(URI uri) {
        this.uriBuilder = UriBuilder.fromUri(uri);
        return this;
    }

    @Override
    public Link.Builder uri(String uri) {
        this.uriBuilder = UriBuilder.fromUri(uri);
        return this;
    }

    @Override
    public Link.Builder baseUri(URI uri) {
        this.baseUri = uri;
        return this;
    }

    @Override
    public Link.Builder baseUri(String uri) {
        this.baseUri = URI.create(uri);
        return this;
    }

    @Override
    public Link.Builder uriBuilder(UriBuilder uriBuilder) {
        this.uriBuilder = uriBuilder.clone();
        return this;
    }

    @Override
    public Link.Builder rel(String rel) {
        String existing = params.get(Link.REL);
        if (existing != null && !existing.isEmpty()) {
            params.put(Link.REL, existing + " " + rel);
        } else {
            params.put(Link.REL, rel);
        }
        return this;
    }

    @Override
    public Link.Builder title(String title) {
        params.put(Link.TITLE, title);
        return this;
    }

    @Override
    public Link.Builder type(String type) {
        params.put(Link.TYPE, type);
        return this;
    }

    @Override
    public Link.Builder param(String name, String value) {
        params.put(name, value);
        return this;
    }

    @Override
    public Link build(Object... values) {
        if (uriBuilder == null) {
            throw new IllegalStateException("URI has not been set");
        }
        URI resolved = uriBuilder.build(values);
        if (baseUri != null && !resolved.isAbsolute()) {
            resolved = baseUri.resolve(resolved);
        }
        return new SimpleLink(resolved, params);
    }

    @Override
    public Link buildRelativized(URI uri, Object... values) {
        Link built = build(values);
        URI relativized = uri.relativize(built.getUri());
        return new SimpleLink(relativized, params);
    }
}
