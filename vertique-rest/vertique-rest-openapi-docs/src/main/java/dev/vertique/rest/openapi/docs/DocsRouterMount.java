// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.Collections;
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
 *
 * <p>The mount refuses to create its router until the documentation module's composition validator
 * has marked it validated, so an {@code HttpVerticle} that skips its composition validators cannot
 * host it. Each composition builds its own mount, so a mark never carries over.
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
    private final Set<SecuritySchemeHandler> securitySchemeHandlers;
    private final Optional<AuthEnforcementCapability> authEnforcement;
    private boolean validated;

    /**
     * Creates the mount.
     *
     * @param prefix the configured documentation prefix, without a trailing slash
     * @param documents the enabled documents
     * @param store the store the documents are read from
     * @param cacheControl the {@code Cache-Control} value of every response
     * @param securitySchemeHandlers the registered security scheme handlers, whose scheme names the
     *     protected documents are checked against
     * @param authEnforcement the authentication enforcement capability, empty when it is not
     *     installed
     */
    DocsRouterMount(
            String prefix,
            EnabledDocuments documents,
            DocumentStore store,
            String cacheControl,
            Set<SecuritySchemeHandler> securitySchemeHandlers,
            Optional<AuthEnforcementCapability> authEnforcement) {
        this.prefix = prefix;
        this.documents = documents;
        this.store = store;
        this.cacheControl = cacheControl;
        this.securitySchemeHandlers = securitySchemeHandlers;
        this.authEnforcement = authEnforcement;
    }

    /** Records that the composition validator has checked this mount. */
    void markValidated() {
        validated = true;
    }

    /**
     * Reports whether the composition validator has checked this mount.
     *
     * @return {@code true} once {@link #markValidated()} has been called
     */
    boolean isValidated() {
        return validated;
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
     * @throws RestConfigurationException first, when the composition validator has not marked this
     *     mount validated, because the hosting {@code HttpVerticle} was built without composition
     *     validators; when a protected document names a security scheme no
     *     registered handler has, or authentication enforcement is not installed, in one exception
     *     listing every violation; or when an enabled document is protected and not served yet;
     *     in every case before any route is registered
     */
    @Override
    public Future<Router> createRouter(Vertx vertx) {
        if (!validated) {
            throw new RestConfigurationException("Documentation mount '" + MOUNT_ID + "' at '" + mountPath()
                    + "' cannot create its router: the hosting HttpVerticle was built without composition"
                    + " validators, such as with the public five-argument constructor or by a subclass;"
                    + " obtain HttpVerticle from Dagger so its composition validators run before any mount"
                    + " router is created");
        }
        checkProtectedDocuments();
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

    /** Checks the {@link ApiDocs} values of the protected documents and throws one exception for all. */
    private void checkProtectedDocuments() {
        List<String> violations = new ArrayList<>();
        for (EnabledDocuments.EnabledDocument document : documents.all()) {
            if (document.access() != ApiDocs.Access.PROTECTED) {
                continue;
            }
            String scheme =
                    document.declaringType().getAnnotation(ApiDocs.class).securityScheme();
            String subject = "Application '" + document.name() + "' (declared by "
                    + document.declaringType().getName() + "): ";
            if (securitySchemeHandlers.stream().noneMatch(handler -> scheme.equals(handler.schemeName()))) {
                violations.add(subject + "@ApiDocs.securityScheme names scheme '" + scheme
                        + "', but no registered SecuritySchemeHandler has that name");
            }
            if (authEnforcement.isEmpty()) {
                violations.add(
                        subject + "@ApiDocs.access is PROTECTED, but authentication enforcement is not installed");
            }
        }
        if (violations.isEmpty()) {
            return;
        }
        Collections.sort(violations);
        if (violations.size() == 1) {
            throw new RestConfigurationException(violations.get(0));
        }
        throw new RestConfigurationException("Invalid @ApiDocs values:\n" + String.join("\n", violations));
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
