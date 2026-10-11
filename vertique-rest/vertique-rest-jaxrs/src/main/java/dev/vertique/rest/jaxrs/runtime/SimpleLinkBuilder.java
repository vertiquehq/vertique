// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import dev.vertique.rest.core.request.HeaderElement;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
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
            throw new IllegalArgumentException("Invalid link header format");
        }
        UriBuilder parsedUri;
        try {
            parsedUri = UriBuilder.fromUri(s.substring(1, uriEnd));
        } catch (IllegalArgumentException e) {
            // The URI builder's own message carries the offending URI; the header value stays out.
            throw new IllegalArgumentException("Invalid link header format");
        }
        Map<String, String> parsedParams = new LinkedHashMap<>();
        String remaining = s.substring(uriEnd + 1).trim();
        if (!remaining.isEmpty()) {
            List<String> parts = HeaderElement.splitOutsideQuotes(remaining, ';');
            if (parts == null) {
                throw new IllegalArgumentException("Invalid link header format");
            }
            for (String part : parts) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                int eq = p.indexOf('=');
                if (eq < 0) continue;
                String key = p.substring(0, eq).trim();
                String val = HeaderElement.unquote(p.substring(eq + 1).trim());
                if (val == null) {
                    throw new IllegalArgumentException("Invalid link header format");
                }
                parsedParams.put(key, val);
            }
        }
        this.uriBuilder = parsedUri;
        this.params.clear();
        this.params.putAll(parsedParams);
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
