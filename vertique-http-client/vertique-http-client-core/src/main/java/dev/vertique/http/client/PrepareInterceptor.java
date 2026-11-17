// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpBodyLimitException;
import dev.vertique.http.client.exception.HttpBodyLimitException.Direction;
import dev.vertique.http.client.internal.BoundedBodyCodec;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.impl.ClientPhase;
import io.vertx.ext.web.client.impl.HttpContext;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.multipart.FormDataPart;
import io.vertx.ext.web.multipart.MultipartForm;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Governance interceptor for the request-preparation phase: it refuses relative requests of a client
 * without a base URL, applies the request header rules to what the caller set, refuses a request body
 * over the request bound, adds the client's default headers and wraps the response body codec so the
 * response bound holds whatever codec the caller chose.
 *
 * <p>Wrapping the codec prepares the request a second time, which runs the interceptors registered
 * before this one again; this interceptor marks the context and passes the second pass through.
 */
final class PrepareInterceptor implements Handler<HttpContext<?>> {

    /** Host a relative request carries when the client has no base URL; it is never connected to. */
    static final String NO_BASE_HOST = "no-base-url.invalid";

    /** Failure message of a relative request on a client without a base URL. */
    static final String NO_BASE_URL = "The request is relative but the client has no base URL";

    private static final String PREPARED = "vertique.prepared";

    private static final String FORM_URLENCODED = "application/x-www-form-urlencoded";
    private static final String JSON = "application/json";

    private final ClientSettings settings;

    PrepareInterceptor(ClientSettings settings) {
        this.settings = settings;
    }

    @Override
    public void handle(HttpContext<?> ctx) {
        if (ctx.phase() != ClientPhase.PREPARE_REQUEST) {
            ctx.next();
            return;
        }
        if (ctx.get(PREPARED) != null) {
            ctx.next();
            return;
        }
        HttpRequest<?> request = ctx.request();
        if (NO_BASE_HOST.equals(request.host())) {
            ctx.fail(new IllegalStateException(NO_BASE_URL));
            return;
        }
        MultiMap headers = request.headers();
        if (settings.validateHeaders()) {
            String violation = HeaderRules.violation(headers);
            if (violation != null) {
                ctx.fail(new IllegalArgumentException(violation));
                return;
            }
        }
        long maxRequestBytes = settings.maxRequestBytes();
        if (maxRequestBytes > 0 && !fitsRequestBound(ctx, maxRequestBytes)) {
            ctx.fail(new HttpBodyLimitException(Direction.REQUEST, maxRequestBytes));
            return;
        }
        for (Map.Entry<String, String> header : settings.defaultHeaders().entrySet()) {
            if (!headers.contains(header.getKey())) {
                headers.add(header.getKey(), header.getValue());
            }
        }
        ctx.set(PREPARED, Boolean.TRUE);
        long maxResponseBytes = settings.maxResponseBytes();
        if (maxResponseBytes > 0) {
            bindResponseBound(ctx, maxResponseBytes);
            return;
        }
        ctx.next();
    }

    /**
     * Replaces the request's body codec with one that counts the bytes it passes on and prepares the
     * request again; the second pass continues the chain.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void bindResponseBound(HttpContext<?> context, long limit) {
        HttpContext ctx = context;
        HttpRequest request = ctx.request();
        BodyCodec bounded = new BoundedBodyCodec(request.bodyCodec(), limit, () -> ExchangeTracker.overrun(ctx, limit));
        ctx.prepareRequest(request.as(bounded), ctx.contentType(), ctx.body());
    }

    /**
     * Measures the request body as it will be encoded. A body that cannot be measured before sending (a
     * stream or a multipart form) does not fit.
     */
    private static boolean fitsRequestBound(HttpContext<?> ctx, long limit) {
        Object body = ctx.body();
        if (body == null) {
            return true;
        }
        String contentType = ctx.contentType();
        if (body instanceof Buffer buffer) {
            return buffer.length() <= limit;
        }
        if (body instanceof MultipartForm form && FORM_URLENCODED.equals(contentType)) {
            return encodedFormLength(form, limit) <= limit;
        }
        if (JSON.equals(contentType) && !(body instanceof MultipartForm)) {
            Buffer encoded = body instanceof JsonObject json ? json.toBuffer() : Json.encodeToBuffer(body);
            return encoded.length() <= limit;
        }
        return false;
    }

    /** The length of the url-encoded form, counted up to one byte over the limit. */
    private static long encodedFormLength(MultipartForm form, long limit) {
        Charset charset = form.getCharset() == null ? StandardCharsets.UTF_8 : form.getCharset();
        long length = 0;
        for (FormDataPart part : form) {
            if (length > limit) {
                break;
            }
            if (length > 0) {
                length++;
            }
            length += URLEncoder.encode(part.name(), charset).length();
            length++;
            length += part.value() == null
                    ? 0
                    : URLEncoder.encode(part.value(), charset).length();
        }
        return length;
    }
}
