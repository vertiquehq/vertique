// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.core.payload.PayloadSource;
import dev.vertique.core.payload.PayloadSources;
import dev.vertique.http.client.exception.HttpBodyLimitException;
import dev.vertique.http.client.exception.HttpBodyLimitException.Direction;
import dev.vertique.http.client.interceptor.HttpClientRequestContext;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.internal.FutureInternal;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.impl.ClientPhase;
import io.vertx.ext.web.client.impl.HttpContext;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.multipart.FormDataPart;
import io.vertx.ext.web.multipart.MultipartForm;
import io.vertx.uritemplate.Variables;
import jakarta.annotation.Nullable;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Governance interceptor for the request-preparation phase: it refuses relative requests of a client
 * without a base URL, applies the request header rules to what the caller set, refuses a request body
 * over the request bound, adds the client's default headers and wraps the response body codec so the
 * response bound holds whatever codec the caller chose. When interceptors are registered it then folds
 * their {@code beforeRequest} over the request, applies the result and takes the request snapshot,
 * all before credentials.
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
    private final HttpClientInterceptors interceptors;

    PrepareInterceptor(ClientSettings settings, HttpClientInterceptors interceptors) {
        this.settings = settings;
        this.interceptors = interceptors;
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
        String violation = HeaderRules.violation(headers);
        if (violation != null) {
            ctx.fail(new IllegalArgumentException(violation));
            return;
        }
        long maxRequestBytes = settings.maxRequestBytes();
        AttemptScope.Exchange exchange = ExchangeTracker.exchangeOf(ctx);
        boolean capture = settings.captureBodies() && exchange != null && !interceptors.isEmpty();
        Measured measured = measure(ctx, maxRequestBytes, capture);
        if (measured.payload() != null) {
            exchange.requestBody(measured.payload());
        }
        if (maxRequestBytes > 0 && !measured.fits()) {
            ctx.fail(new HttpBodyLimitException(Direction.REQUEST, maxRequestBytes));
            return;
        }
        for (Map.Entry<String, String> header : settings.defaultHeaders().entrySet()) {
            if (!headers.contains(header.getKey())) {
                headers.add(header.getKey(), header.getValue());
            }
        }
        if (interceptors.isEmpty() || exchange == null) {
            proceed(ctx);
            return;
        }
        HttpClientRequestContext seed = interceptors.describe(request, exchange.callId());
        exchange.snapshot(seed);
        Context owner = ctx.future() instanceof FutureInternal<?> future ? future.context() : null;
        interceptors.prepare(seed).onComplete(result -> {
            if (owner != null && Vertx.currentContext() != owner) {
                owner.runOnContext(ignored -> prepared(ctx, exchange, seed, result));
            } else {
                prepared(ctx, exchange, seed, result);
            }
        });
    }

    /**
     * Applies what the interceptors returned: the final headers and URI go into the request, the
     * snapshot the interceptors are shown on completion is taken, and the chain goes on to egress and
     * credentials. A failed fold, an invalid header or an unusable URI fails the exchange instead.
     */
    private void prepared(
            HttpContext<?> ctx,
            AttemptScope.Exchange exchange,
            HttpClientRequestContext seed,
            AsyncResult<HttpClientRequestContext> result) {
        if (ctx.future().isComplete()) {
            return;
        }
        if (exchange.isAbandoned()) {
            ctx.fail(new IllegalStateException(AttemptScope.ENDED_MESSAGE));
            return;
        }
        if (result.failed()) {
            ctx.fail(result.cause());
            return;
        }
        HttpClientRequestContext prepared = result.result();
        String preparedViolation = HeaderRules.violation(prepared.headers());
        if (preparedViolation != null) {
            ctx.fail(new IllegalArgumentException(preparedViolation));
            return;
        }
        HttpRequest<?> request = ctx.request();
        if (!prepared.uri().equals(seed.uri())) {
            try {
                HttpClientInterceptors.applyUri(request, prepared.uri());
            } catch (IllegalArgumentException unusable) {
                ctx.fail(unusable);
                return;
            }
        }
        MultiMap headers = request.headers();
        headers.clear();
        headers.addAll(prepared.headers());
        exchange.snapshot(prepared);
        exchange.target(interceptors.targetOf(request));
        proceed(ctx);
    }

    /** Marks the context prepared and continues, wrapping the response body codec when a bound applies. */
    private void proceed(HttpContext<?> ctx) {
        ctx.set(PREPARED, Boolean.TRUE);
        long maxResponseBytes = settings.maxResponseBytes();
        if (maxResponseBytes > 0 && !(ctx.request().bodyCodec() instanceof StreamCodec)) {
            bindResponseBound(ctx, maxResponseBytes, settings.captureBodies());
            return;
        }
        ctx.next();
    }

    /**
     * Prepares a copy of the request again with a body codec that counts the bytes it passes on; the
     * second pass continues the chain. The copy keeps the caller's request unchanged, because
     * {@code as(codec)} changes a request in place and a resent request must not be wrapped twice.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void bindResponseBound(HttpContext<?> context, long limit, boolean capture) {
        HttpContext ctx = context;
        HttpRequest request = ctx.request();
        AttemptScope.Exchange exchange = ExchangeTracker.exchangeOf(ctx);
        // a Buffer codec hands its Buffer on as it is, so only another codec needs the bytes copied
        Consumer<Buffer> copied =
                capture && exchange != null && request.bodyCodec() != BodyCodec.buffer() ? exchange::copiedBytes : null;
        BodyCodec bounded =
                new BoundedBodyCodec(request.bodyCodec(), limit, () -> ExchangeTracker.overrun(ctx, limit), copied);
        ctx.prepareRequest(copyOf(request).as(bounded), ctx.contentType(), ctx.body());
    }

    /** Copies a request with the URI-template variables and trace operation that {@code copy()} drops. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static HttpRequest copyOf(HttpRequest request) {
        HttpRequest copy = request.copy();
        Variables variables;
        try {
            variables = request.templateParams();
        } catch (IllegalStateException notATemplate) {
            // a request built from a plain string URI has no template variables
            variables = null;
        }
        if (variables != null) {
            for (Object key : variables.names()) {
                String name = (String) key;
                Object value = variables.get(name);
                if (value instanceof String single) {
                    copy.setTemplateParam(name, single);
                } else if (value instanceof List list) {
                    copy.setTemplateParam(name, (List<String>) list);
                } else if (value instanceof Map map) {
                    copy.setTemplateParam(name, (Map<String, String>) map);
                }
            }
        }
        if (request.traceOperation() != null) {
            copy.traceOperation(request.traceOperation());
        }
        return copy;
    }

    /** What the request preparation learned about the request body: whether it fits the bound and its payload. */
    private record Measured(boolean fits, @Nullable PayloadSource payload) {}

    /**
     * Measures the request body as it will be encoded, and describes it for the interceptors when
     * {@code capture} is set. A body that cannot be measured before sending (a stream or a multipart form)
     * does not fit the bound. Nothing is encoded for a client without a bound and without capture, and a JSON
     * body is encoded once for both purposes.
     *
     * @param limit the request bound, {@code 0} for none
     * @param capture whether to describe the body
     */
    private static Measured measure(HttpContext<?> ctx, long limit, boolean capture) {
        boolean bounded = limit > 0;
        Object body = ctx.body();
        if (body == null) {
            return new Measured(true, null);
        }
        if (!bounded && !capture) {
            return new Measured(true, null);
        }
        String contentType = ctx.contentType();
        Buffer encoded = null;
        if (body instanceof Buffer buffer) {
            encoded = buffer;
        } else if (body instanceof MultipartForm form && FORM_URLENCODED.equals(contentType)) {
            if (!capture) {
                return new Measured(encodedFormLength(form, limit) <= limit, null);
            }
            encoded = encodedForm(form);
        } else if (JSON.equals(contentType) && !(body instanceof MultipartForm)) {
            encoded = body instanceof JsonObject json ? json.toBuffer() : Json.encodeToBuffer(body);
        }
        boolean fits = encoded != null && (!bounded || encoded.length() <= limit);
        if (!capture) {
            return new Measured(fits, null);
        }
        if (contentType == null) {
            contentType = ctx.request().headers().get("Content-Type");
        }
        PayloadSource payload = encoded != null && fits
                ? PayloadSources.buffered(encoded, contentType)
                : PayloadSources.streaming(
                        contentType,
                        encoded != null
                                ? encoded.length()
                                : declaredLength(ctx.request().headers()));
        return new Measured(fits, payload);
    }

    /** The {@code Content-Length} the caller declared, or {@code -1} when there is none. */
    private static long declaredLength(MultiMap headers) {
        String declared = headers.get("Content-Length");
        if (declared == null) {
            return -1;
        }
        try {
            long length = Long.parseLong(declared.trim());
            return length < 0 ? -1 : length;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    /** The url-encoded form as written to the wire. */
    private static Buffer encodedForm(MultipartForm form) {
        Charset charset = form.getCharset() == null ? StandardCharsets.UTF_8 : form.getCharset();
        StringBuilder text = new StringBuilder();
        for (FormDataPart part : form) {
            if (text.length() > 0) {
                text.append('&');
            }
            text.append(URLEncoder.encode(part.name(), charset)).append('=');
            if (part.value() != null) {
                text.append(URLEncoder.encode(part.value(), charset));
            }
        }
        return Buffer.buffer(text.toString());
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
