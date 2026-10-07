// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.serving;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.jaxrs.application.RestApplications.ContractOrigin;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperation;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperationInstaller;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.config.EnabledDocuments;
import dev.vertique.rest.openapi.docs.document.PublicationFingerprint;
import dev.vertique.rest.openapi.docs.document.PublishedDocument;
import dev.vertique.rest.openapi.docs.publication.DocumentStore;
import dev.vertique.rest.openapi.docs.publication.PublicationAccess;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import jakarta.annotation.security.DenyAll;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit proof that the terminal handler of a protected document whose policy is a lone {@code
 * @DenyAll} refuses every request on its own, without relying on an authorization contributor being
 * in the chain.
 *
 * <p>The routes are installed by an installer that registers the terminal handler directly, so the
 * chain holds no authentication handler and no contributor: whatever the handler answers is the
 * handler's own decision. The store holds a real document, so a handler that did reach the store
 * would answer {@code 200} with an entity tag and the bytes. A document whose policy only requires
 * authentication is read through the same harness as the control, which shows the harness reaches
 * the terminal handler and serves the stored bytes.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ProtectedDocumentRoutesTest {

    private static final String HOST = "127.0.0.1";
    private static final String PREFIX = "/apidocs";
    private static final String NAME = "internal";
    private static final long WAIT_SECONDS = 10;
    private static final String JSON_BODY = "{\"stored\":true}";

    /** Refuses every reader. */
    @DenyAll
    public interface DenyEveryone extends AccessPolicy {}

    /** Requires authentication only. */
    @Authorized
    public interface AnyAuthenticated extends AccessPolicy {}

    /** Declares a document nobody may read. */
    @ApiDocs(policy = DenyEveryone.class, securityScheme = "bearerAuth")
    interface DenyDeclaration {}

    /** Declares a document any authenticated reader may read. */
    @ApiDocs(policy = AnyAuthenticated.class, securityScheme = "bearerAuth")
    interface AuthenticatedDeclaration {}

    private Vertx vertx;
    private HttpServer server;
    private WebClient client;
    private DocumentStore store;

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        client = WebClient.create(vertx);
        store = PublicationAccess.newStore();
        PublicationFingerprint fingerprint =
                new PublicationFingerprint(new PublicationFingerprint.MountPart("", "", "", ""), new TreeMap<>());
        PublishedDocument document = new PublishedDocument(
                JSON_BODY.getBytes(StandardCharsets.UTF_8),
                "stored: true\n".getBytes(StandardCharsets.UTF_8),
                "\"json-tag\"",
                "\"yaml-tag\"",
                fingerprint);
        PublicationAccess.store(store, NAME, vertx.getOrCreateContext(), document)
                .toCompletionStage()
                .toCompletableFuture()
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        client.close();
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("A deny-everyone document refuses every request itself, with no contributor in the chain")
    void denyEveryoneDocumentIsRefusedByItsTerminalHandler() throws Exception {
        // Given: the routes of a deny-everyone document, installed with no authentication handler and
        // no contributor, over a store that holds its document
        int port = serve(DenyDeclaration.class);

        for (String form : List.of("json", "yaml")) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
                // When: the form is requested
                HttpResponse<?> reply = request(port, method, PREFIX + "/" + NAME + "/openapi." + form);

                // Then: 403, no entity tag, and no document bytes
                String label = method + " " + form;
                assertEquals(403, reply.statusCode(), label);
                assertNull(reply.getHeader("ETag"), label + " carried an entity tag");
                assertTrue(reply.body() == null || reply.bodyAsString().isEmpty(), label + " carried a body");
            }
        }
    }

    @Test
    @DisplayName(
            "Control: an authenticated-only document is served by the same harness, so the proof reaches the handler")
    void authenticatedOnlyDocumentIsServedByTheSameHarness() throws Exception {
        // Given: the same harness with a document whose policy only requires authentication
        int port = serve(AuthenticatedDeclaration.class);

        // When: its JSON form is requested
        HttpResponse<?> reply = request(port, HttpMethod.GET, PREFIX + "/" + NAME + "/openapi.json");

        // Then: the terminal handler reaches the store and serves the bytes with the entity tag
        assertEquals(200, reply.statusCode());
        assertEquals("\"json-tag\"", reply.getHeader("ETag"));
        assertEquals(JSON_BODY, reply.bodyAsString());
    }

    private int serve(Class<?> declaringType) throws Exception {
        Router documents = Router.router(vertx);
        EnabledDocuments.EnabledDocument document = new EnabledDocuments.EnabledDocument(
                NAME, declaringType, ApiDocs.Access.PROTECTED, "/*", ContractOrigin.GLOBAL, null, null, null);
        new ProtectedDocumentRoutes(PREFIX, store, new DirectInstaller(), Set.of())
                .install(documents, List.of(document));
        Router root = Router.router(vertx);
        root.route(PREFIX + "/*").subRouter(documents);
        server = vertx.createHttpServer()
                .requestHandler(root)
                .listen(0, HOST)
                .toCompletionStage()
                .toCompletableFuture()
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
        return server.actualPort();
    }

    private HttpResponse<?> request(int port, HttpMethod method, String path) throws Exception {
        return client.request(method, port, HOST, path)
                .send()
                .toCompletionStage()
                .toCompletableFuture()
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * An installer that registers the terminal handler alone, answering a failure with its status, so
     * the handler's own decision is all that a request meets.
     */
    private static final class DirectInstaller implements SyntheticOperationInstaller {

        @Override
        public void install(
                Router router,
                String path,
                List<HttpMethod> methods,
                SyntheticOperation operation,
                Handler<RoutingContext> terminal) {
            Route route = router.route(path);
            methods.forEach(route::method);
            route.handler(terminal);
            route.failureHandler(
                    ctx -> ctx.response().setStatusCode(ctx.statusCode()).end());
        }
    }
}
