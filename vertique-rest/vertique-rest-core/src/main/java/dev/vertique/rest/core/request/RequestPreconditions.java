// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates the conditional request headers from an HTTP request and provides RFC 9110
 * §13.2.2 compliant precondition evaluation.
 *
 * <p>Developers may inject a {@code RequestPreconditions} as a JAX-RS resource method parameter
 * to perform early evaluation of conditional headers before executing expensive operations:
 *
 * <pre>{@code
 * @GET
 * @Path("/{id}")
 * public Future<Response> getItem(RequestPreconditions preconditions, @PathParam("id") String id) {
 *     EntityTag etag = computeEtagFromVersion(id);
 *     Response earlyResponse = preconditions.evaluate(etag);
 *     if (earlyResponse != null) {
 *         return Future.succeededFuture(earlyResponse);  // 304 — no DB query
 *     }
 *     return repository.findById(id)
 *         .map(item -> Response.ok(item).tag(etag).build());
 * }
 * }</pre>
 *
 * <p>The framework also performs automatic post-response evaluation: after the resource method
 * produces a {@link Response}, the handler evaluates preconditions against the response's
 * {@code ETag} and {@code Last-Modified} headers and replaces the response with a
 * {@code 304 Not Modified} or {@code 412 Precondition Failed} response when appropriate.
 *
 * <p>Instances are cached in {@link RoutingContext#data()} under the key
 * {@value CONTEXT_KEY} and shared between the framework's auto-evaluation and developer
 * early-evaluation paths.
 *
 * @see #from(RoutingContext)
 * @see #evaluate(EntityTag)
 * @see #evaluate(EntityTag, Instant)
 * @see #evaluate(Response)
 */
public final class RequestPreconditions {

    /** Key used to cache a {@code RequestPreconditions} instance in {@link RoutingContext#data()}. */
    static final String CONTEXT_KEY = "dev.vertique.preconditions";

    // --- RFC 1123 date formatter for HTTP dates ---

    private static final DateTimeFormatter HTTP_DATE_FORMATTER = DateTimeFormatter.RFC_1123_DATE_TIME;

    // --- Conditional header values ---

    @Nullable
    private final String ifNoneMatch;

    @Nullable
    private final String ifMatch;

    @Nullable
    private final String ifModifiedSince;

    @Nullable
    private final String ifUnmodifiedSince;

    private final HttpMethod method;

    // --- Constructor ---

    /**
     * Creates a new {@code RequestPreconditions} from the given header values and HTTP method.
     *
     * @param ifNoneMatch       value of the {@code If-None-Match} request header, or {@code null}
     * @param ifMatch           value of the {@code If-Match} request header, or {@code null}
     * @param ifModifiedSince   value of the {@code If-Modified-Since} request header, or {@code null}
     * @param ifUnmodifiedSince value of the {@code If-Unmodified-Since} request header, or {@code null}
     * @param method            the HTTP method of the request
     */
    private RequestPreconditions(
            @Nullable String ifNoneMatch,
            @Nullable String ifMatch,
            @Nullable String ifModifiedSince,
            @Nullable String ifUnmodifiedSince,
            HttpMethod method) {
        this.ifNoneMatch = blankToNull(ifNoneMatch);
        this.ifMatch = blankToNull(ifMatch);
        this.ifModifiedSince = blankToNull(ifModifiedSince);
        this.ifUnmodifiedSince = blankToNull(ifUnmodifiedSince);
        this.method = method;
    }

    // --- Factory ---

    /**
     * Creates a {@code RequestPreconditions} from the given routing context, caching the instance
     * in {@link RoutingContext#data()} under {@value CONTEXT_KEY} to avoid duplicate creation.
     *
     * @param ctx the current routing context
     * @return the {@code RequestPreconditions} for this request, never {@code null}
     */
    public static RequestPreconditions from(RoutingContext ctx) {
        Object cached = ctx.data().get(CONTEXT_KEY);
        if (cached instanceof RequestPreconditions rp) {
            return rp;
        }
        RequestPreconditions rp = new RequestPreconditions(
                ctx.request().getHeader("If-None-Match"),
                ctx.request().getHeader("If-Match"),
                ctx.request().getHeader("If-Modified-Since"),
                ctx.request().getHeader("If-Unmodified-Since"),
                ctx.request().method());
        ctx.data().put(CONTEXT_KEY, rp);
        return rp;
    }

    // --- Query methods ---

    /**
     * Returns {@code true} if the HTTP method of this request is {@code HEAD}.
     *
     * @return {@code true} for HEAD requests
     */
    public boolean isHead() {
        return method == HttpMethod.HEAD;
    }

    /**
     * Returns {@code true} if any conditional request header has a non-blank value.
     *
     * @return {@code true} if at least one of {@code If-None-Match}, {@code If-Match},
     *         {@code If-Modified-Since}, or {@code If-Unmodified-Since} is present
     */
    public boolean hasConditions() {
        return ifNoneMatch != null || ifMatch != null || ifModifiedSince != null || ifUnmodifiedSince != null;
    }

    // --- Evaluate overloads ---

    /**
     * Evaluates conditional request headers against the given ETag, following RFC 9110
     * §13.2.2 evaluation order.
     *
     * <p>Returns a {@link Response} with status {@code 304 Not Modified} or
     * {@code 412 Precondition Failed} when the conditions match, or {@code null} when
     * the request should proceed normally.
     *
     * @param etag the current ETag of the resource; may be {@code null}
     * @return a {@code 304} or {@code 412} Response if a condition matched, or {@code null}
     */
    @Nullable
    public Response evaluate(@Nullable EntityTag etag) {
        return evaluate(etag, null);
    }

    /**
     * Evaluates conditional request headers against the given ETag and last-modified time,
     * following RFC 9110 §13.2.2 evaluation order.
     *
     * <p>Returns a {@link Response} with status {@code 304 Not Modified} or
     * {@code 412 Precondition Failed} when the conditions match, or {@code null} when the
     * request should proceed normally.
     *
     * @param etag         the current ETag of the resource; may be {@code null}
     * @param lastModified the last-modified time of the resource; may be {@code null}
     * @return a {@code 304} or {@code 412} Response if a condition matched, or {@code null}
     */
    @Nullable
    public Response evaluate(@Nullable EntityTag etag, @Nullable Instant lastModified) {
        // RFC 9110 §13.2.2 — Step 1: If-Match
        if (ifMatch != null) {
            if (!evaluateIfMatch(etag)) {
                return buildPreconditionFailed();
            }
        }

        // RFC 9110 §13.2.2 — Step 2: If-Unmodified-Since (only if no If-Match)
        if (ifMatch == null && ifUnmodifiedSince != null && lastModified != null) {
            Instant threshold = parseHttpDate(ifUnmodifiedSince);
            if (threshold != null && lastModified.isAfter(threshold)) {
                return buildPreconditionFailed();
            }
        }

        // RFC 9110 §13.2.2 — Step 3: If-None-Match
        if (ifNoneMatch != null) {
            if (evaluateIfNoneMatch(etag)) {
                // Weak match found
                if (isGetOrHead()) {
                    return buildNotModified(etag);
                } else {
                    return buildPreconditionFailed();
                }
            }
        }

        // RFC 9110 §13.2.2 — Step 4: If-Modified-Since (GET/HEAD only)
        if (ifModifiedSince != null && lastModified != null && isGetOrHead()) {
            Instant threshold = parseHttpDate(ifModifiedSince);
            if (threshold != null && !lastModified.isAfter(threshold)) {
                return buildNotModified(etag);
            }
        }

        return null;
    }

    /**
     * Evaluates conditional request headers against the ETag and Last-Modified headers of the
     * given {@link Response}, following RFC 9110 §13.2.2 evaluation order.
     *
     * <p>This is the framework's automatic evaluation path: called by the response handler
     * after the resource method produces a response, to check whether the response can be
     * replaced with a {@code 304} or {@code 412} without serializing the entity.
     *
     * @param response the produced JAX-RS response containing optional {@code ETag} and
     *                 {@code Last-Modified} headers
     * @return a {@code 304} or {@code 412} Response if a condition matched, or {@code null}
     *         if the request should proceed with the original response
     */
    @Nullable
    public Response evaluate(Response response) {
        EntityTag etag = response.getEntityTag();
        Instant lastModified = extractLastModified(response);
        return evaluate(etag, lastModified);
    }

    // --- RFC 9110 evaluation helpers ---

    /**
     * Evaluates the {@code If-Match} precondition using strong comparison.
     *
     * <p>RFC 9110 §13.1.1: An {@code If-Match} of {@code *} matches any resource that has a
     * current representation (i.e., any non-null strong ETag). Otherwise, each listed ETag
     * must be compared using strong comparison (both sides must be strong, values must match
     * exactly).
     *
     * @param etag the resource's current ETag; {@code null} means no representation exists
     * @return {@code true} if the precondition is satisfied (request may proceed)
     */
    private boolean evaluateIfMatch(@Nullable EntityTag etag) {
        if ("*".equals(ifMatch)) {
            // Wildcard: matches any existing strong ETag; null or weak ETag fails
            return etag != null && !etag.isWeak();
        }
        if (etag == null || etag.isWeak()) {
            // Strong comparison requires a strong ETag on the resource side
            return false;
        }
        List<String> etagValues = parseETagList(ifMatch);
        String resourceValue = etag.getValue();
        for (String candidateValue : etagValues) {
            if (resourceValue.equals(candidateValue)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Evaluates the {@code If-None-Match} precondition using weak comparison.
     *
     * <p>RFC 9110 §13.1.2: {@code *} matches any ETag (including weak ones). Otherwise,
     * each listed ETag is compared using weak comparison (strip {@code W/} prefix and quotes,
     * compare opaque-tag values).
     *
     * @param etag the resource's current ETag; {@code null} means no representation
     * @return {@code true} if a weak match was found (precondition triggered)
     */
    private boolean evaluateIfNoneMatch(@Nullable EntityTag etag) {
        if ("*".equals(ifNoneMatch)) {
            // Wildcard: matches any existing ETag (including null? No — null means no ETag)
            return etag != null;
        }
        if (etag == null) {
            return false;
        }
        List<String> etagValues = parseETagList(ifNoneMatch);
        String resourceValue = etag.getValue();
        for (String candidateValue : etagValues) {
            if (resourceValue.equals(candidateValue)) {
                return true;
            }
        }
        return false;
    }

    // --- ETag list parsing ---

    /**
     * Parses a comma-separated list of ETag values from a header string, returning the
     * opaque-tag value (stripped of {@code W/} prefix and surrounding quotes) for each entry.
     *
     * <p>Handles proper RFC 9110 §8.8.3 ETag format:
     * <ul>
     *   <li>Strong ETag: {@code "value"} → {@code value}</li>
     *   <li>Weak ETag: {@code W/"value"} → {@code value}</li>
     * </ul>
     *
     * @param headerValue the raw header value (e.g. {@code "abc", W/"def", "ghi"})
     * @return list of extracted opaque-tag values; never {@code null}
     */
    private static List<String> parseETagList(String headerValue) {
        List<String> result = new ArrayList<>();
        int len = headerValue.length();
        int i = 0;
        while (i < len) {
            // Skip whitespace and commas
            while (i < len && (headerValue.charAt(i) == ' ' || headerValue.charAt(i) == ',')) {
                i++;
            }
            if (i >= len) {
                break;
            }
            // Skip optional W/ prefix
            if (i + 2 < len && headerValue.charAt(i) == 'W' && headerValue.charAt(i + 1) == '/') {
                i += 2;
            }
            // Expect opening quote
            if (i < len && headerValue.charAt(i) == '"') {
                i++; // skip opening quote
                int start = i;
                // Find closing quote
                while (i < len && headerValue.charAt(i) != '"') {
                    i++;
                }
                if (i < len) {
                    result.add(headerValue.substring(start, i));
                    if (i < len) {
                        i++; // skip closing quote
                    }
                }
            } else {
                // Malformed token — skip to next comma
                while (i < len && headerValue.charAt(i) != ',') {
                    i++;
                }
            }
        }
        return result;
    }

    // --- Response building ---

    /**
     * Builds a {@code 304 Not Modified} response, preserving the ETag header if present.
     *
     * @param etag the current resource ETag to include in the response; may be {@code null}
     * @return a 304 response
     */
    private static Response buildNotModified(@Nullable EntityTag etag) {
        Response.ResponseBuilder builder = Response.notModified();
        if (etag != null) {
            builder.tag(etag);
        }
        return builder.build();
    }

    /**
     * Builds a {@code 412 Precondition Failed} response.
     *
     * @return a 412 response
     */
    private static Response buildPreconditionFailed() {
        return Response.status(Response.Status.PRECONDITION_FAILED).build();
    }

    // --- Date parsing ---

    /**
     * Parses an RFC 1123 HTTP date string to an {@link Instant}.
     * Returns {@code null} on parse failure rather than throwing.
     *
     * @param dateString the HTTP date string to parse
     * @return the parsed {@link Instant}, or {@code null} if unparseable
     */
    @Nullable
    private static Instant parseHttpDate(String dateString) {
        try {
            return HTTP_DATE_FORMATTER
                    .parse(dateString, java.time.ZonedDateTime::from)
                    .toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Extracts the last-modified time from a {@link Response}'s {@code Last-Modified} header.
     *
     * @param response the response to inspect
     * @return the parsed last-modified {@link Instant}, or {@code null} if the header is absent
     *         or unparseable
     */
    @Nullable
    private static Instant extractLastModified(Response response) {
        MultivaluedMap<String, String> headers = response.getStringHeaders();
        if (headers == null) {
            return null;
        }
        String lastModified = headers.getFirst("Last-Modified");
        if (lastModified == null) {
            return null;
        }
        return parseHttpDate(lastModified);
    }

    // --- Utility ---

    /**
     * Returns {@code true} if the HTTP method is {@code GET} or {@code HEAD}.
     *
     * @return {@code true} for GET or HEAD methods
     */
    private boolean isGetOrHead() {
        return method == HttpMethod.GET || method == HttpMethod.HEAD;
    }

    /**
     * Returns {@code null} if the given string is {@code null} or blank, otherwise returns it.
     *
     * @param s the string to test
     * @return {@code null} if blank, otherwise the original string
     */
    @Nullable
    private static String blankToNull(@Nullable String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
