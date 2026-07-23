// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Minimal {@link Response.ResponseBuilder} implementation backed by a simple in-memory header map.
 *
 * <p>Supports the most commonly used HTTP response features: status, entity, headers, cookies,
 * cache control, expiry dates, entity tags, allowed methods, content encoding, content language,
 * variant selection, and hypermedia links. Server-driven content negotiation
 * ({@code variants(Variant...)}, {@code variants(List)}) is not supported by this minimal runtime
 * and will throw {@link UnsupportedOperationException}.
 *
 * @see SimpleRuntimeDelegate
 * @see SimpleResponse
 */
class SimpleResponseBuilder extends Response.ResponseBuilder {

    // --- HTTP date formatting ---

    /** RFC 1123 / RFC 7231 HTTP date formatter, e.g. {@code Thu, 01 Jan 1970 00:00:00 GMT}. */
    private static final DateTimeFormatter HTTP_DATE_FORMAT = DateTimeFormatter.RFC_1123_DATE_TIME;

    // --- Builder state ---

    private int status = 200;
    private String reasonPhrase;
    private Object entity;
    private MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

    // --- Core builder methods ---

    @Override
    public Response build() {
        return new SimpleResponse(status, reasonPhrase, entity, new MultivaluedHashMap<>(headers));
    }

    @Override
    public Response.ResponseBuilder clone() {
        SimpleResponseBuilder copy = new SimpleResponseBuilder();
        copy.status = this.status;
        copy.reasonPhrase = this.reasonPhrase;
        copy.entity = this.entity;
        copy.headers = new MultivaluedHashMap<>(this.headers);
        return copy;
    }

    // --- Status ---

    @Override
    public Response.ResponseBuilder status(int status) {
        this.status = status;
        return this;
    }

    @Override
    public Response.ResponseBuilder status(int status, String reasonPhrase) {
        this.status = status;
        this.reasonPhrase = reasonPhrase;
        return this;
    }

    // --- Entity ---

    @Override
    public Response.ResponseBuilder entity(Object entity) {
        this.entity = entity;
        return this;
    }

    @Override
    public Response.ResponseBuilder entity(Object entity, Annotation[] annotations) {
        this.entity = entity;
        return this;
    }

    // --- General headers ---

    @Override
    public Response.ResponseBuilder header(String name, Object value) {
        if (value == null) {
            headers.remove(name);
        } else {
            headers.add(name, value);
        }
        return this;
    }

    /**
     * Clears all current headers and replaces them with the entries from {@code headers}.
     *
     * @param headers the new header map to install, or {@code null} to simply clear all headers
     * @return this builder
     */
    @Override
    public Response.ResponseBuilder replaceAll(MultivaluedMap<String, Object> headers) {
        this.headers.clear();
        if (headers != null) {
            this.headers.putAll(headers);
        }
        return this;
    }

    // --- Content-Type / Location ---

    @Override
    public Response.ResponseBuilder type(MediaType type) {
        headers.remove("Content-Type");
        if (type != null) {
            headers.add("Content-Type", type);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder type(String type) {
        headers.remove("Content-Type");
        if (type != null) {
            headers.add("Content-Type", type);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder location(URI location) {
        headers.remove("Location");
        if (location != null) {
            headers.add("Location", location);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder contentLocation(URI location) {
        headers.remove("Content-Location");
        if (location != null) {
            headers.add("Content-Location", location);
        }
        return this;
    }

    // --- Allowed methods ---

    /**
     * Sets the {@code Allow} response header as a comma-separated list of HTTP method names.
     *
     * @param methods the allowed HTTP methods; ignored if {@code null} or empty
     * @return this builder
     */
    @Override
    public Response.ResponseBuilder allow(String... methods) {
        headers.remove("Allow");
        if (methods != null && methods.length > 0) {
            headers.add("Allow", String.join(", ", methods));
        }
        return this;
    }

    /**
     * Sets the {@code Allow} response header as a comma-separated list of HTTP method names.
     *
     * @param methods the allowed HTTP methods; ignored if {@code null} or empty
     * @return this builder
     */
    @Override
    public Response.ResponseBuilder allow(Set<String> methods) {
        headers.remove("Allow");
        if (methods != null && !methods.isEmpty()) {
            headers.add("Allow", String.join(", ", methods));
        }
        return this;
    }

    // --- Cache control ---

    /**
     * Sets the {@code Cache-Control} response header.
     *
     * @param cacheControl the cache control directives; {@code null} removes the header
     * @return this builder
     */
    @Override
    public Response.ResponseBuilder cacheControl(CacheControl cacheControl) {
        headers.remove("Cache-Control");
        if (cacheControl != null) {
            headers.add("Cache-Control", cacheControl.toString());
        }
        return this;
    }

    // --- Date headers ---

    /**
     * Sets the {@code Expires} response header formatted as an RFC 1123 HTTP date.
     *
     * @param expires the expiry date; {@code null} removes the header
     * @return this builder
     */
    @Override
    public Response.ResponseBuilder expires(Date expires) {
        headers.remove("Expires");
        if (expires != null) {
            headers.add("Expires", formatHttpDate(expires));
        }
        return this;
    }

    /**
     * Sets the {@code Last-Modified} response header formatted as an RFC 1123 HTTP date.
     *
     * @param lastModified the last-modified date; {@code null} removes the header
     * @return this builder
     */
    @Override
    public Response.ResponseBuilder lastModified(Date lastModified) {
        headers.remove("Last-Modified");
        if (lastModified != null) {
            headers.add("Last-Modified", formatHttpDate(lastModified));
        }
        return this;
    }

    // --- Entity tag ---

    /**
     * Sets the {@code ETag} response header from an {@link EntityTag}.
     *
     * @param tag the entity tag; {@code null} removes the header
     * @return this builder
     */
    @Override
    public Response.ResponseBuilder tag(EntityTag tag) {
        headers.remove("ETag");
        if (tag != null) {
            headers.add("ETag", tag.toString());
        }
        return this;
    }

    /**
     * Sets the {@code ETag} response header as a strong (quoted) tag.
     *
     * @param tag the tag value (without quotes); {@code null} removes the header
     * @return this builder
     */
    @Override
    public Response.ResponseBuilder tag(String tag) {
        headers.remove("ETag");
        if (tag != null) {
            headers.add("ETag", "\"" + tag + "\"");
        }
        return this;
    }

    // --- Cookies ---

    /**
     * Adds each non-null cookie as a {@code Set-Cookie} header entry.
     * The {@link NewCookie} object is stored directly so that {@link SimpleResponse#getCookies()}
     * can return typed cookie instances without re-parsing.
     *
     * @param cookies the cookies to set; {@code null} or empty is a no-op
     * @return this builder
     */
    @Override
    public Response.ResponseBuilder cookie(NewCookie... cookies) {
        if (cookies != null) {
            for (NewCookie cookie : cookies) {
                if (cookie != null) {
                    headers.add("Set-Cookie", cookie);
                }
            }
        }
        return this;
    }

    // --- Content negotiation headers ---

    /**
     * Sets the {@code Content-Encoding} response header.
     *
     * @param encoding the content encoding value (e.g. {@code "gzip"}); {@code null} removes the header
     * @return this builder
     */
    @Override
    public Response.ResponseBuilder encoding(String encoding) {
        headers.remove("Content-Encoding");
        if (encoding != null) {
            headers.add("Content-Encoding", encoding);
        }
        return this;
    }

    /**
     * Sets the {@code Content-Language} response header from a language tag string.
     *
     * @param language the language tag value (e.g. {@code "en-US"}); {@code null} removes the header
     * @return this builder
     */
    @Override
    public Response.ResponseBuilder language(String language) {
        headers.remove("Content-Language");
        if (language != null) {
            headers.add("Content-Language", language);
        }
        return this;
    }

    /**
     * Sets the {@code Content-Language} response header from a {@link Locale},
     * using {@link Locale#toLanguageTag()}.
     *
     * @param language the locale to use as language tag; {@code null} removes the header
     * @return this builder
     */
    @Override
    public Response.ResponseBuilder language(Locale language) {
        headers.remove("Content-Language");
        if (language != null) {
            headers.add("Content-Language", language.toLanguageTag());
        }
        return this;
    }

    /**
     * Sets response headers derived from the given {@link Variant}: media type, content language,
     * and content encoding. Passing {@code null} clears all three headers.
     *
     * @param variant the variant describing the response representation; {@code null} clears headers
     * @return this builder
     */
    @Override
    public Response.ResponseBuilder variant(Variant variant) {
        if (variant == null) {
            type((MediaType) null);
            language((Locale) null);
            encoding(null);
        } else {
            type(variant.getMediaType());
            language(variant.getLanguage());
            encoding(variant.getEncoding());
        }
        return this;
    }

    /**
     * Not supported by this minimal JAX-RS runtime.
     *
     * @param variants ignored
     * @return never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public Response.ResponseBuilder variants(Variant... variants) {
        throw new UnsupportedOperationException("variants(Variant...) is not supported by this minimal JAX-RS runtime");
    }

    /**
     * Not supported by this minimal JAX-RS runtime.
     *
     * @param variants ignored
     * @return never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public Response.ResponseBuilder variants(List<Variant> variants) {
        throw new UnsupportedOperationException("variants(List) is not supported by this minimal JAX-RS runtime");
    }

    // --- Hypermedia links ---

    /**
     * Replaces all {@code Link} response headers with the provided links.
     * Passing {@code null} clears all existing {@code Link} headers.
     *
     * @param links the links to set; {@code null} clears existing headers
     * @return this builder
     */
    @Override
    public Response.ResponseBuilder links(Link... links) {
        headers.remove("Link");
        if (links != null) {
            for (Link link : links) {
                if (link != null) {
                    headers.add("Link", link);
                }
            }
        }
        return this;
    }

    /**
     * Adds a single {@code Link} header with the given URI and relation.
     * Does not remove existing {@code Link} headers.
     *
     * @param uri the link URI; must not be {@code null}
     * @param rel the link relation type; must not be {@code null}
     * @return this builder
     */
    @Override
    public Response.ResponseBuilder link(URI uri, String rel) {
        headers.add("Link", new SimpleLink(uri, Map.of(Link.REL, rel)));
        return this;
    }

    /**
     * Adds a single {@code Link} header with the given URI string and relation.
     * Does not remove existing {@code Link} headers.
     *
     * @param uri the link URI string; must not be {@code null}
     * @param rel the link relation type; must not be {@code null}
     * @return this builder
     */
    @Override
    public Response.ResponseBuilder link(String uri, String rel) {
        return link(URI.create(uri), rel);
    }

    // --- Private helpers ---

    /**
     * Formats a {@link Date} as an RFC 1123 HTTP date string (UTC timezone).
     *
     * @param date the date to format
     * @return the formatted HTTP date string
     */
    private static String formatHttpDate(Date date) {
        return HTTP_DATE_FORMAT.format(date.toInstant().atZone(ZoneOffset.UTC));
    }
}
