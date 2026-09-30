// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The router mount that serves the enabled documents. It is mounted before every other mount, at
 * the configured prefix followed by {@code /*}, and answers {@code GET} and {@code HEAD} for
 * {@code /<name>/openapi.json} and {@code /<name>/openapi.yaml} of each enabled document and for
 * nothing else: a request for any other URL, method, or name continues to the mounts after it.
 *
 * <p>The mount reads each document from the {@link DocumentStore} when a request arrives, because
 * the documents are stored after this router is built. A request for a document the store does not
 * hold yet continues to the later mounts.
 *
 * <p>A response carries the exact content type, the length, the strong entity tag of the form, and a
 * {@code Cache-Control} value that is never weaker than the configured default. A request whose
 * {@code If-None-Match} lists the entity tag, in weak comparison, or {@code *} gets {@code 304}
 * with the entity tag and {@code Cache-Control} and no body. Every response wraps the stored bytes
 * in a fresh {@link Buffer}.
 */
final class DocsRouterMount implements RouterMount {

    /** The mount id of the documentation mount, as mount customizers see it. */
    static final String MOUNT_ID = "apidocs";

    private static final String JSON_FILE = "openapi.json";
    private static final String YAML_FILE = "openapi.yaml";
    private static final String JSON_TYPE = "application/json";
    private static final String YAML_TYPE = "application/yaml";
    private static final String WEAK_PREFIX = "W/";

    private final String prefix;
    private final EnabledDocuments documents;
    private final DocumentStore store;
    private final String cacheControl;

    /**
     * Creates the mount.
     *
     * @param prefix the configured documentation prefix, without a trailing slash
     * @param documents the enabled documents
     * @param store the store the documents are read from
     * @param cacheControl the {@code Cache-Control} value of every response
     */
    DocsRouterMount(String prefix, EnabledDocuments documents, DocumentStore store, String cacheControl) {
        this.prefix = prefix;
        this.documents = documents;
        this.store = store;
        this.cacheControl = cacheControl;
    }

    @Override
    public String mountPath() {
        return prefix + "/*";
    }

    @Override
    public ExtensionPhase phase() {
        return ExtensionPhase.SYSTEM_FIRST;
    }

    @Override
    public MountMeta meta() {
        return new MountMeta(MOUNT_ID, mountPath(), null, Set.of());
    }

    /**
     * Creates the router of the documents.
     *
     * @param vertx the Vert.x instance
     * @return a future holding the router
     * @throws RestConfigurationException when an enabled document is protected, before any route is
     *     registered
     */
    @Override
    public Future<Router> createRouter(Vertx vertx) {
        for (EnabledDocuments.EnabledDocument document : documents.all()) {
            if (document.access() == ApiDocs.Access.PROTECTED) {
                throw new RestConfigurationException("Application '" + document.name() + "' (declared by "
                        + document.declaringType().getName() + ") sets @ApiDocs.access to PROTECTED, "
                        + "and protected documents are not served yet");
            }
        }
        Router router = Router.router(vertx);
        for (EnabledDocuments.EnabledDocument document : documents.all()) {
            register(
                    router, document.name(), JSON_FILE, JSON_TYPE, PublishedDocument::json, PublishedDocument::jsonTag);
            register(
                    router, document.name(), YAML_FILE, YAML_TYPE, PublishedDocument::yaml, PublishedDocument::yamlTag);
        }
        return Future.succeededFuture(router);
    }

    private void register(
            Router router,
            String name,
            String file,
            String contentType,
            Function<PublishedDocument, byte[]> bytes,
            Function<PublishedDocument, String> tag) {
        String relativePath = "/" + name + "/" + file;
        String exactPath = prefix + relativePath;
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
            router.route(method, relativePath).handler(ctx -> serve(ctx, name, exactPath, contentType, bytes, tag));
        }
    }

    private void serve(
            RoutingContext ctx,
            String name,
            String exactPath,
            String contentType,
            Function<PublishedDocument, byte[]> bytes,
            Function<PublishedDocument, String> tag) {
        // An exact-path route also matches its trailing-slash variants, so the path is compared again.
        if (!exactPath.equals(ctx.normalizedPath())) {
            ctx.next();
            return;
        }
        Optional<PublishedDocument> stored = store.lookup(name);
        if (stored.isEmpty()) {
            ctx.next();
            return;
        }
        PublishedDocument document = stored.get();
        String entityTag = tag.apply(document);
        HttpServerResponse response =
                ctx.response().putHeader("ETag", entityTag).putHeader("Cache-Control", cacheControl);
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
