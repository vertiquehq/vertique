// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.assembly;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.core.security.scheme.ApiKey;
import dev.vertique.rest.core.security.scheme.Http;
import dev.vertique.rest.core.security.scheme.MutualTls;
import dev.vertique.rest.core.security.scheme.OAuth2;
import dev.vertique.rest.core.security.scheme.OAuthFlows;
import dev.vertique.rest.core.security.scheme.OpenIdConnect;
import dev.vertique.rest.core.security.scheme.SecuritySchemeDescription;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.MetadataDocuments;
import dev.vertique.rest.openapi.docs.OpenApi31Toolchain;
import dev.vertique.rest.openapi.docs.ResponseDocuments;
import dev.vertique.rest.openapi.docs.config.EnabledDocuments;
import dev.vertique.rest.openapi.docs.diagnostics.DiagnosticsAccess;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.StubSchemeHandler;
import dev.vertique.rest.openapi.docs.fixture.security.catalog.WarningCapture;
import dev.vertique.rest.openapi.docs.fixture.security.listing.AlphaResource;
import dev.vertique.rest.openapi.docs.fixture.security.listing.BetaResource;
import dev.vertique.rest.openapi.docs.fixture.security.listing.DaggerRosterCaptureComponent;
import dev.vertique.rest.openapi.docs.fixture.security.listing.OpenResource;
import dev.vertique.rest.openapi.docs.fixture.security.listing.RosterApi;
import dev.vertique.rest.openapi.docs.fixture.security.listing.RosterCaptureComponent;
import dev.vertique.rest.openapi.docs.fixture.security.listing.RosterSchemeModule;
import dev.vertique.rest.openapi.docs.fixture.security.unit.DaggerGhostCaptureComponent;
import dev.vertique.rest.openapi.docs.fixture.security.unit.GhostApi;
import dev.vertique.rest.openapi.docs.fixture.security.unit.GhostBearerHandler;
import dev.vertique.rest.openapi.docs.fixture.security.unit.GhostCaptureComponent;
import dev.vertique.rest.openapi.docs.fixture.security.unit.GhostQueryKeyHandler;
import dev.vertique.rest.openapi.docs.fixture.security.unit.GhostResource;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import dev.vertique.rest.openapi.docs.metadata.OperationFacts;
import dev.vertique.rest.openapi.docs.publication.PublicationAccess;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit proofs of the security part of an assembled document: every kind of security scheme
 * description renders as its OpenAPI 3.1.1 Security Scheme Object, and a published operation that
 * references a scheme no registered handler provides fails publication naming the scheme.
 *
 * <p>Rendering is checked on the renderer directly. Each row's rendering is serialized compactly and
 * compared with its expected JSON text as a string, so member order and omitted empty fields are part
 * of the comparison; the same rendering is then placed under {@code components.securitySchemes} of
 * a minimal OpenAPI 3.1.1 document whose one operation references it, and that document must
 * validate with {@link OpenApi31Toolchain}.
 *
 * <p>The missing-handler refusal is checked on a publication captured from a real mount build: a
 * component without the documentation module deploys the application {@code ghost}, whose one
 * operation requires {@value GhostResource#SCHEME}, with a handler for that scheme (the route
 * registrar refuses an operation whose scheme has none) and a capturing publication sink that wants
 * detail. The captured publication is then assembled as the documentation sink does, against a
 * handler set that lacks {@value GhostResource#SCHEME}; a deployment never reaches this state, so the
 * rule is reachable only here.
 *
 * <p>The order of the public restriction warning is checked the same way: a component without the
 * documentation module deploys the public application {@code roster}, whose restricted operations'
 * ids, registration order, and paths each sort differently and two of which share one path. The
 * captured publication is assembled with a fresh warning guard while the documentation module's
 * warning logger is captured, and the warning's list is compared with its expected literal.
 *
 * <p>Expected values are hand-written literals. Failure messages are checked by fragment.
 */
@DisplayName("Security schemes and requirements of assembled documents")
class DocumentSecurityAssemblyTest {

    /** Serializes compactly, keeping every object's member order. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The scheme name each rendering is published under in its validation document. */
    private static final String ROW_SCHEME = "rowScheme";

    /** The longest the capture's Vert.x instance is awaited while it closes. */
    private static final long CLOSE_TIMEOUT_SECONDS = 10;

    /** The documentation module's warning logger. */
    private static final String WARNINGS_LOGGER = "dev.vertique.rest.openapi.docs.DocumentWarnings";

    /** The configuration path of the {@code roster} document, which starts each of its warnings. */
    private static final String ROSTER_DOCUMENT_PATH = "apidocs.documents.roster";

    /** The fragment that marks the restriction warning among a document's warnings. */
    private static final String RESTRICT_FRAGMENT = "restrict callers";

    /** The fragment after which the restriction warning lists its entries. */
    private static final String LIST_FRAGMENT = "served without authentication: ";

    /** The separator between two listed entries. */
    private static final String ENTRY_SEPARATOR = ", ";

    // ---------------------------------------------------------------------------------------------
    // Rendering every description kind
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("descriptionKinds")
    @DisplayName("every description kind renders as its OpenAPI Security Scheme Object")
    void rendersEveryDescriptionKind(String label, SecuritySchemeDescription description, String expectedJson) {
        // Given: one description built through the security scheme factories, and its expected
        // Security Scheme Object as JSON text

        // When: the description is rendered
        ObjectNode rendered = SecuritySchemeRenderer.render(description);

        // Then: the rendering equals the expected text byte for byte (member order and omitted empty
        // fields included), and a minimal document whose operation references it is valid OpenAPI 3.1
        assertAll(
                () -> assertEquals(compact(expectedJson), compact(rendered), label + ": the rendered scheme"),
                () -> OpenApi31Toolchain.assertValid(documentReferencing(rendered)));
    }

    static Stream<Arguments> descriptionKinds() {
        return Stream.of(
                Arguments.of("http basic with a description", Http.of("basic").withDescription("Basic"), """
                        {"type": "http", "description": "Basic", "scheme": "basic"}
                        """),
                Arguments.of("http bearer with a bearer format", Http.bearer("JWT"), """
                        {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"}
                        """),
                Arguments.of("api key in the query", ApiKey.query("api_key"), """
                        {"type": "apiKey", "name": "api_key", "in": "query"}
                        """),
                Arguments.of("api key in a header", ApiKey.header("X-Api-Key"), """
                        {"type": "apiKey", "name": "X-Api-Key", "in": "header"}
                        """),
                Arguments.of("api key in a cookie", ApiKey.cookie("session"), """
                        {"type": "apiKey", "name": "session", "in": "cookie"}
                        """),
                Arguments.of("oauth2 with every flow and a refresh URL", oauth2WithEveryFlow(), """
                        {
                          "type": "oauth2",
                          "flows": {
                            "implicit": {
                              "authorizationUrl": "https://auth.example.test/oauth/authorize",
                              "refreshUrl": "https://auth.example.test/oauth/refresh",
                              "scopes": {"admin": "", "read": "Read orders", "write": "Write orders"}
                            },
                            "password": {
                              "tokenUrl": "https://auth.example.test/oauth/token",
                              "refreshUrl": "https://auth.example.test/oauth/refresh",
                              "scopes": {
                                "email": "Read the email address",
                                "openid": "",
                                "profile": "Read the profile"
                              }
                            },
                            "clientCredentials": {
                              "tokenUrl": "https://auth.example.test/oauth/client-token",
                              "refreshUrl": "https://auth.example.test/oauth/refresh",
                              "scopes": {"audit": "", "metrics": "Read metrics", "reports": "Read reports"}
                            },
                            "authorizationCode": {
                              "authorizationUrl": "https://auth.example.test/oauth/code-authorize",
                              "tokenUrl": "https://auth.example.test/oauth/code-token",
                              "refreshUrl": "https://auth.example.test/oauth/refresh",
                              "scopes": {"alpha": "First scope", "mid": "", "zeta": "Last scope"}
                            }
                          }
                        }
                        """),
                Arguments.of(
                        "openIdConnect",
                        OpenIdConnect.of(URI.create("https://id.example.test/.well-known/openid-configuration")),
                        """
                        {
                          "type": "openIdConnect",
                          "openIdConnectUrl": "https://id.example.test/.well-known/openid-configuration"
                        }
                        """),
                Arguments.of(
                        "mutual TLS with a description", MutualTls.of().withDescription("Client certificates"), """
                        {"type": "mutualTLS", "description": "Client certificates"}
                        """));
    }

    /**
     * An OAuth2 description with all four flows and one refresh URL, each flow's scopes inserted in an
     * order that is not sorted by name.
     */
    private static OAuth2 oauth2WithEveryFlow() {
        return OAuth2.of(OAuthFlows.builder()
                .implicit(
                        URI.create("https://auth.example.test/oauth/authorize"),
                        scopes("write", "Write orders", "admin", "", "read", "Read orders"))
                .password(
                        URI.create("https://auth.example.test/oauth/token"),
                        scopes("profile", "Read the profile", "email", "Read the email address", "openid", ""))
                .clientCredentials(
                        URI.create("https://auth.example.test/oauth/client-token"),
                        scopes("reports", "Read reports", "audit", "", "metrics", "Read metrics"))
                .authorizationCode(
                        URI.create("https://auth.example.test/oauth/code-authorize"),
                        URI.create("https://auth.example.test/oauth/code-token"),
                        scopes("zeta", "Last scope", "alpha", "First scope", "mid", ""))
                .refreshUrl(URI.create("https://auth.example.test/oauth/refresh"))
                .build());
    }

    /** Scopes in the given insertion order, from alternating scope names and descriptions. */
    private static Map<String, String> scopes(String... namesAndDescriptions) {
        Map<String, String> scopes = new LinkedHashMap<>();
        for (int i = 0; i < namesAndDescriptions.length; i += 2) {
            scopes.put(namesAndDescriptions[i], namesAndDescriptions[i + 1]);
        }
        return scopes;
    }

    /**
     * A minimal OpenAPI 3.1.1 document publishing the rendering as {@value #ROW_SCHEME} under {@code
     * components.securitySchemes}, with one operation that requires it.
     */
    private static JsonObject documentReferencing(ObjectNode rendered) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        ObjectNode document = nodes.objectNode();
        document.put("openapi", "3.1.1");
        document.putObject("info").put("title", "Security schemes").put("version", "1.0");
        ObjectNode operation =
                document.putObject("paths").putObject("/protected").putObject("get");
        operation.put("operationId", "readProtected");
        operation.putObject("responses").putObject("200").put("description", "OK");
        ArrayNode security = operation.putArray("security");
        security.addObject().putArray(ROW_SCHEME);
        document.putObject("components").putObject("securitySchemes").set(ROW_SCHEME, rendered.deepCopy());
        return new JsonObject(compact(document));
    }

    /** Re-serializes JSON text without insignificant whitespace, keeping member order. */
    private static String compact(String json) {
        try {
            return compact(JSON.readTree(json));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Serializes a tree without insignificant whitespace, in its members' written order. */
    private static String compact(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A referenced scheme without a handler
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a referenced scheme with no registered handler fails publication naming the scheme")
    void referencedSchemeWithoutHandlerFailsNamingScheme() throws Exception {
        // Given: the publication of a real mount build of application 'ghost', whose operation
        // readGhost requires ghostAuth, and a handler set holding other schemes but not ghostAuth
        MountPublication captured = captureGhostPublication();
        assertReferencesOnlyGhostAuth(captured);
        Map<String, OperationFacts> facts = PublicationAccess.operationFacts(captured);
        MountPublication detached = PublicationAccess.detach(captured);
        EnabledDocuments.EnabledDocument document = MetadataDocuments.document(captured, ApiDocs.Access.PUBLIC);
        AssemblyContext context = new AssemblyContext(
                Optional.empty(),
                ResponseDocuments.registry(),
                DiagnosticsAccess.documentWarnings(),
                Set.of(),
                schemeHandlers("bearerAuth", "apiKeyAuth"));

        // When: the document is assembled
        RestConfigurationException failure = assertThrows(
                RestConfigurationException.class,
                () -> DocumentAssembler.assemble(document, detached, facts, context),
                "assembly must fail: readGhost requires ghostAuth, which no registered handler provides");

        // Then: the message names the document, the mount, the operation, the scheme, and the rule
        String message = failure.getMessage();
        assertAll(
                () -> assertTrue(
                        message.contains("apidocs.documents." + GhostApi.NAME),
                        () -> "the failure must name apidocs.documents.ghost: " + message),
                () -> assertTrue(
                        message.contains(GhostApi.MOUNT_PATH),
                        () -> "the failure must name the mount /api/ghost/*: " + message),
                () -> assertTrue(
                        message.contains("operation '" + GhostResource.READ_GHOST + "'"),
                        () -> "the failure must name operation 'readGhost': " + message),
                () -> assertTrue(
                        message.contains("security scheme '" + GhostResource.SCHEME + "'"),
                        () -> "the failure must name security scheme 'ghostAuth': " + message),
                () -> assertTrue(
                        message.contains("no SecuritySchemeHandler is registered"),
                        () -> "the failure must state that no SecuritySchemeHandler is registered: " + message));
    }

    @Test
    @DisplayName("a referenced scheme provided by two handlers fails publication naming both handlers")
    void schemeProvidedByTwoHandlersFailsNamingBoth() throws Exception {
        // Given: the publication of a real mount build of application 'ghost', whose operation
        // readGhost requires ghostAuth, and a handler set in which two different handler classes both
        // provide ghostAuth, each describing it differently
        MountPublication captured = captureGhostPublication();
        assertReferencesOnlyGhostAuth(captured);
        Map<String, OperationFacts> facts = PublicationAccess.operationFacts(captured);
        MountPublication detached = PublicationAccess.detach(captured);
        EnabledDocuments.EnabledDocument document = MetadataDocuments.document(captured, ApiDocs.Access.PUBLIC);
        Set<SecuritySchemeHandler> handlers = new LinkedHashSet<>();
        handlers.add(new GhostQueryKeyHandler());
        handlers.add(new GhostBearerHandler());
        AssemblyContext context = new AssemblyContext(
                Optional.empty(),
                ResponseDocuments.registry(),
                DiagnosticsAccess.documentWarnings(),
                Set.of(),
                handlers);

        // When: the document is assembled
        RestConfigurationException failure = assertThrows(
                RestConfigurationException.class,
                () -> DocumentAssembler.assemble(document, detached, facts, context),
                "assembly must fail: readGhost requires ghostAuth, which two handlers provide");

        // Then: the message names the document, the operation, the scheme, both handlers sorted by
        // binary name, and the rule, and publishes neither handler's description
        String message = failure.getMessage();
        String bothHandlers = "dev.vertique.rest.openapi.docs.fixture.security.unit.GhostBearerHandler, "
                + "dev.vertique.rest.openapi.docs.fixture.security.unit.GhostQueryKeyHandler";
        assertAll(
                () -> assertTrue(
                        message.contains("apidocs.documents." + GhostApi.NAME),
                        () -> "the failure must name apidocs.documents.ghost: " + message),
                () -> assertTrue(
                        message.contains("operation '" + GhostResource.READ_GHOST + "'"),
                        () -> "the failure must name operation 'readGhost': " + message),
                () -> assertTrue(
                        message.contains("security scheme '" + GhostResource.SCHEME + "'"),
                        () -> "the failure must name security scheme 'ghostAuth': " + message),
                () -> assertTrue(
                        message.contains("which more than one SecuritySchemeHandler provides: " + bothHandlers),
                        () -> "the failure must list both handlers, sorted: " + message),
                () -> assertTrue(
                        message.contains("each security scheme must be provided by exactly one handler"),
                        () -> "the failure must state the one-handler rule: " + message),
                () -> assertFalse(
                        message.contains("api_key"), () -> "the failure must not carry a description: " + message),
                () -> assertFalse(
                        message.contains("JWT"), () -> "the failure must not carry a description: " + message));
    }

    /**
     * Deploys the capture component on a private Vert.x instance and returns the publication its
     * mount build handed to the capture; the deployment is undone and the instance closed on every
     * exit path.
     */
    private static MountPublication captureGhostPublication() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            GhostCaptureComponent component =
                    DaggerGhostCaptureComponent.factory().create(DocsConfigs.loopback());
            StartupDeployments.Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
            try {
                assertTrue(outcome.deployed(), () -> "the capture component must deploy: " + outcome.failure());
                MountPublication captured = component.capture().publication(GhostApi.NAME);
                assertNotNull(captured, "the mount of application 'ghost' must have been published");
                return captured;
            } finally {
                StartupDeployments.undeploy(vertx, outcome);
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Checks the fixture: the captured mount is {@code /api/ghost/*} and has the operation readGhost,
     * with detail, whose requirement sets name ghostAuth and nothing else.
     */
    private static void assertReferencesOnlyGhostAuth(MountPublication captured) {
        assertEquals(GhostApi.MOUNT_PATH, captured.mountPath(), "the captured mount path");
        List<OperationPublication> operations = captured.operations();
        OperationPublication readGhost = operations.stream()
                .filter(operation -> operation.operationId().equals(GhostResource.READ_GHOST))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the captured mount has no operation readGhost: "
                        + operations.stream()
                                .map(OperationPublication::operationId)
                                .toList()));
        assertNotNull(readGhost.detail(), "readGhost must carry detail");
        assertEquals(
                List.of(GhostResource.SCHEME),
                readGhost.securityRequirementSets().stream()
                        .flatMap(set -> set.schemes().stream())
                        .map(SecurityRequirement::schemeName)
                        .toList(),
                "the schemes readGhost requires");
    }

    // ---------------------------------------------------------------------------------------------
    // The order of the public restriction warning
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the restriction warning lists operations by path, then by Path Item method order")
    void restrictionWarningListsOperationsInPathThenMethodOrder() throws Exception {
        // Given: the publication of a real mount build of the public application 'roster', whose
        // restricted operations are zuluCreate (POST /alpha), yankeeRead (GET /alpha), and alphaList
        // (GET /beta), so that operation-id order and registration order both differ from path order,
        // beside the open operation openPing (GET /open); and its described scheme handler
        MountPublication captured = captureRosterPublication();
        assertEquals(
                Set.of(
                        AlphaResource.ZULU_CREATE,
                        AlphaResource.YANKEE_READ,
                        BetaResource.ALPHA_LIST,
                        OpenResource.OPEN_PING),
                captured.operations().stream()
                        .map(OperationPublication::operationId)
                        .collect(Collectors.toSet()),
                "the operations of the captured roster mount");
        Map<String, OperationFacts> facts = PublicationAccess.operationFacts(captured);
        MountPublication detached = PublicationAccess.detach(captured);
        EnabledDocuments.EnabledDocument document = MetadataDocuments.document(captured, ApiDocs.Access.PUBLIC);
        AssemblyContext context = new AssemblyContext(
                Optional.empty(),
                ResponseDocuments.registry(),
                DiagnosticsAccess.documentWarnings(),
                Set.of(),
                Set.of(RosterSchemeModule.rosterAuthHandler()));

        // When: the public document is assembled while the warning logger is captured
        List<String> warnings;
        WarningCapture capture = WarningCapture.attach(WARNINGS_LOGGER);
        try {
            DocumentAssembler.assemble(document, detached, facts, context);
            warnings = capture.warnings();
        } finally {
            capture.detach();
        }

        // Then: exactly one restriction warning for the roster document
        List<String> restrictions = warnings.stream()
                .filter(message -> message.startsWith(ROSTER_DOCUMENT_PATH) && message.contains(RESTRICT_FRAGMENT))
                .toList();
        assertEquals(1, restrictions.size(), () -> "the roster restriction warnings: " + warnings);
        String warning = restrictions.get(0);

        // Then: it lists the restricted operations by path in natural order, and within /alpha by Path
        // Item method order (get before post), and never the open operation
        assertAll(
                () -> assertEquals(
                        List.of("GET /alpha (yankeeRead)", "POST /alpha (zuluCreate)", "GET /beta (alphaList)"),
                        listedEntries(warning),
                        () -> "the listed entries: " + warning),
                () -> assertFalse(
                        warning.contains(OpenResource.OPEN_PING),
                        () -> "the warning names the open operation: " + warning));
    }

    /**
     * Deploys the roster capture component on a private Vert.x instance and returns the publication
     * its mount build handed to the capture; the deployment is undone and the instance closed on
     * every exit path.
     */
    private static MountPublication captureRosterPublication() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            RosterCaptureComponent component =
                    DaggerRosterCaptureComponent.factory().create(DocsConfigs.loopback());
            StartupDeployments.Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
            try {
                assertTrue(outcome.deployed(), () -> "the roster capture must deploy: " + outcome.failure());
                MountPublication captured = component.capture().publication(RosterApi.NAME);
                assertNotNull(captured, "the mount of application 'roster' must have been published");
                return captured;
            } finally {
                StartupDeployments.undeploy(vertx, outcome);
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Returns the entries a restriction warning lists: its suffix after the list fragment, split. */
    private static List<String> listedEntries(String warning) {
        int at = warning.indexOf(LIST_FRAGMENT);
        assertTrue(at >= 0, () -> "the warning has no entry list after '" + LIST_FRAGMENT + "': " + warning);
        return List.of(warning.substring(at + LIST_FRAGMENT.length()).split(ENTRY_SEPARATOR, -1));
    }

    /**
     * Builds a handler set of stub handlers, one per scheme name, in the given order.
     *
     * @param schemeNames the scheme names the handlers provide
     * @return the handlers
     */
    private static Set<SecuritySchemeHandler> schemeHandlers(String... schemeNames) {
        Set<SecuritySchemeHandler> handlers = new LinkedHashSet<>();
        Arrays.stream(schemeNames).map(StubSchemeHandler::new).forEach(handlers::add);
        return handlers;
    }
}
