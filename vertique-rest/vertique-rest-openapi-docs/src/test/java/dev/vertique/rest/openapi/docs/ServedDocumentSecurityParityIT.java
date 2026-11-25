// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.security.parity.ParityApi;
import dev.vertique.rest.openapi.docs.fixture.security.parity.ParityModule;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments.Outcome;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Compares the security a deployed application's served document publishes for each operation with
 * the security its router enforces, across the document, class and method levels, the empty marker
 * and {@code @PermitAll}.
 *
 * <p>The two sides are independent. The published side is the {@code security} of each operation in
 * the document fetched over HTTP. The enforced side is what the router answers to real requests: an
 * anonymous request, a token whose scopes are unrelated to the operation, and a token carrying
 * exactly the scopes the document lists. An operation whose document {@code security} is non-empty
 * must refuse an anonymous caller with {@code 401}, refuse the unrelated token with {@code 403} when
 * the document lists scopes and admit it otherwise, and admit the token carrying the listed scopes;
 * an operation whose document lists no {@code security} must admit all three. The published value of
 * every operation is also checked against a fixed literal, so a document and a router that drift
 * together are still caught. Every operation of the document must be covered by a case.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class ServedDocumentSecurityParityIT {

    private static final String HOST = "127.0.0.1";

    /** The scope no operation lists. */
    private static final String UNRELATED_SCOPE = "unrelated.scope";

    /** One operation: where it is served, its id, and its fixed expected document {@code security}. */
    private record Case(String path, String operationId, String expectedSecurity) {

        @Override
        public String toString() {
            return operationId;
        }
    }

    private static final String NONE = "none";

    private static final String DOC = "[{\"bearerAuth\":[\"" + ParityApi.DOCUMENT_SCOPE + "\"]}]";
    private static final String CLASS = "[{\"bearerAuth\":[\"" + ParityApi.CLASS_SCOPE + "\"]}]";
    private static final String METHOD = "[{\"bearerAuth\":[\"" + ParityApi.METHOD_SCOPE + "\"]}]";
    private static final String SCOPELESS = "[{\"bearerAuth\":[]}]";

    private static final List<Case> CASES = List.of(
            new Case("/doc/inherits", "documentInherits", DOC),
            new Case("/doc/method", "documentMethodReplaces", METHOD),
            new Case("/doc/scopeless", "documentMethodScopeless", SCOPELESS),
            new Case("/doc/open", "documentMethodMarker", NONE),
            new Case("/doc/permit", "documentPermitAllWithMarker", NONE),
            new Case("/cls/inherits", "classInherits", CLASS),
            new Case("/cls/method", "classMethodReplaces", METHOD),
            new Case("/cls/marker", "classMethodMarker", CLASS),
            new Case("/pub/plain", "publicClassPlain", NONE),
            new Case("/pub/own", "publicClassOwn", METHOD));

    private static Vertx vertx;
    private static WebClient client;
    private static Outcome parity;
    private static JsonObject document;
    private static JWTAuth signer;

    @BeforeAll
    static void deployParity(Vertx sharedVertx) throws Exception {
        vertx = sharedVertx;
        client = WebClient.create(vertx);
        signer = JwtAuthFactory.fromSymmetricKey(vertx, "HS256", ParityModule.SIGNING_KEY);
        JsonObject config = DocsConfigs.withDocumentInfo(DocsConfigs.loopback(), ParityApi.NAME, "Parity", "1");
        config.put("jwt", new JsonObject().put("schemeName", ParityApi.BEARER_AUTH));
        vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).clear();
        parity = StartupDeployments.deploy(
                vertx, () -> DaggerServedDocumentSecurityParityComponents_ParityComponent.factory()
                        .create(vertx, config)
                        .httpVerticle());
        assertNull(parity.failure(), () -> "the parity deployment failed: " + parity.failure());
        assertNotNull(parity.port(), "the parity deployment published a port");
        String path = DocsConfigs.DEFAULT_APIDOCS_PATH + "/" + ParityApi.NAME + "/openapi.json";
        HttpResponse<Buffer> response =
                Futures.await(client.get(parity.port(), HOST, path).send(), StartupDeployments.BOUND);
        assertEquals(200, response.statusCode(), () -> "GET " + path + ": " + response.bodyAsString());
        document = new JsonObject(response.bodyAsString());
    }

    @AfterAll
    static void undeployAndCloseTheClient() throws Exception {
        try {
            StartupDeployments.undeploy(vertx, parity);
        } finally {
            vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).clear();
            if (client != null) {
                client.close();
            }
        }
    }

    static Stream<Arguments> operations() {
        return CASES.stream().map(Arguments::of);
    }

    @Test
    @DisplayName("Every operation of the served document is covered by a case")
    void everyServedOperationIsCovered() {
        Set<String> served = new TreeSet<>();
        JsonObject paths = document.getJsonObject("paths");
        assertNotNull(paths, () -> "the document has paths: " + document.encode());
        for (String path : paths.fieldNames()) {
            for (String method : paths.getJsonObject(path).fieldNames()) {
                served.add(method + " " + path);
            }
        }
        Set<String> covered = new TreeSet<>();
        CASES.forEach(c -> covered.add("get " + c.path()));
        assertEquals(covered, served, "the operations the document serves, against the cases");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("operations")
    @DisplayName("The served document's security is what the router enforces")
    void servedSecurityMatchesEnforcement(Case operation) throws Exception {
        // Given: the operation as the served document publishes it
        JsonObject published =
                document.getJsonObject("paths").getJsonObject(operation.path()).getJsonObject("get");
        assertNotNull(published, () -> "the document serves GET " + operation.path());
        assertEquals(operation.operationId(), published.getString("operationId"), "the operation id");
        JsonArray security = published.getJsonArray("security");
        boolean secured = security != null && !security.isEmpty();
        List<String> scopes = scopesOf(security);
        assertEquals(
                operation.expectedSecurity(),
                secured ? security.encode() : NONE,
                () -> "the published security of " + operation.operationId());

        // When: the router answers an anonymous caller, an unrelated token, and a token with the listed scopes
        int anonymous = get(operation.path(), null);
        int unrelated = get(operation.path(), token(UNRELATED_SCOPE));
        int listed = get(operation.path(), token(String.join(" ", scopes)));

        // Then: it enforces what the document publishes
        assertEquals(secured ? 401 : 200, anonymous, "anonymous request to " + operation.operationId());
        assertEquals(
                secured && !scopes.isEmpty() ? 403 : 200,
                unrelated,
                "request with unrelated scopes to " + operation.operationId());
        assertEquals(200, listed, "request with the listed scopes to " + operation.operationId());
    }

    private static List<String> scopesOf(JsonArray security) {
        List<String> scopes = new ArrayList<>();
        if (security != null) {
            for (int i = 0; i < security.size(); i++) {
                security.getJsonObject(i)
                        .forEach(entry -> ((JsonArray) entry.getValue()).forEach(scope -> scopes.add((String) scope)));
            }
        }
        return scopes;
    }

    private static String token(String scope) {
        return signer.generateToken(new JsonObject().put("sub", "probe").put("scope", scope));
    }

    private static int get(String path, String bearer) throws Exception {
        HttpRequest<Buffer> request = client.get(parity.port(), HOST, ParityApi.PATH + path);
        if (bearer != null) {
            request.putHeader("Authorization", "Bearer " + bearer);
        }
        return Futures.await(request.send(), StartupDeployments.BOUND).statusCode();
    }
}
