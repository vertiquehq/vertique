// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.serving;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperation;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperationInstaller;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.config.DocumentPolicies;
import dev.vertique.rest.openapi.docs.config.EnabledDocuments;
import dev.vertique.rest.openapi.docs.document.PublishedDocument;
import dev.vertique.rest.openapi.docs.publication.DocumentStore;
import dev.vertique.security.authz.AccessPolicy;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Installs the routes of the protected documents as synthetic operations, so each document URL runs
 * the chain of an equally annotated resource method: the scheme's authentication handler, then every
 * registered operation handler contributor, then the document's terminal handler. A protected
 * document is any document whose policy is not exactly one public requirement, so a document whose
 * policy denies every reader is installed too: its readers are authenticated and then refused.
 *
 * <p>The access policy of a document comes only from the {@link ApiDocs} on its application's
 * declaring interface: its {@code securityScheme} guards both forms, and its {@code policy} is the
 * typed access policy both forms are governed by. No configuration value changes it. Each form is
 * one operation, {@code apidocs:<name>:json} or {@code apidocs:<name>:yaml}, answering {@code GET}
 * and {@code HEAD}, whose application name is the documented application's name. No authorization
 * of its own is added and no contributor is selected: the policy's requirements, including a
 * required action, are enforced by the contributors registered for every operation.
 *
 * <p>The terminal handler of a document whose policy is a lone {@code @DenyAll} fails every request
 * with {@code 403} itself, before it reads the store, so that document never depends on a contributor
 * being in the chain to be refused.
 *
 * <p>The terminal handler never continues to another route or mount. A request whose normalized path
 * is not the exact document URL fails with {@code 404}, and a document the store does not hold yet
 * fails with {@code 503}, both answered by the synthetic route's own failure handler. Otherwise the
 * response carries the strong entity tag, {@code Cache-Control: private, no-store}, and a
 * {@code Vary} on the header that carries the scheme's credential, when there is one; a matching
 * {@code If-None-Match} answers {@code 304}, and the conditional evaluation and the bytes happen only
 * here, after the whole chain has passed.
 */
final class ProtectedDocumentRoutes {

    private final String prefix;
    private final DocumentStore store;
    private final SyntheticOperationInstaller syntheticOperationInstaller;
    private final Set<SecuritySchemeHandler> securitySchemeHandlers;

    /**
     * Creates the installer of the protected document routes.
     *
     * @param prefix the configured documentation prefix, without a trailing slash
     * @param store the store the documents are read from
     * @param syntheticOperationInstaller the installer of the synthetic operations
     * @param securitySchemeHandlers the registered security scheme handlers, whose descriptions decide
     *     the {@code Vary} header
     */
    ProtectedDocumentRoutes(
            String prefix,
            DocumentStore store,
            SyntheticOperationInstaller syntheticOperationInstaller,
            Set<SecuritySchemeHandler> securitySchemeHandlers) {
        this.prefix = prefix;
        this.store = store;
        this.syntheticOperationInstaller = syntheticOperationInstaller;
        this.securitySchemeHandlers = securitySchemeHandlers;
    }

    /**
     * Installs the JSON and YAML routes of every protected document, in the given order.
     *
     * @param router the documentation router
     * @param protectedDocuments the enabled protected documents
     * @throws RestConfigurationException when an operation is rejected; its message starts with the
     *     document's origin, and routes of documents installed earlier remain on the router
     */
    void install(Router router, List<EnabledDocuments.EnabledDocument> protectedDocuments) {
        for (EnabledDocuments.EnabledDocument document : protectedDocuments) {
            install(router, document);
        }
    }

    private void install(Router router, EnabledDocuments.EnabledDocument document) {
        String name = document.name();
        ApiDocs apiDocs = document.declaringType().getAnnotation(ApiDocs.class);
        String scheme = apiDocs.securityScheme();
        String origin = "Protected API document of application '" + name + "' (access policy: @ApiDocs on "
                + document.declaringType().getName() + ")";
        Class<? extends AccessPolicy> policy = apiDocs.policy();
        boolean denyAll = DocumentPolicies.isDenyAll(policy);
        Optional<String> vary = DocumentCachePolicy.protectedVary(securitySchemeHandlers.stream()
                .filter(handler -> scheme.equals(handler.schemeName()))
                .findFirst()
                .flatMap(SecuritySchemeHandler::openApiDescription));
        installForm(
                router,
                name,
                origin,
                scheme,
                policy,
                denyAll,
                "json",
                DocumentResponses.JSON_TYPE,
                PublishedDocument::json,
                PublishedDocument::jsonTag,
                vary);
        installForm(
                router,
                name,
                origin,
                scheme,
                policy,
                denyAll,
                "yaml",
                DocumentResponses.YAML_TYPE,
                PublishedDocument::yaml,
                PublishedDocument::yamlTag,
                vary);
    }

    private void installForm(
            Router router,
            String name,
            String origin,
            String scheme,
            Class<? extends AccessPolicy> policy,
            boolean denyAll,
            String form,
            String contentType,
            Function<PublishedDocument, byte[]> bytes,
            Function<PublishedDocument, String> tag,
            Optional<String> vary) {
        String operationId = "apidocs:" + name + ":" + form;
        SyntheticOperation operation = SyntheticOperation.withPolicy(origin, operationId, scheme, name, policy);
        String relativePath = "/" + name + "/openapi." + form;
        String exactPath = prefix + relativePath;
        syntheticOperationInstaller.install(
                router,
                relativePath,
                List.of(HttpMethod.GET, HttpMethod.HEAD),
                operation,
                ctx -> serve(ctx, name, exactPath, denyAll, contentType, bytes, tag, vary));
    }

    private void serve(
            RoutingContext ctx,
            String name,
            String exactPath,
            boolean denyAll,
            String contentType,
            Function<PublishedDocument, byte[]> bytes,
            Function<PublishedDocument, String> tag,
            Optional<String> vary) {
        // A policy that denies every reader never reaches the store, whatever the chain before it did.
        if (denyAll) {
            ctx.fail(403);
            return;
        }
        // An exact-path route also matches its trailing-slash variants, so the path is compared again.
        if (!exactPath.equals(ctx.normalizedPath())) {
            ctx.fail(404);
            return;
        }
        Optional<PublishedDocument> stored = store.lookup(name);
        if (stored.isEmpty()) {
            ctx.fail(503);
            return;
        }
        DocumentResponses.write(
                ctx, stored.get(), contentType, bytes, tag, DocumentCachePolicy.PROTECTED_CACHE_CONTROL, vary);
    }
}
