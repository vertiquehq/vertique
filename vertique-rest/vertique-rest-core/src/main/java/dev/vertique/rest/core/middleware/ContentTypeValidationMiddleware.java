// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.middleware;

import dev.vertique.rest.core.ProblemDetail;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;
import java.util.Locale;

/**
 * API-scoped middleware that validates the {@code Content-Type} header on requests with bodies.
 * Rejects POST, PUT, and PATCH requests whose {@code Content-Type} is not one of the broadly
 * accepted types.
 *
 * <p>Accepted content types:
 * <ul>
 *   <li>{@code application/*} (all application subtypes — json, xml, octet-stream, form-urlencoded, etc.)</li>
 *   <li>{@code multipart/form-data}</li>
 *   <li>{@code text/*} (any text subtype)</li>
 * </ul>
 *
 * <p>This middleware acts as a broad router-level safety net for all routes. Fine-grained
 * per-route {@code Content-Type} validation against an operation's {@code @Consumes} list is
 * enforced per-route at registration time by {@code JaxRsRouteRegistrar}, which installs a
 * dedicated 415-check handler on each route that declares a non-empty {@code @Consumes}
 * annotation (after the operation's identity and authentication handlers).
 *
 * <p>Returns 415 Unsupported Media Type when the {@code Content-Type} header is absent or not
 * accepted, except when the request has no body, in which case validation is skipped. A non-empty
 * body the body handler read always counts, including one an HTTP/2 request sent in DATA frames
 * without {@code Content-Length} or {@code Transfer-Encoding}. Otherwise a request has a body when
 * it carries {@code Transfer-Encoding} or a non-zero {@code Content-Length}, or when the body
 * handler treats it as multipart or form (by its {@code Content-Type}). A declared
 * {@code Content-Length: 0} without {@code Transfer-Encoding} is empty.
 *
 * <p>Priority: 20 (APPLICATION phase).
 */
public class ContentTypeValidationMiddleware implements Middleware {

    /**
     * Returns the execution priority for this middleware.
     *
     * @return {@code 20} — API-scoped, runs after root-scoped middlewares in the APPLICATION phase
     */
    @Override
    public int priority() {
        return 20;
    }

    @Override
    public MiddlewareScope scope() {
        return MiddlewareScope.API;
    }

    @Override
    public void handle(RoutingContext ctx) {
        HttpMethod method = ctx.request().method();

        if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            // Skip validation if the request has no body. A body the body handler read always
            // counts: an HTTP/2 request may carry DATA frames with neither Content-Length nor
            // Transfer-Encoding. Otherwise the request has a body when it carries Transfer-Encoding
            // or a Content-Length, or when the body handler treats it as multipart or form (a
            // request flag it sets from the Content-Type, not proof of a decoded body). An explicit
            // Content-Length of 0 with no Transfer-Encoding suppresses only those header signals.
            // The per-route @Consumes gate in vertique-rest-jaxrs applies the same rule; change both
            // together.
            String contentLength = ctx.request().getHeader("Content-Length");
            String transferEncoding = ctx.request().getHeader("Transfer-Encoding");
            io.vertx.ext.web.RequestBody readBody = ctx.body();
            boolean declaredEmpty = "0".equals(contentLength) && transferEncoding == null;
            boolean readNonEmpty = readBody != null && readBody.length() > 0;
            boolean hasBody = readNonEmpty
                    || (!declaredEmpty
                            && (contentLength != null
                                    || transferEncoding != null
                                    || ctx.request().isExpectMultipart()));

            if (hasBody) {
                String contentType = ctx.request().getHeader("Content-Type");
                if (contentType == null || !isAcceptedContentType(contentType)) {
                    // Author the ProblemDetail on the JAX-RS Response so equal-status detail
                    // sanitization in ErrorPipeline keeps this framework diagnostic (a bare
                    // NotSupportedException(message) would have its message dropped).
                    Response unsupported = Response.status(415)
                            .entity(ProblemDetail.of(415, "Unsupported Content-Type"))
                            .type("application/problem+json")
                            .build();
                    ctx.fail(415, new jakarta.ws.rs.NotSupportedException(unsupported));
                    return;
                }
            }
        }

        ctx.next();
    }

    /**
     * Returns {@code true} when the {@code Content-Type} value is in the set of broadly accepted
     * types: any {@code application/*} subtype, {@code multipart/form-data}, or any {@code text/*}
     * subtype. Per-route {@code @Consumes} narrowing is enforced separately by
     * {@code JaxRsRouteRegistrar} at registration time.
     *
     * @param contentType the raw {@code Content-Type} header value (not blank)
     * @return {@code true} if the content type is in the accepted set
     */
    private boolean isAcceptedContentType(String contentType) {
        String lower = contentType.toLowerCase(Locale.ROOT).trim();
        // Accept all application/* subtypes (json, xml, octet-stream, form-urlencoded, etc.)
        if (lower.startsWith("application/")) {
            return true;
        }
        // Accept multipart form data
        if (lower.startsWith("multipart/form-data")) {
            return true;
        }
        // Accept all text subtypes (plain, html, csv, etc.)
        return lower.startsWith("text/");
    }
}
