// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.RuntimeDelegate;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable {@link Response} implementation produced by {@link SimpleResponseBuilder}.
 *
 * <p>Stores response state as a status code, optional reason phrase, optional entity, and a
 * multi-valued header map. Header-backed getters ({@link #getCookies()},
 * {@link #getAllowedMethods()}, {@link #getEntityTag()}, {@link #getDate()},
 * {@link #getLastModified()}) parse their values from the stored headers at call time.
 *
 * @see SimpleResponseBuilder
 * @see SimpleRuntimeDelegate
 */
class SimpleResponse extends Response {

    // --- HTTP date formatting ---

    /** RFC 1123 / RFC 7231 HTTP date formatter, e.g. {@code Thu, 01 Jan 1970 00:00:00 GMT}. */
    private static final DateTimeFormatter HTTP_DATE_FORMAT = DateTimeFormatter.RFC_1123_DATE_TIME;

    // --- Stored state ---

    private final int status;
    private final String reasonPhrase;
    private final Object entity;
    private final MultivaluedMap<String, Object> headers;

    /**
     * Constructs a {@code SimpleResponse}.
     *
     * @param status       the HTTP status code
     * @param reasonPhrase optional reason phrase; may be {@code null}
     * @param entity       optional response entity; may be {@code null}
     * @param headers      the response headers; must not be {@code null}
     */
    SimpleResponse(int status, String reasonPhrase, Object entity, MultivaluedMap<String, Object> headers) {
        this.status = status;
        this.reasonPhrase = reasonPhrase;
        this.entity = entity;
        this.headers = headers;
    }

    // --- Status ---

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public StatusType getStatusInfo() {
        return new SimpleStatusType(status, reasonPhrase);
    }

    // --- Entity ---

    @Override
    public Object getEntity() {
        return entity;
    }

    @Override
    public boolean hasEntity() {
        return entity != null;
    }

    @Override
    public void close() {}

    // --- Headers ---

    @Override
    public MultivaluedMap<String, Object> getMetadata() {
        return new MultivaluedHashMap<>(headers);
    }

    @Override
    public MultivaluedMap<String, Object> getHeaders() {
        return getMetadata();
    }

    @Override
    public MultivaluedMap<String, String> getStringHeaders() {
        MultivaluedHashMap<String, String> result = new MultivaluedHashMap<>();
        for (Map.Entry<String, List<Object>> entry : headers.entrySet()) {
            for (Object value : entry.getValue()) {
                result.add(entry.getKey(), value == null ? null : value.toString());
            }
        }
        return result;
    }

    @Override
    public String getHeaderString(String name) {
        List<Object> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object v = values.get(i);
            sb.append(v == null ? "" : v.toString());
        }
        return sb.toString();
    }

    // --- Derived header getters ---

    @Override
    public int getLength() {
        String value = getHeaderString("Content-Length");
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public MediaType getMediaType() {
        String value = getHeaderString("Content-Type");
        if (value == null) {
            return null;
        }
        try {
            return MediaType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Returns the content language parsed from the {@code Content-Language} response header.
     *
     * @return the parsed {@link Locale}, or {@code null} if the header is absent
     */
    @Override
    public Locale getLanguage() {
        String value = getHeaderString("Content-Language");
        if (value == null) {
            return null;
        }
        return Locale.forLanguageTag(value.trim());
    }

    /**
     * Returns the allowed HTTP methods parsed from the {@code Allow} response header.
     * Returns an empty set when the header is absent.
     *
     * @return unmodifiable set of allowed method names, never {@code null}
     */
    @Override
    public Set<String> getAllowedMethods() {
        String allow = getHeaderString("Allow");
        if (allow == null || allow.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> methods = new LinkedHashSet<>();
        for (String method : allow.split(",")) {
            methods.add(method.trim());
        }
        return Collections.unmodifiableSet(methods);
    }

    /**
     * Returns the cookies parsed from {@code Set-Cookie} header entries.
     * Only entries stored as {@link NewCookie} objects (i.e., added via
     * {@link SimpleResponseBuilder#cookie(NewCookie...)}) are included; raw string values
     * are not re-parsed.
     *
     * @return unmodifiable map of cookie name to {@link NewCookie}, never {@code null}
     */
    @Override
    public Map<String, NewCookie> getCookies() {
        List<Object> values = headers.get("Set-Cookie");
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, NewCookie> result = new LinkedHashMap<>();
        for (Object value : values) {
            if (value instanceof NewCookie nc) {
                result.put(nc.getName(), nc);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns the entity tag parsed from the {@code ETag} response header.
     * Handles strong ({@code "value"}) and weak ({@code W/"value"}) tags.
     *
     * @return the parsed {@link EntityTag}, or {@code null} if the header is absent
     */
    @Override
    public EntityTag getEntityTag() {
        String etag = getHeaderString("ETag");
        if (etag == null) {
            return null;
        }
        return RuntimeDelegate.getInstance()
                .createHeaderDelegate(EntityTag.class)
                .fromString(etag);
    }

    /**
     * Returns the response date parsed from the {@code Date} response header.
     *
     * @return the parsed date, or {@code null} if the header is absent or unparseable
     */
    @Override
    public Date getDate() {
        return parseHttpDate("Date");
    }

    /**
     * Returns the last-modified date parsed from the {@code Last-Modified} response header.
     *
     * @return the parsed date, or {@code null} if the header is absent or unparseable
     */
    @Override
    public Date getLastModified() {
        return parseHttpDate("Last-Modified");
    }

    @Override
    public URI getLocation() {
        String value = getHeaderString("Location");
        if (value == null) {
            return null;
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // --- Link headers ---

    /**
     * Returns the links parsed from {@code Link} header entries.
     * Handles both {@link Link} objects and raw RFC 5988 strings.
     *
     * @return unmodifiable set of {@link Link} instances, never {@code null}
     */
    @Override
    public Set<Link> getLinks() {
        List<Object> values = headers.get("Link");
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Link> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (value instanceof Link link) {
                result.add(link);
            } else if (value instanceof String s) {
                // A single Link header may contain multiple comma-separated link-values.
                // Split on commas outside <...> and "..." to preserve commas in URIs.
                for (String segment : splitLinkHeader(s)) {
                    String trimmed = segment.trim();
                    if (!trimmed.isEmpty()) {
                        try {
                            result.add(Link.valueOf(trimmed));
                        } catch (IllegalArgumentException ignored) {
                            // Skip malformed link header strings
                        }
                    }
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public boolean hasLink(String relation) {
        return getLink(relation) != null;
    }

    @Override
    public Link getLink(String relation) {
        for (Link link : getLinks()) {
            if (link.getRels().contains(relation)) {
                return link;
            }
        }
        return null;
    }

    @Override
    public Link.Builder getLinkBuilder(String relation) {
        Link link = getLink(relation);
        if (link == null) {
            return null;
        }
        return new SimpleLinkBuilder().link(link);
    }

    // --- Entity reading (server-side not supported) ---

    @Override
    public <T> T readEntity(Class<T> entityType) {
        throw new UnsupportedOperationException("Server-side Response does not support readEntity");
    }

    @Override
    public <T> T readEntity(GenericType<T> entityType) {
        throw new UnsupportedOperationException("Server-side Response does not support readEntity");
    }

    @Override
    public <T> T readEntity(Class<T> entityType, Annotation[] annotations) {
        throw new UnsupportedOperationException("Server-side Response does not support readEntity");
    }

    @Override
    public <T> T readEntity(GenericType<T> entityType, Annotation[] annotations) {
        throw new UnsupportedOperationException("Server-side Response does not support readEntity");
    }

    @Override
    public boolean bufferEntity() {
        return false;
    }

    // --- Private helpers ---

    /**
     * Splits a combined {@code Link} header value on commas, respecting {@code <...>} URI
     * references and {@code "..."} quoted strings so that commas inside URIs or parameter
     * values are preserved.
     */
    private static List<String> splitLinkHeader(String header) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inAngleBrackets = false;
        boolean inQuotes = false;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '\\' && inQuotes && i + 1 < header.length()) {
                current.append(c);
                i++;
                current.append(header.charAt(i));
            } else if (c == '"' && !inAngleBrackets) {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == '<' && !inQuotes) {
                inAngleBrackets = true;
                current.append(c);
            } else if (c == '>' && !inQuotes) {
                inAngleBrackets = false;
                current.append(c);
            } else if (c == ',' && !inAngleBrackets && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    /**
     * Parses an RFC 1123 HTTP date from the named response header.
     *
     * @param headerName the header to read (e.g. {@code "Date"}, {@code "Last-Modified"})
     * @return the parsed {@link Date}, or {@code null} if the header is absent or unparseable
     */
    private Date parseHttpDate(String headerName) {
        String value = getHeaderString(headerName);
        if (value == null) {
            return null;
        }
        try {
            return Date.from(ZonedDateTime.parse(value, HTTP_DATE_FORMAT).toInstant());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
