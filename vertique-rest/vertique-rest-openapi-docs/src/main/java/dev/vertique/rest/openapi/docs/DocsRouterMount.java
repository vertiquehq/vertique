// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.publication.SyntheticOperations;
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
 * nothing else. A request whose URL and method match no document route continues to the mounts
 * after it, and so does a request a public document's route passes on; a protected document's route
 * never continues, so even a trailing-slash variant of its URL ends with {@code 404}.
 *
 * <p>The mount reads each document from the {@link DocumentStore} when a request arrives, because
 * the documents are stored after this router is built.
 *
 * <p>A public document is served to any caller. A request for it whose normalized path is not the
 * exact document URL, or for a document the store does not hold yet, continues to the later mounts.
 * Its {@code Cache-Control} value is never weaker than the configured default.
 *
 * <p>A protected document is served through the resource security chain its application's
 * {@link ApiDocs} declares, installed by {@link ProtectedDocumentRoutes}. Its route never continues
 * to a later mount: a denial, a non-exact path, and a document the store does not hold yet each end
 * on the route's own failure handler. Its responses carry {@code Cache-Control: private, no-store}
 * and a {@code Vary} on the header that carries the scheme's credential, when there is one.
 *
 * <p>A response carries the exact content type, the length, the strong entity tag of the form, and
 * the document's {@code Cache-Control} value. A request whose {@code If-None-Match} lists the entity
 * tag, in weak comparison, or {@code *} gets {@code 304} with the entity tag and the caching headers
 * and no body. Every response wraps the stored bytes in a fresh {@link Buffer}.
 *
 * <p>The mount refuses to create its router until the documentation module's composition validator
 * has marked it validated, so an {@code HttpVerticle} that skips its composition validators cannot
 * host it. Each composition builds its own mount, so a mark never carries over.
 */
final class DocsRouterMount implements RouterMount {

    /** The mount id of the documentation mount, as mount customizers see it. */
    static final String MOUNT_ID = "apidocs";

    /** The content type of a document's JSON form. */
    static final String JSON_TYPE = "application/json";

    /** The content type of a document's YAML form. */
    static final String YAML_TYPE = "application/yaml";

    private static final String JSON_FILE = "openapi.json";
    private static final String YAML_FILE = "openapi.yaml";
    private static final String WEAK_PREFIX = "W/";
    private static final String ROLELESS_PROTECTED = "roleless-protected";

    private final String prefix;
    private final EnabledDocuments documents;
    private final DocumentStore store;
    private final String cacheControl;
    private final Set<SecuritySchemeHandler> securitySchemeHandlers;
    private final Optional<AuthEnforcementCapability> authEnforcement;
    private final SyntheticOperations syntheticOperations;
    private final DocumentWarnings warnings;
    // Plain field, not volatile or atomic: HttpVerticle runs the composition validators and then the
    // sequential createRouter chain on the same verticle context within one start, and the unscoped
    // provider gives every composition a fresh instance, so no two threads share this flag.
    private boolean validated;

    /**
     * Creates the mount.
     *
     * @param prefix the configured documentation prefix, without a trailing slash
     * @param documents the enabled documents
     * @param store the store the documents are read from
     * @param cacheControl the {@code Cache-Control} value of every public document response
     * @param securitySchemeHandlers the registered security scheme handlers, whose scheme names the
     *     protected documents are checked against and whose descriptions decide their {@code Vary}
     * @param authEnforcement the authentication enforcement capability, empty when it is not
     *     installed
     * @param syntheticOperations the installer the protected document routes are installed through
     * @param warnings the documentation module's warnings of the component
     */
    DocsRouterMount(
            String prefix,
            EnabledDocuments documents,
            DocumentStore store,
            String cacheControl,
            Set<SecuritySchemeHandler> securitySchemeHandlers,
            Optional<AuthEnforcementCapability> authEnforcement,
            SyntheticOperations syntheticOperations,
            DocumentWarnings warnings) {
        this.prefix = prefix;
        this.documents = documents;
        this.store = store;
        this.cacheControl = cacheControl;
        this.securitySchemeHandlers = securitySchemeHandlers;
        this.authEnforcement = authEnforcement;
        this.syntheticOperations = syntheticOperations;
        this.warnings = warnings;
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
     *     validators; when a protected document names a security scheme no registered handler has,
     *     or authentication enforcement is not installed, in one exception listing every violation,
     *     before any route is registered; or when the installation of a protected document's route
     *     is rejected, with the message starting with that document's origin, after every route has
     *     been removed again
     */
    @Override
    public Future<Router> createRouter(Vertx vertx) {
        requireValidated();
        Router router = Router.router(vertx);
        buildInto(router);
        return Future.succeededFuture(router);
    }

    /**
     * Builds the documentation routes into the given router. Repeats the composition-validated check
     * so a direct call cannot bypass it, runs the protected-document checks, installs the protected
     * document routes through the synthetic operation installer, when there is a protected document,
     * and then registers the public document routes. Once every route is in place, a protected
     * document that lists no role logs one notice per component.
     *
     * @param router the router to register the routes on
     * @throws RestConfigurationException when the mount is not validated or a protected document is
     *     invalid, before any route is registered; or when the installation of a protected document's
     *     route is rejected, after every route has been removed from the router again
     */
    void buildInto(Router router) {
        requireValidated();
        checkProtectedDocuments();
        List<EnabledDocuments.EnabledDocument> protectedDocuments = documents.all().stream()
                .filter(document -> document.access() == ApiDocs.Access.PROTECTED)
                .toList();
        try {
            if (!protectedDocuments.isEmpty()) {
                new ProtectedDocumentRoutes(prefix, store, syntheticOperations, securitySchemeHandlers)
                        .install(router, protectedDocuments);
            }
            for (EnabledDocuments.EnabledDocument document : documents.all()) {
                if (document.access() != ApiDocs.Access.PUBLIC) {
                    continue;
                }
                register(
                        router,
                        document.name(),
                        JSON_FILE,
                        JSON_TYPE,
                        PublishedDocument::json,
                        PublishedDocument::jsonTag);
                register(
                        router,
                        document.name(),
                        YAML_FILE,
                        YAML_TYPE,
                        PublishedDocument::yaml,
                        PublishedDocument::yamlTag);
            }
        } catch (RuntimeException failure) {
            // Routes of documents installed before the failure already exist; none may serve.
            router.clear();
            throw failure;
        }
        for (EnabledDocuments.EnabledDocument document : protectedDocuments) {
            ApiDocs apiDocs = document.declaringType().getAnnotation(ApiDocs.class);
            if (apiDocs.rolesAllowed().length == 0) {
                warnings.infoOnce(
                        ROLELESS_PROTECTED,
                        document.name(),
                        "apidocs.documents." + document.name()
                                + ": the protected document is readable by any principal the '"
                                + apiDocs.securityScheme() + "' handler authenticates; @ApiDocs on "
                                + document.declaringType().getName() + " lists no rolesAllowed");
            }
        }
    }

    /** Throws unless the composition validator has marked this mount validated. */
    private void requireValidated() {
        if (!validated) {
            throw new RestConfigurationException("Documentation mount '" + MOUNT_ID + "' at '" + mountPath()
                    + "' cannot create its router: the hosting HttpVerticle was built without composition"
                    + " validators, such as with the public five-argument constructor or by a subclass;"
                    + " obtain HttpVerticle from Dagger so its composition validators run before any mount"
                    + " router is created");
        }
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
        // One route for both methods: with a route per method, a request the first route passes on
        // would match the other route's path but not its method, and end with 405 instead of
        // continuing to the later mounts.
        router.route(relativePath)
                .method(HttpMethod.GET)
                .method(HttpMethod.HEAD)
                .handler(ctx -> serve(ctx, name, exactPath, contentType, bytes, tag));
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
        write(ctx, stored.get(), contentType, bytes, tag, cacheControl, Optional.empty());
    }

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
