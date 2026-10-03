// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.serving;

import dev.vertique.rest.openapi.docs.document.PublishedDocument;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Writes the response of a stored document's JSON or YAML form with its caching and entity-tag
 * headers, answering a matching conditional request with {@code 304}. The public document routes and
 * the protected document routes share it.
 */
final class DocumentResponses {

    /** The content type of a document's JSON form. */
    static final String JSON_TYPE = "application/json";

    /** The content type of a document's YAML form. */
    static final String YAML_TYPE = "application/yaml";

    private static final String WEAK_PREFIX = "W/";

    private DocumentResponses() {}

    /**
     * Answers a request with a stored document form: the strong entity tag, the given
     * {@code Cache-Control} value, and {@code Vary} when present, added to any {@code Vary} value an earlier
     * handler already set; {@code 304} with no body when
     * {@code If-None-Match} matches the entity tag; otherwise the content type and length, and the
     * bytes unless the method is {@code HEAD}.
     *
     * @param ctx the routing context
     * @param document the stored document
     * @param contentType the content type of the form
     * @param bytes the bytes of the form
     * @param tag the entity tag of the form
     * @param cacheControl the {@code Cache-Control} value of the response
     * @param vary the {@code Vary} value to add to the response, empty when it adds none
     */
    static void write(
            RoutingContext ctx,
            PublishedDocument document,
            String contentType,
            Function<PublishedDocument, byte[]> bytes,
            Function<PublishedDocument, String> tag,
            String cacheControl,
            Optional<String> vary) {
        String entityTag = tag.apply(document);
        HttpServerResponse response =
                ctx.response().putHeader("ETag", entityTag).putHeader("Cache-Control", cacheControl);
        vary.ifPresent(value -> response.headers().add("Vary", value));
        if (matches(ctx.request().headers().getAll("If-None-Match"), entityTag)) {
            response.setStatusCode(304).end();
            return;
        }
        byte[] body = bytes.apply(document);
        response.putHeader("Content-Type", contentType).putHeader("Content-Length", Integer.toString(body.length));
        if (ctx.request().method() == HttpMethod.HEAD) {
            response.end();
        } else {
            response.end(Buffer.buffer(body));
        }
    }

    /**
     * Reports whether an {@code If-None-Match} field matches an entity tag. Each field value is a
     * comma-separated list; a member is trimmed, and matches when it is {@code *} or equals the tag
     * once a leading {@code W/} is dropped, which is weak comparison.
     */
    private static boolean matches(List<String> fieldValues, String entityTag) {
        for (String fieldValue : fieldValues) {
            for (String member : fieldValue.split(",")) {
                String candidate = member.trim();
                if (candidate.equals("*")) {
                    return true;
                }
                if (candidate.startsWith(WEAK_PREFIX)) {
                    candidate = candidate.substring(WEAK_PREFIX.length());
                }
                if (candidate.equals(entityTag)) {
                    return true;
                }
            }
        }
        return false;
    }
}
