// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.openapi.docs.StartupTestComponents.CompositionExtensions;
import dev.vertique.rest.openapi.docs.StartupTestComponents.ProtectedEnforcementOnlyVerticleInputsComponent;
import dev.vertique.rest.openapi.docs.StartupTestComponents.RootApplicationStartupComponent;
import dev.vertique.rest.openapi.docs.StartupTestComponents.StartupProvisions;
import dev.vertique.rest.openapi.docs.StartupTestComponents.VerticleInputs;
import dev.vertique.rest.openapi.docs.StartupTestComponents.VerticleInputsStartupComponent;
import dev.vertique.rest.openapi.docs.StartupTestComponents.ZeroDeclarationStartupComponent;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.MarkerRouterMount;
import dev.vertique.rest.openapi.docs.fixture.startup.startupit.ApiJsonIdResource;
import dev.vertique.rest.openapi.docs.fixture.startup.startupit.ContributedResources;
import dev.vertique.rest.openapi.docs.fixture.startup.startupit.CountingResource;
import dev.vertique.rest.openapi.docs.fixture.startup.startupit.HandBuiltMounts;
import dev.vertique.rest.openapi.docs.fixture.startup.startupit.PrefixProbeResource;
import dev.vertique.rest.openapi.docs.fixture.startup.startupit.ReservedCaseResource;
import dev.vertique.rest.openapi.docs.fixture.startup.startupit.ReservedJsonResource;
import dev.vertique.rest.openapi.docs.fixture.startup.startupit.ReservedMgmtResource;
import dev.vertique.rest.openapi.docs.fixture.startup.startupit.ReservedYamlResource;
import dev.vertique.rest.openapi.docs.fixture.startup.startupit.TenantProbeResource;
import dev.vertique.rest.openapi.docs.fixture.startup.startupit.ThreeSegmentsResource;
import dev.vertique.rest.openapi.docs.fixture.support.Cleanup;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments.Outcome;
import dev.vertique.rest.openapi.docs.publication.DocsPublicationSink;
import dev.vertique.rest.openapi.docs.publication.PublicationAccess;
import dev.vertique.rest.openapi.docs.serving.DocsCompositionValidator;
import dev.vertique.rest.openapi.docs.serving.DocsRouterMount;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Deploys the documentation module's startup components over loopback HTTP and observes which
 * compositions may start: configuration failures and protected {@code @ApiDocs} values refuse
 * startup before any router is built, naming the application, its declaring interface, and the
 * offending path or attribute without echoing a configured value, and an {@code @ApiDocs}
 * declaration without configuration publishes its document. It also observes the composition
 * checks: a JAX-RS mount under the documentation prefix and an operation using a document's
 * synthetic operation id refuse startup, disabled documentation adds no check, sink, or warning,
 * and a documentation mount that no composition validator marked refuses to create its router.
 *
 * <p>Every row builds a fresh component (so its spies start at zero), deploys its
 * {@code HttpVerticle} through {@link #deploy(Vertx, StartupProvisions)}, which builds the verticle
 * inside the deployment so a refused provision becomes the deployment's failure, and undeploys in a
 * {@code finally} block, which clears the published port. A refused startup is checked by
 * {@link #assertStartupFailure}; a document request is sent by {@link #getJsonDocument}. Every
 * expectation is a hand-written literal; the marker {@value #MARKER} stands in every value a failing
 * configuration must not echo.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DocsStartupChecksIT {

    /** The host every server binds and every request dials. */
    private static final String LOOPBACK = "127.0.0.1";

    /** The marker carried by every configured value a failure message must not echo. */
    private static final String MARKER = "zq7";

    /** A title carrying the marker, placed where a failing configuration holds a second value. */
    private static final String MARKED_TITLE = "zq7Title";

    /** An absolute-path server URL carrying the marker. */
    private static final String MARKED_SERVER_URL = "/zq7";

    /** The URL of the {@code public} document's JSON form under the default prefix. */
    private static final String PUBLIC_JSON_URL = "/apidocs/public/openapi.json";

    /** The documented application of the shared fixture. */
    private static final String PUBLIC = "public";

    /** The second application of the shared fixture. */
    private static final String MGMT = "mgmt";

    /** The binary name of the shared fixture's documented declaration. */
    private static final String PUBLIC_API = "dev.vertique.rest.openapi.docs.fixture.PublicApi";

    /** The binary name of the shared fixture's undocumented declaration. */
    private static final String MGMT_API = "dev.vertique.rest.openapi.docs.fixture.MgmtApi";

    /** The binary name of the protected declaration that names no security scheme. */
    private static final String NO_SCHEME_API = "dev.vertique.rest.openapi.docs.fixture.startup.NoSchemeApi";

    /** The binary name of the protected declaration guarded by {@code bearerAuth} and the role {@code admin}. */
    private static final String PROTECTED_MGMT_API = "dev.vertique.rest.openapi.docs.fixture.ProtectedMgmtApi";

    /** The second protected application, guarded by {@code otherAuth}. */
    private static final String OPS = "ops";

    /** The binary name of the protected declaration guarded by {@code otherAuth}. */
    private static final String PROTECTED_OPS_API =
            "dev.vertique.rest.openapi.docs.fixture.startup.startupit.ProtectedOpsApi";

    /** The binary name of the protected declaration guarded by {@code bearerAuth} with no roles. */
    private static final String AUTHENTICATED_MGMT_API =
            "dev.vertique.rest.openapi.docs.fixture.startup.AuthenticatedMgmtApi";

    /** The security scheme the protected declarations name in code. */
    private static final String BEARER_AUTH = "bearerAuth";

    /** The attribute naming a protected document's security scheme. */
    private static final String SECURITY_SCHEME_ATTRIBUTE = "@ApiDocs.securityScheme";

    /** The attribute declaring a document's access. */
    private static final String ACCESS_ATTRIBUTE = "@ApiDocs.access";

    /** The statement of a refusal whose scheme matches no registered handler. */
    private static final String NO_HANDLER = "no registered SecuritySchemeHandler has that name";

    /** The statement of a refusal of a protected document without authentication enforcement. */
    private static final String NO_ENFORCEMENT = "authentication enforcement is not installed";

    /** The statement of a refusal that a protected document failing a startup check must not make. */
    private static final String NOT_SERVED_YET = "not served yet";

    /** The {@code info} the annotated declaration's {@code @OpenAPIDefinition} carries. */
    private static final Map<String, Object> ANNOTATED_INFO = Map.of("title", "Catalog API", "version", "1.0");

    /** The {@code info} a configuration sets beside the annotated declaration. */
    private static final Map<String, Object> CONFIGURED_INFO = Map.of("title", "Configured", "version", "9");

    /** The router builds of a composition refused before any router: none. */
    private static final int NO_ROUTER = 0;

    /** The start of the failure message of a composition refused by a composition validator. */
    private static final String INVALID_MOUNT_CONFIGURATION = "Invalid mount configuration";

    /** The configuration path of the documentation prefix. */
    private static final String APIDOCS_PATH_SETTING = "apidocs.path";

    /** The default documentation prefix, quoted as a violation names it. */
    private static final String QUOTED_DEFAULT_PREFIX = "'/apidocs'";

    /** The configuration path of the {@code public} document's entry. */
    private static final String PUBLIC_DOCUMENT_SETTING = "apidocs.documents.public";

    /** The {@code public} application, quoted as a message names it. */
    private static final String QUOTED_PUBLIC = "'public'";

    /** The binary name of the documented declaration that lists the reserved-id resource. */
    private static final String PUBLIC_RESERVED_API =
            "dev.vertique.rest.openapi.docs.fixture.startup.startupit.PublicReservedApi";

    /** The mount path of the hand-built mount holding a reserved-id resource. */
    private static final String OTHER_MOUNT_PATH = "/api/other/*";

    /** The mount path of the documented {@code public} application. */
    private static final String PUBLIC_MOUNT_PATH = "/api/public/*";

    /** The method and template of every reserved-id resource's operation. */
    private static final String GET_X = "GET /x";

    /** The synthetic operation id of the {@code public} document's JSON form. */
    private static final String PUBLIC_JSON_ID = "apidocs:public:json";

    /** The synthetic operation id of the {@code public} document's YAML form. */
    private static final String PUBLIC_YAML_ID = "apidocs:public:yaml";

    /** The priority that places a hand-built mount before the default JAX-RS mount. */
    private static final int EARLY_PRIORITY = 100;

    /** The logger the documentation module's warnings are written to. */
    private static final String DOCUMENT_WARNINGS_LOGGER = "dev.vertique.rest.openapi.docs.DocumentWarnings";

    /** The unvalidated-mount refusal: it names the documentation mount. */
    private static final String DOCS_MOUNT_NAMED = "Documentation mount 'apidocs'";

    /** The unvalidated-mount refusal: its cause. */
    private static final String UNVALIDATED_CAUSE = "built without composition validators";

    /** The unvalidated-mount refusal: its remedy. */
    private static final String UNVALIDATED_REMEDY = "obtain HttpVerticle from Dagger";

    /** A hand-built mount path under the documentation prefix carrying the marker. */
    private static final String MARKED_DOCS_MOUNT_PATH = "/apidocs/zq7/*";

    /**
     * Build (i). A disabled entry is never checked for its content: documentation is on, the root
     * application {@code api} is documented, and its only entry disables the document while breaking
     * the {@code info} rule (a blank title) and the {@code serverUrl} rule (a scheme-relative URL).
     */
    private static final String DISABLED_ENTRY_WITH_BROKEN_CONTENT = """
            {
              "http": {"host": "127.0.0.1", "port": 0},
              "jaxrs": {"validationStrategy": "none"},
              "apidocs": {
                "documents": {
                  "api": {"enabled": false, "info": {"title": " ", "version": "1"}, "serverUrl": "//zq7"}
                }
              }
            }
            """;

    /**
     * Build (ii). {@code apidocs.enabled: false} reads nothing else of the section: an invalid typed
     * prefix ({@code apidocs.path: 17}), an entry that is not an object ({@code documents.api: 17}),
     * an entry naming no application with unsupported keys ({@code nosuch}), and an entry breaking the
     * name grammar ({@code Api}).
     */
    private static final String GLOBALLY_DISABLED_WITH_INVALID_SETTINGS = """
            {
              "http": {"host": "127.0.0.1", "port": 0},
              "jaxrs": {"validationStrategy": "none"},
              "apidocs": {
                "enabled": false,
                "path": 17,
                "documents": {
                  "api": 17,
                  "nosuch": {"access": {"mode": "zq7"}, "mount": "/zq7"},
                  "Api": {}
                }
              }
            }
            """;

    /**
     * Build (iii). The global switch is a strict JSON boolean: the string {@code "false"} is neither
     * accepted nor coerced, and fails startup naming {@code apidocs.enabled}.
     */
    private static final String MALFORMED_GLOBAL_SWITCH = """
            {
              "http": {"host": "127.0.0.1", "port": 0},
              "jaxrs": {"validationStrategy": "none"},
              "apidocs": {
                "enabled": "false",
                "documents": {
                  "api": {"enabled": false, "info": {"title": " ", "version": "1"}, "serverUrl": "//zq7"}
                }
              }
            }
            """;

    /**
     * Build (iv). Without any declared application no document can be enabled, so the installed
     * documentation module adds no check: no {@code apidocs} section at all.
     */
    private static final String NO_APIDOCS_SECTION = """
            {
              "http": {"host": "127.0.0.1", "port": 0},
              "jaxrs": {"validationStrategy": "none"}
            }
            """;

    private WebClient client;

    private Logger documentWarnings;

    private Level previousWarningsLevel;

    private ListAppender<ILoggingEvent> warnings;

    @BeforeEach
    void createClient(Vertx vertx) {
        client = WebClient.create(vertx);
    }

    @BeforeEach
    void captureDocumentWarnings() {
        documentWarnings = (Logger) LoggerFactory.getLogger(DOCUMENT_WARNINGS_LOGGER);
        previousWarningsLevel = documentWarnings.getLevel();
        documentWarnings.setLevel(Level.DEBUG);
        warnings = new ListAppender<>();
        warnings.start();
        documentWarnings.addAppender(warnings);
    }

    @AfterEach
    void releaseDocumentWarnings() {
        documentWarnings.detachAppender(warnings);
        warnings.stop();
        documentWarnings.setLevel(previousWarningsLevel);
    }

    @AfterEach
    void closeClient(Vertx vertx) {
        client.close();
        vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).remove(StartupDeployments.PORT_KEY);
    }

    // ---------------------------------------------------------------------------------------------
    // Configuration failures stop startup before any router
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurationFailures")
    @DisplayName(
            "a refused configuration fails startup before any router is built, naming the path or attribute, the application, and its interface, and echoing no value")
    void configurationFailuresStopStartupBeforeAnyRouter(
            String label,
            Composition composition,
            JsonObject configuration,
            @Nullable String application,
            List<String> fragments,
            Vertx vertx)
            throws Exception {
        // Given: the composition with the spies, configured with the row's configuration
        StartupProvisions component = composition.create(configuration);

        // When: it is deployed
        Outcome outcome = deploy(vertx, component);
        try {
            // Then: startup failed before any router, and the message names what the row's rule names
            assertStartupFailure(label, outcome, component, SpyCheck.BOTH, application, fragments, List.of());
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    static Stream<Arguments> configurationFailures() {
        return Stream.of(
                Arguments.of(
                        "an entry keyed 'Api' breaks the name grammar",
                        Composition.SHARED,
                        entryKeyedApi(),
                        null,
                        List.of("apidocs.documents.Api")),
                Arguments.of(
                        "an entry keyed 'nosuch' names no declared application",
                        Composition.SHARED,
                        entryKeyedNosuch(),
                        null,
                        List.of("apidocs.documents.nosuch")),
                Arguments.of(
                        "documents.public.access is not an entry key",
                        Composition.SHARED,
                        publicEntryWithAccess(),
                        PUBLIC,
                        List.of("apidocs.documents.public.access", PUBLIC_API)),
                Arguments.of(
                        "documents.public.mount is not an entry key",
                        Composition.SHARED,
                        publicEntryWithMount(),
                        PUBLIC,
                        List.of("apidocs.documents.public.mount", PUBLIC_API)),
                Arguments.of(
                        "documents.mgmt.enabled true on an interface without @ApiDocs",
                        Composition.SHARED,
                        mgmtEnabledWithoutApiDocs(),
                        MGMT,
                        List.of("apidocs.documents.mgmt.enabled", MGMT_API)),
                Arguments.of(
                        "documents.public without info and no interface info",
                        Composition.SHARED,
                        publicEntryWithoutInfo(),
                        PUBLIC,
                        List.of("apidocs.documents.public.info", PUBLIC_API)),
                Arguments.of(
                        "NoSchemeApi: a protected document naming no security scheme",
                        Composition.NO_SCHEME,
                        noSchemeDeclaration(),
                        MGMT,
                        List.of(SECURITY_SCHEME_ATTRIBUTE, NO_SCHEME_API)));
    }

    /** The shared configuration, the {@code public} title marked, plus an entry keyed {@code Api}. */
    private static JsonObject entryKeyedApi() {
        JsonObject config = markedShared();
        DocsConfigs.withDocumentInfo(config, "Api", MARKED_TITLE, "1");
        return config;
    }

    /** The shared configuration, the {@code public} title marked, plus an entry keyed {@code nosuch}. */
    private static JsonObject entryKeyedNosuch() {
        JsonObject config = markedShared();
        DocsConfigs.withDocumentInfo(config, "nosuch", MARKED_TITLE, "1");
        return config;
    }

    /** The shared configuration, the {@code public} title marked, plus {@code documents.public.access}. */
    private static JsonObject publicEntryWithAccess() {
        JsonObject config = markedShared();
        DocsConfigs.document(config, PUBLIC).put("access", new JsonObject().put("mode", "public"));
        return config;
    }

    /** The shared configuration, the {@code public} title marked, plus {@code documents.public.mount}. */
    private static JsonObject publicEntryWithMount() {
        JsonObject config = markedShared();
        DocsConfigs.document(config, PUBLIC).put("mount", "/api/public");
        return config;
    }

    /** The shared configuration, the {@code public} title marked, plus {@code documents.mgmt.enabled: true}. */
    private static JsonObject mgmtEnabledWithoutApiDocs() {
        JsonObject config = markedShared();
        DocsConfigs.withDocumentEnabled(config, MGMT, true);
        return config;
    }

    /**
     * The shared configuration with the {@code public} entry's {@code info} removed; the entry holds
     * only a marked {@code serverUrl}.
     */
    private static JsonObject publicEntryWithoutInfo() {
        JsonObject config = DocsConfigs.shared();
        JsonObject entry = DocsConfigs.document(config, PUBLIC);
        entry.remove("info");
        entry.put("serverUrl", MARKED_SERVER_URL);
        return config;
    }

    /**
     * The shared configuration, the {@code public} title marked, plus {@code documents.mgmt.info},
     * so that only the declaration's shape can be refused.
     */
    private static JsonObject noSchemeDeclaration() {
        JsonObject config = markedShared();
        DocsConfigs.withDocumentInfo(config, MGMT, "Mgmt", "1");
        return config;
    }

    // ---------------------------------------------------------------------------------------------
    // @ApiDocs without configuration publishes the document
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("annotatedPublicVariants")
    @DisplayName(
            "@ApiDocs without configuration publishes the document with the interface's info, and a configured info replaces it")
    void apiDocsWithoutConfigurationPublishesTheDocument(
            String label, JsonObject configuration, Map<String, Object> expectedInfo, Vertx vertx) throws Exception {
        // Given: the shared fixture with AnnotatedPublicApi in place of PublicApi
        StartupProvisions component = Composition.ANNOTATED_PUBLIC.create(configuration);

        // When: it is deployed and the public document's JSON form is requested
        Outcome outcome = deploy(vertx, component);
        try {
            assertTrue(
                    outcome.deployed(), () -> label + ": the composition deploys; it failed with " + outcome.failure());
            assertNotNull(outcome.port(), label + ": the deployment publishes its port");
            HttpResponse<Buffer> response = getJsonDocument(outcome.port());

            // Then: the documentation mount answers, the info is exactly the expected literal, and
            // the store holds exactly the public entry
            assertEquals(200, response.statusCode(), label + ": status of GET " + PUBLIC_JSON_URL);
            assertNull(
                    response.getHeader(MarkerRouterMount.HEADER),
                    label + ": answered by the documentation mount, not the marker mount");
            JsonObject info = new JsonObject(response.bodyAsString()).getJsonObject("info");
            assertEquals(new JsonObject(expectedInfo), info, label + ": the document's info");
            assertEquals(
                    Set.of(PUBLIC),
                    PublicationAccess.names(component.documentStore()),
                    label + ": the stored documents");
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    static Stream<Arguments> annotatedPublicVariants() {
        return Stream.of(
                Arguments.of("(i) no apidocs section at all", DocsConfigs.loopback(), ANNOTATED_INFO),
                Arguments.of("(ii) only documents.public.info configured", configuredInfoOnly(), CONFIGURED_INFO));
    }

    /** The loopback configuration with only {@code apidocs.documents.public.info} set. */
    private static JsonObject configuredInfoOnly() {
        return DocsConfigs.withDocumentInfo(DocsConfigs.loopback(), PUBLIC, "Configured", "9");
    }

    // ---------------------------------------------------------------------------------------------
    // Protected @ApiDocs values are checked at startup
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedVariants")
    @DisplayName(
            "a protected document is refused at startup naming the application, its interface, and the missing handler or enforcement; a public-only composition serves")
    void protectedAccessIsCheckedAtStartup(
            String label,
            Composition composition,
            @Nullable List<String> fragments,
            List<String> absentFragments,
            Vertx vertx)
            throws Exception {
        boolean control = fragments == null;

        // Given: the row's bindings and declaration, with documents.mgmt.info configured on a refused row
        JsonObject configuration = control ? DocsConfigs.shared() : markedSharedWithMgmtInfo();
        StartupProvisions component = composition.create(configuration);

        // When: it is deployed
        Outcome outcome = deploy(vertx, component);
        try {
            if (control) {
                // Then: the public-only composition deploys and serves its document
                assertTrue(
                        outcome.deployed(),
                        () -> label + ": the composition deploys; it failed with " + outcome.failure());
                assertNotNull(outcome.port(), label + ": the deployment publishes its port");
                HttpResponse<Buffer> response = getJsonDocument(outcome.port());
                assertEquals(200, response.statusCode(), label + ": status of GET " + PUBLIC_JSON_URL);
                assertNull(
                        response.getHeader(MarkerRouterMount.HEADER),
                        label + ": answered by the documentation mount, not the marker mount");
            } else {
                // Then: startup failed before any JAX-RS router, naming the application and what is missing
                assertStartupFailure(label, outcome, component, SpyCheck.HOOK_ONLY, MGMT, fragments, absentFragments);
            }
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    static Stream<Arguments> protectedVariants() {
        return Stream.of(
                Arguments.of(
                        "no scheme handler, the enforcement marker bound",
                        Composition.PROTECTED_ENFORCEMENT_ONLY,
                        List.of(PROTECTED_MGMT_API, SECURITY_SCHEME_ATTRIBUTE, BEARER_AUTH, NO_HANDLER),
                        List.of()),
                Arguments.of(
                        "only an otherAuth handler, the enforcement marker bound",
                        Composition.PROTECTED_OTHER_AUTH,
                        List.of(PROTECTED_MGMT_API, SECURITY_SCHEME_ATTRIBUTE, BEARER_AUTH, NO_HANDLER),
                        List.of()),
                Arguments.of(
                        "the bearerAuth handler, no enforcement marker",
                        Composition.PROTECTED_BEARER_AUTH_ONLY,
                        List.of(PROTECTED_MGMT_API, ACCESS_ATTRIBUTE, NO_ENFORCEMENT),
                        List.of(NOT_SERVED_YET)),
                Arguments.of(
                        "AuthenticatedMgmtApi, the bearerAuth handler, no enforcement marker",
                        Composition.AUTHENTICATED_BEARER_AUTH_ONLY,
                        List.of(AUTHENTICATED_MGMT_API, ACCESS_ATTRIBUTE, NO_ENFORCEMENT),
                        List.of(NOT_SERVED_YET)),
                Arguments.of(
                        "control: the shared fixture's public document only, no handler, no marker",
                        Composition.SHARED,
                        null,
                        List.of()));
    }

    /** The shared configuration, the {@code public} title marked, plus {@code documents.mgmt.info}. */
    private static JsonObject markedSharedWithMgmtInfo() {
        JsonObject config = markedShared();
        DocsConfigs.withDocumentInfo(config, MGMT, "Mgmt", "1");
        return config;
    }

    // ---------------------------------------------------------------------------------------------
    // A JAX-RS mount under the documentation prefix
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("jaxRsMountsNearThePrefix")
    @DisplayName(
            "a JAX-RS mount at or under the documentation prefix fails startup before any router; one beside the prefix, or under a moved prefix, deploys")
    void jaxRsMountUnderPrefixFailsBeforeAnyRouter(
            String label,
            String mountPath,
            JsonObject configuration,
            @Nullable List<String> fragments,
            @Nullable String probeUrl,
            @Nullable String documentUrl,
            Vertx vertx)
            throws Exception {
        boolean control = fragments == null;

        // Given: the shared fixture with one hand-built JAX-RS mount holding one resource at the row's path
        PrefixProbeResource resource = new PrefixProbeResource();
        StartupProvisions component = DaggerStartupTestComponents_HandBuiltMountStartupComponent.factory()
                .create(configuration, HandBuiltMounts.of(HandBuiltMounts.Mount.at(mountPath, resource)));

        // When: it is deployed
        Outcome outcome = deploy(vertx, component);
        try {
            if (control) {
                // Then: the composition deploys, the mount answers from its resource, and the moved
                // prefix serves the document from the documentation mount
                assertTrue(
                        outcome.deployed(),
                        () -> label + ": the composition deploys; it failed with " + outcome.failure());
                assertNotNull(outcome.port(), label + ": the deployment publishes its port");
                assertAnsweredBy(label, outcome.port(), probeUrl, resource);
                if (documentUrl != null) {
                    HttpResponse<Buffer> response = get(outcome.port(), documentUrl);
                    assertEquals(200, response.statusCode(), label + ": status of GET " + documentUrl);
                    assertNull(
                            response.getHeader(MarkerRouterMount.HEADER),
                            label + ": answered by the documentation mount, not the marker mount");
                }
            } else {
                // Then: startup failed before any router with the documentation prefix violation
                assertStartupFailure(label, outcome, component, SpyCheck.BOTH, null, fragments, List.of());
                String message = outcome.failure().getMessage();
                assertTrue(
                        message.startsWith(INVALID_MOUNT_CONFIGURATION),
                        () -> label + ": a composition violation fails startup: " + message);
            }
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    static Stream<Arguments> jaxRsMountsNearThePrefix() {
        return Stream.of(
                Arguments.of(
                        "/apidocs/* lies at the prefix: refused",
                        "/apidocs/*",
                        markedShared(),
                        List.of("jaxrs:/apidocs/*", "'/apidocs/*'", APIDOCS_PATH_SETTING, QUOTED_DEFAULT_PREFIX),
                        null,
                        null),
                Arguments.of(
                        "/apidocs/admin/* lies under the prefix: refused",
                        "/apidocs/admin/*",
                        markedShared(),
                        List.of(
                                "jaxrs:/apidocs/admin/*",
                                "'/apidocs/admin/*'",
                                APIDOCS_PATH_SETTING,
                                QUOTED_DEFAULT_PREFIX),
                        null,
                        null),
                Arguments.of(
                        "control: /apidocsx/* only shares the prefix's characters: deploys",
                        "/apidocsx/*",
                        DocsConfigs.shared(),
                        null,
                        "/apidocsx/probe",
                        null),
                Arguments.of(
                        "control: /apidocs/* with apidocs.path /docs: deploys and serves under /docs",
                        "/apidocs/*",
                        DocsConfigs.withApidocsPath(DocsConfigs.shared(), "/docs"),
                        null,
                        "/apidocs/probe",
                        "/docs/public/openapi.json"));
    }

    // ---------------------------------------------------------------------------------------------
    // Synthetic document operation ids are reserved
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("reservedOperationIds")
    @DisplayName(
            "an operation using a document's synthetic operation id fails startup naming the id, the route, the mount, the application, and its entry; other ids deploy")
    void reservedSyntheticOperationIdsFailStartup(
            String label,
            ReservedIdPlacement placement,
            CountingResource resource,
            JsonObject configuration,
            @Nullable List<String> fragments,
            String resourceUrl,
            Vertx vertx)
            throws Exception {
        boolean control = fragments == null;

        // Given: the shared fixture with the row's reserved-id resource where the row places it
        StartupProvisions component = placement.create(configuration, resource);

        // When: it is deployed
        Outcome outcome = deploy(vertx, component);
        try {
            if (control) {
                // Then: the composition deploys and GET /x answers from the resource
                assertTrue(
                        outcome.deployed(),
                        () -> label + ": the composition deploys; it failed with " + outcome.failure());
                assertNotNull(outcome.port(), label + ": the deployment publishes its port");
                assertAnsweredBy(label, outcome.port(), resourceUrl, resource);
            } else {
                // Then: startup failed before listening, naming the reservation, and no document is stored
                assertStartupFailure(label, outcome, component, SpyCheck.NONE, null, fragments, List.of());
                assertEquals(
                        Set.of(),
                        PublicationAccess.names(component.documentStore()),
                        label + ": no document is stored");
            }
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    static Stream<Arguments> reservedOperationIds() {
        return Stream.of(
                Arguments.of(
                        "apidocs:public:json on the hand-built mount /api/other/*: refused",
                        ReservedIdPlacement.HAND_BUILT_MOUNT,
                        new ReservedJsonResource(),
                        markedShared(),
                        List.of(
                                PUBLIC_JSON_ID,
                                GET_X,
                                OTHER_MOUNT_PATH,
                                QUOTED_PUBLIC,
                                PUBLIC_API,
                                PUBLIC_DOCUMENT_SETTING),
                        "/api/other/x"),
                Arguments.of(
                        "apidocs:public:yaml on the hand-built mount /api/other/*: refused",
                        ReservedIdPlacement.HAND_BUILT_MOUNT,
                        new ReservedYamlResource(),
                        markedShared(),
                        List.of(
                                PUBLIC_YAML_ID,
                                GET_X,
                                OTHER_MOUNT_PATH,
                                QUOTED_PUBLIC,
                                PUBLIC_API,
                                PUBLIC_DOCUMENT_SETTING),
                        "/api/other/x"),
                Arguments.of(
                        "apidocs:public:json listed by the documented application itself: refused",
                        ReservedIdPlacement.DOCUMENTED_APPLICATION,
                        new ReservedJsonResource(),
                        markedShared(),
                        List.of(
                                PUBLIC_JSON_ID,
                                GET_X,
                                PUBLIC_MOUNT_PATH,
                                QUOTED_PUBLIC,
                                PUBLIC_RESERVED_API,
                                PUBLIC_DOCUMENT_SETTING),
                        "/api/public/x"),
                Arguments.of(
                        "control: apidocs:mgmt:json, mgmt has no document: deploys",
                        ReservedIdPlacement.HAND_BUILT_MOUNT,
                        new ReservedMgmtResource(),
                        DocsConfigs.shared(),
                        null,
                        "/api/other/x"),
                Arguments.of(
                        "control: apidocs:Public:json differs in letter case: deploys",
                        ReservedIdPlacement.HAND_BUILT_MOUNT,
                        new ReservedCaseResource(),
                        DocsConfigs.shared(),
                        null,
                        "/api/other/x"),
                Arguments.of(
                        "control: apidocs:public:json with documents.public.enabled false: deploys",
                        ReservedIdPlacement.HAND_BUILT_MOUNT,
                        new ReservedJsonResource(),
                        DocsConfigs.withDocumentEnabled(DocsConfigs.shared(), PUBLIC, false),
                        null,
                        "/api/other/x"));
    }

    /** Where a row places its reserved-id resource. */
    enum ReservedIdPlacement {
        /** On a hand-built JAX-RS mount at {@code /api/other/*}, beside the shared fixture. */
        HAND_BUILT_MOUNT {
            @Override
            StartupProvisions create(JsonObject configuration, CountingResource resource) {
                return DaggerStartupTestComponents_HandBuiltMountStartupComponent.factory()
                        .create(
                                configuration,
                                HandBuiltMounts.of(HandBuiltMounts.Mount.at(OTHER_MOUNT_PATH, resource)));
            }
        },
        /** Listed by {@code PublicReservedApi}, the documented application, with no hand-built mount. */
        DOCUMENTED_APPLICATION {
            @Override
            StartupProvisions create(JsonObject configuration, CountingResource resource) {
                return DaggerStartupTestComponents_PublicReservedStartupComponent.factory()
                        .create(configuration, ContributedResources.of(resource));
            }
        };

        /**
         * Creates a fresh component holding the resource.
         *
         * @param configuration the application configuration
         * @param resource      the reserved-id resource
         * @return the component, nothing provisioned yet
         */
        abstract StartupProvisions create(JsonObject configuration, CountingResource resource);
    }

    // ---------------------------------------------------------------------------------------------
    // Disabled documentation adds no failure path
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("disabledDocumentationBuilds")
    @DisplayName(
            "disabled documentation adds no failure path: disabled builds deploy and answer from their resources with no documentation check, sink, or warning; only a malformed global switch fails")
    void disabledDocumentationAddsNoFailurePath(
            String label, String configuration, boolean zeroDeclaration, @Nullable String failurePath, Vertx vertx)
            throws Exception {
        // Given: a three-segment resource and a resource using the synthetic id apidocs:api:json, held
        // by the root application, or by the default mount beside two early hand-built mounts
        ThreeSegmentsResource threeSegments = new ThreeSegmentsResource();
        ApiJsonIdResource apiJsonId = new ApiJsonIdResource();
        ContributedResources resources = ContributedResources.of(threeSegments, apiJsonId);
        Map<String, CountingResource> routes = new LinkedHashMap<>();
        routes.put("/p/q/r", threeSegments);
        routes.put("/x", apiJsonId);
        StartupProvisions component;
        CompositionExtensions extensions;
        if (zeroDeclaration) {
            PrefixProbeResource prefixProbe = new PrefixProbeResource();
            TenantProbeResource tenantProbe = new TenantProbeResource();
            ZeroDeclarationStartupComponent zero = DaggerStartupTestComponents_ZeroDeclarationStartupComponent.factory()
                    .create(
                            new JsonObject(configuration),
                            resources,
                            HandBuiltMounts.of(
                                    new HandBuiltMounts.Mount("/apidocs/*", prefixProbe, EARLY_PRIORITY),
                                    new HandBuiltMounts.Mount("/:tenant/*", tenantProbe, EARLY_PRIORITY)));
            component = zero;
            extensions = zero;
            routes.put("/apidocs/probe", prefixProbe);
            routes.put("/acme/tenant-probe", tenantProbe);
        } else {
            RootApplicationStartupComponent root = DaggerStartupTestComponents_RootApplicationStartupComponent.factory()
                    .create(new JsonObject(configuration), resources);
            component = root;
            extensions = root;
        }

        // When: it is deployed
        Outcome outcome = deploy(vertx, component);
        try {
            if (failurePath != null) {
                // Then: the malformed switch fails startup before listening, naming its path
                assertStartupFailure(label, outcome, component, SpyCheck.NONE, null, List.of(failurePath), List.of());
            } else {
                // Then: the build deploys, every route answers from its resource, and neither the
                // documentation module's validator nor its sink is contributed
                assertTrue(
                        outcome.deployed(),
                        () -> label + ": the composition deploys; it failed with " + outcome.failure());
                assertNotNull(outcome.port(), label + ": the deployment publishes its port");
                for (Map.Entry<String, CountingResource> route : routes.entrySet()) {
                    assertAnsweredBy(label, outcome.port(), route.getKey(), route.getValue());
                }
                assertAll(
                        label + ": no documentation element is contributed",
                        () -> assertTrue(
                                extensions.mountCompositionValidators().stream()
                                        .noneMatch(DocsCompositionValidator.class::isInstance),
                                label + ": no documentation composition validator"),
                        () -> assertTrue(
                                extensions.publicationSinks().stream().noneMatch(DocsPublicationSink.class::isInstance),
                                label + ": no documentation publication sink"));
            }
            // Then: no warning is written
            assertEquals(List.of(), capturedWarnings(), label + ": no WARN from the documentation module");
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    static Stream<Arguments> disabledDocumentationBuilds() {
        return Stream.of(
                Arguments.of(
                        "(i) the root application's document disabled, its info and serverUrl broken: deploys",
                        DISABLED_ENTRY_WITH_BROKEN_CONTENT,
                        false,
                        null),
                Arguments.of(
                        "(ii) apidocs.enabled false with invalid typed settings and entries: deploys",
                        GLOBALLY_DISABLED_WITH_INVALID_SETTINGS,
                        false,
                        null),
                Arguments.of(
                        "(iii) apidocs.enabled \"false\", a string: fails naming apidocs.enabled",
                        MALFORMED_GLOBAL_SWITCH,
                        false,
                        "apidocs.enabled"),
                Arguments.of(
                        "(iv) no declared application, no apidocs section, mounts at /apidocs/* and /:tenant/*: deploys",
                        NO_APIDOCS_SECTION,
                        true,
                        null));
    }

    /** Returns the formatted messages of the captured events at level {@code WARN}. */
    private List<String> capturedWarnings() {
        return List.copyOf(warnings.list).stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // ---------------------------------------------------------------------------------------------
    // A documentation mount its composition validator did not mark refuses to build
    // ---------------------------------------------------------------------------------------------

    /**
     * Deploys the Dagger-built verticle, then, with it still deployed, verticles built with the
     * public five-argument constructor from fresh provisions, and finally calls the documentation
     * mount of a fresh provision directly.
     *
     * <p>{@link StartupDeployments#deploy} removes the published port from the local map before each
     * deployment, so the port the still-deployed first verticle published, kept in its own outcome,
     * cannot be read as a later refused deployment's port. The lifecycle hook has no reset, so its
     * count is read before each refused step and compared after it; the router spy is reset before
     * each step. Every deployment is undeployed at the end.
     */
    @Test
    @DisplayName(
            "a documentation mount its composition validator did not mark refuses to create its router, naming the mount, the cause, and the remedy")
    void unvalidatedDocsMountRefusesToCreateItsRouter(Vertx vertx) throws Exception {
        // Given: the shared fixture exposing what the five-argument constructor receives
        VerticleInputsStartupComponent component = DaggerStartupTestComponents_VerticleInputsStartupComponent.factory()
                .create(DocsConfigs.shared());
        try (Cleanup cleanup = new Cleanup()) {
            // When (i): the Dagger-built verticle is deployed
            Outcome first = deploy(vertx, component);
            cleanup.step("undeploy the Dagger-built verticle", () -> StartupDeployments.undeploy(vertx, first));

            // Then (i): it serves the public document
            assertTrue(
                    first.deployed(),
                    () -> "(i): the Dagger-built verticle deploys; it failed with " + first.failure());
            assertNotNull(first.port(), "(i): the Dagger-built verticle publishes its port");
            HttpResponse<Buffer> response = getJsonDocument(first.port());
            assertEquals(200, response.statusCode(), "(i): status of GET " + PUBLIC_JSON_URL);
            assertNull(
                    response.getHeader(MarkerRouterMount.HEADER),
                    "(i): answered by the documentation mount, not the marker mount");

            // When (ii): with (i) still deployed, a five-argument verticle from a fresh provision is deployed
            component.routerSpy().reset();
            int hookCallsBeforeSecond = component.lifecycleHook().beforeAuthSetupCalls();
            Outcome fiveArgument =
                    StartupDeployments.deploy(vertx, () -> fiveArgumentVerticle(component, component.routerMounts()));
            cleanup.step("undeploy the five-argument verticle", () -> StartupDeployments.undeploy(vertx, fiveArgument));

            // Then (ii): it is refused by the documentation mount before any JAX-RS router
            assertUnvalidatedRefusal(
                    "(ii) five-argument verticle",
                    fiveArgument,
                    component.lifecycleHook().beforeAuthSetupCalls() - hookCallsBeforeSecond);

            // When (iii): the same from a provision also holding a hand-built mount the skipped
            // validator would reject
            component.routerSpy().reset();
            int hookCallsBeforeThird = component.lifecycleHook().beforeAuthSetupCalls();
            Outcome fiveArgumentWithMount = StartupDeployments.deploy(vertx, () -> {
                Set<RouterMount> mounts = new HashSet<>(component.routerMounts());
                mounts.add(HandBuiltMounts.Mount.at(MARKED_DOCS_MOUNT_PATH, new PrefixProbeResource())
                        .create(component.jaxRsRouterMountFactory()));
                return fiveArgumentVerticle(component, mounts);
            });
            cleanup.step(
                    "undeploy the five-argument verticle with a hand-built mount",
                    () -> StartupDeployments.undeploy(vertx, fiveArgumentWithMount));

            // Then (iii): the same refusal, never naming the hand-built mount
            assertUnvalidatedRefusal(
                    "(iii) five-argument verticle with a mount at " + MARKED_DOCS_MOUNT_PATH,
                    fiveArgumentWithMount,
                    component.lifecycleHook().beforeAuthSetupCalls() - hookCallsBeforeThird);

            // When (iv): the documentation mount of a fresh provision creates its router directly
            DocsRouterMount docsMount = onlyDocsMount(component.routerMounts());
            Future<Router> router = null;
            RuntimeException refusal = null;
            try {
                router = docsMount.createRouter(vertx);
            } catch (RuntimeException thrown) {
                refusal = thrown;
            }

            // Then (iv): it throws the same refusal and returns no router
            Future<Router> returned = router;
            RuntimeException thrown = refusal;
            assertAll(
                    "(iv) direct createRouter",
                    () -> assertNull(returned, "(iv): no router is returned"),
                    () -> assertNotNull(thrown, "(iv): createRouter throws"));
            assertUnvalidatedMessage("(iv) direct createRouter", thrown.getMessage());
        }
    }

    /**
     * Builds an {@link HttpVerticle} with the public five-argument constructor from the component's
     * provisions and the given mounts; no composition validator is passed.
     */
    private static HttpVerticle fiveArgumentVerticle(VerticleInputs inputs, Set<RouterMount> mounts) {
        return new HttpVerticle(
                inputs.httpServerOptions(),
                inputs.routerCustomizers(),
                inputs.middlewares(),
                mounts,
                inputs.mountCustomizers());
    }

    /** Returns the one documentation mount of a provision. */
    private static DocsRouterMount onlyDocsMount(Set<RouterMount> mounts) {
        List<DocsRouterMount> docsMounts = mounts.stream()
                .filter(DocsRouterMount.class::isInstance)
                .map(DocsRouterMount.class::cast)
                .toList();
        assertEquals(1, docsMounts.size(), "the provision holds one documentation mount");
        return docsMounts.get(0);
    }

    /**
     * Asserts a deployment refused as unvalidated: it failed, published no port, built no JAX-RS
     * router, and its message names the documentation mount, the cause, and the remedy.
     */
    private static void assertUnvalidatedRefusal(String label, Outcome outcome, int jaxRsRouterBuilds) {
        assertAll(
                label + ": refused before listening",
                () -> assertNotNull(outcome.failure(), label + ": the deployment succeeded on port " + outcome.port()),
                () -> assertNull(outcome.port(), label + ": no port is published"),
                () -> assertEquals(NO_ROUTER, jaxRsRouterBuilds, label + ": no JAX-RS router is built"));
        String message = outcome.failure().getMessage();
        assertNotNull(message, () -> label + ": the failure has a message: " + outcome.failure());
        assertUnvalidatedMessage(label, message);
    }

    /** Asserts the unvalidated-mount refusal's fragments and that the marker is not echoed. */
    private static void assertUnvalidatedMessage(String label, String message) {
        assertAll(
                label + ": the refusal " + message,
                () -> assertTrue(message.contains(DOCS_MOUNT_NAMED), label + ": names " + DOCS_MOUNT_NAMED),
                () -> assertTrue(message.contains(UNVALIDATED_CAUSE), label + ": names " + UNVALIDATED_CAUSE),
                () -> assertTrue(message.contains(UNVALIDATED_REMEDY), label + ": names " + UNVALIDATED_REMEDY),
                () -> assertFalse(message.contains(MARKER), label + ": never names " + MARKER));
    }

    // ---------------------------------------------------------------------------------------------
    // Supporting checks: several value violations, and the order of the mount's own checks
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "several protected documents whose schemes no handler has fail startup in one refusal, one line per document in name order")
    void severalProtectedValueViolationsFailStartupTogether(Vertx vertx) throws Exception {
        // Given: PublicApi, ProtectedMgmtApi (bearerAuth), and ProtectedOpsApi (otherAuth), the
        // enforcement marker bound and no scheme handler, with both protected documents' info configured
        JsonObject configuration = markedSharedWithMgmtInfo();
        DocsConfigs.withDocumentInfo(configuration, OPS, "Ops", "1");
        StartupProvisions component = DaggerStartupTestComponents_TwoProtectedEnforcementOnlyComponent.factory()
                .create(configuration);

        // When: it is deployed
        Outcome outcome = deploy(vertx, component);
        try {
            // Then: startup failed before any JAX-RS router, naming both documents' missing handlers
            String label = "two protected documents, no scheme handler";
            assertStartupFailure(
                    label,
                    outcome,
                    component,
                    SpyCheck.HOOK_ONLY,
                    MGMT,
                    List.of(PROTECTED_MGMT_API, PROTECTED_OPS_API, SECURITY_SCHEME_ATTRIBUTE, NO_HANDLER),
                    List.of(NO_ENFORCEMENT, NOT_SERVED_YET));

            // and: in one message, the mgmt line before the ops line, one missing-handler line each
            String message = outcome.failure().getMessage();
            int mgmtLine = message.indexOf(PROTECTED_MGMT_API);
            int opsLine = message.indexOf(PROTECTED_OPS_API);
            assertAll(
                    label + ": the refusal " + message,
                    () -> assertTrue(mgmtLine < opsLine, label + ": the mgmt line comes before the ops line"),
                    () -> assertEquals(
                            2, occurrences(message, NO_HANDLER), label + ": one missing-handler line per document"));
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    @Test
    @DisplayName(
            "a protected document breaking both value rules fails startup in one refusal listing its access line before its securityScheme line")
    void bothProtectedValueViolationsOfOneDocumentAreSorted(Vertx vertx) throws Exception {
        // Given: PublicApi and ProtectedMgmtApi (bearerAuth), with neither a scheme handler nor the
        // enforcement marker bound, and the protected document's info configured
        StartupProvisions component = DaggerStartupTestComponents_ProtectedWithoutBindingsComponent.factory()
                .create(markedSharedWithMgmtInfo());

        // When: it is deployed
        Outcome outcome = deploy(vertx, component);
        try {
            // Then: startup failed before any JAX-RS router, naming both the missing enforcement and
            // the missing handler of the one protected document
            String label = "one protected document, no scheme handler, no enforcement marker";
            assertStartupFailure(
                    label,
                    outcome,
                    component,
                    SpyCheck.HOOK_ONLY,
                    MGMT,
                    List.of(
                            PROTECTED_MGMT_API,
                            ACCESS_ATTRIBUTE,
                            NO_ENFORCEMENT,
                            SECURITY_SCHEME_ATTRIBUTE,
                            BEARER_AUTH,
                            NO_HANDLER),
                    List.of(NOT_SERVED_YET));

            // and: in one message, the access line before the securityScheme line
            String message = outcome.failure().getMessage();
            int accessLine = message.indexOf(ACCESS_ATTRIBUTE);
            int schemeLine = message.indexOf(SECURITY_SCHEME_ATTRIBUTE);
            assertAll(
                    label + ": the refusal " + message,
                    () -> assertTrue(
                            accessLine < schemeLine, label + ": the access line comes before the securityScheme line"),
                    () -> assertEquals(
                            1, occurrences(message, NO_ENFORCEMENT), label + ": one missing-enforcement line"),
                    () -> assertEquals(1, occurrences(message, NO_HANDLER), label + ": one missing-handler line"));
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    @Test
    @DisplayName(
            "an unmarked documentation mount refuses as unvalidated before it checks its protected documents' values")
    void unvalidatedRefusalPrecedesTheProtectedValueChecks(Vertx vertx) throws Exception {
        // Given: ProtectedMgmtApi, whose bearerAuth scheme no handler has, the enforcement marker bound,
        // exposing what the five-argument constructor receives
        ProtectedEnforcementOnlyVerticleInputsComponent component =
                DaggerStartupTestComponents_ProtectedEnforcementOnlyVerticleInputsComponent.factory()
                        .create(markedSharedWithMgmtInfo());
        Outcome fiveArgument = null;
        try {
            // When (i): the documentation mount of a fresh provision creates its router directly
            DocsRouterMount docsMount = onlyDocsMount(component.routerMounts());
            Future<Router> router = null;
            RuntimeException refusal = null;
            try {
                router = docsMount.createRouter(vertx);
            } catch (RuntimeException thrown) {
                refusal = thrown;
            }

            // Then (i): it throws the unvalidated-mount refusal, not the missing-handler violation
            Future<Router> returned = router;
            RuntimeException thrown = refusal;
            assertAll(
                    "(i) direct createRouter",
                    () -> assertNull(returned, "(i): no router is returned"),
                    () -> assertNotNull(thrown, "(i): createRouter throws"));
            assertUnvalidatedMessage("(i) direct createRouter", thrown.getMessage());
            assertNoValueViolation("(i) direct createRouter", thrown.getMessage());

            // When (ii): a five-argument verticle from a fresh provision is deployed
            component.routerSpy().reset();
            int hookCallsBefore = component.lifecycleHook().beforeAuthSetupCalls();
            fiveArgument =
                    StartupDeployments.deploy(vertx, () -> fiveArgumentVerticle(component, component.routerMounts()));

            // Then (ii): the same refusal before any JAX-RS router, not the missing-handler violation
            assertUnvalidatedRefusal(
                    "(ii) five-argument verticle",
                    fiveArgument,
                    component.lifecycleHook().beforeAuthSetupCalls() - hookCallsBefore);
            assertNoValueViolation(
                    "(ii) five-argument verticle", fiveArgument.failure().getMessage());
        } finally {
            StartupDeployments.undeploy(vertx, fiveArgument);
        }
    }

    /** Asserts a refusal states no {@code @ApiDocs} value violation. */
    private static void assertNoValueViolation(String label, String message) {
        assertAll(
                label + ": the refusal " + message,
                () -> assertFalse(message.contains(NO_HANDLER), label + ": does not state " + NO_HANDLER),
                () -> assertFalse(
                        message.contains(SECURITY_SCHEME_ATTRIBUTE),
                        label + ": does not name " + SECURITY_SCHEME_ATTRIBUTE));
    }

    /** Counts the non-overlapping occurrences of a fragment in a message. */
    private static int occurrences(String message, String fragment) {
        int count = 0;
        for (int index = message.indexOf(fragment); index >= 0; index = message.indexOf(fragment, index + 1)) {
            count++;
        }
        return count;
    }

    // ---------------------------------------------------------------------------------------------
    // Shared compositions, configurations, deployment, and assertions
    // ---------------------------------------------------------------------------------------------

    /**
     * A startup composition: the registrations and bindings a row deploys, created fresh per row so
     * its spies start at zero.
     *
     * @param description what the composition holds
     * @param factory     creates the component from the configuration; nothing is provisioned yet
     */
    record Composition(String description, Function<JsonObject, StartupProvisions> factory) {

        /** The shared fixture: {@code PublicApi} documented, {@code MgmtApi} undocumented, no handler, no marker. */
        static final Composition SHARED = new Composition(
                "PublicApi and MgmtApi", config -> DaggerStartupTestComponents_SharedStartupComponent.factory()
                        .create(config));

        /** {@code NoSchemeApi} in place of {@code MgmtApi}, no handler, no marker. */
        static final Composition NO_SCHEME = new Composition(
                "PublicApi and NoSchemeApi", config -> DaggerStartupTestComponents_NoSchemeStartupComponent.factory()
                        .create(config));

        /** {@code AnnotatedPublicApi} in place of {@code PublicApi}, beside {@code MgmtApi}. */
        static final Composition ANNOTATED_PUBLIC = new Composition(
                "AnnotatedPublicApi and MgmtApi",
                config -> DaggerStartupTestComponents_AnnotatedPublicStartupComponent.factory()
                        .create(config));

        /** {@code ProtectedMgmtApi}; the enforcement marker bound and no handler. */
        static final Composition PROTECTED_ENFORCEMENT_ONLY = new Composition(
                "PublicApi and ProtectedMgmtApi; enforcement marker only",
                config -> DaggerStartupTestComponents_ProtectedEnforcementOnlyComponent.factory()
                        .create(config));

        /** {@code ProtectedMgmtApi}; an {@code otherAuth} handler and the enforcement marker bound. */
        static final Composition PROTECTED_OTHER_AUTH = new Composition(
                "PublicApi and ProtectedMgmtApi; otherAuth handler and enforcement marker",
                config -> DaggerStartupTestComponents_ProtectedOtherAuthComponent.factory()
                        .create(config));

        /** {@code ProtectedMgmtApi}; the {@code bearerAuth} handler bound and no marker. */
        static final Composition PROTECTED_BEARER_AUTH_ONLY = new Composition(
                "PublicApi and ProtectedMgmtApi; bearerAuth handler only",
                config -> DaggerStartupTestComponents_ProtectedBearerAuthOnlyComponent.factory()
                        .create(config));

        /** {@code AuthenticatedMgmtApi}; the {@code bearerAuth} handler bound and no marker. */
        static final Composition AUTHENTICATED_BEARER_AUTH_ONLY = new Composition(
                "PublicApi and AuthenticatedMgmtApi; bearerAuth handler only",
                config -> DaggerStartupTestComponents_AuthenticatedBearerAuthOnlyComponent.factory()
                        .create(config));

        /**
         * Creates a fresh component.
         *
         * @param config the application configuration
         * @return the component, nothing provisioned yet
         */
        StartupProvisions create(JsonObject config) {
            return factory.apply(config);
        }

        @Override
        public String toString() {
            return description;
        }
    }

    /** Which spies a refused startup must leave at zero. */
    enum SpyCheck {
        /** Neither the router spy nor the hook ran: no router at all was created. */
        BOTH,
        /** The hook did not run: no JAX-RS router was built; the router spy is not observed. */
        HOOK_ONLY,
        /** Neither spy is observed: the refusal may come after routers were built, but before listening. */
        NONE
    }

    /**
     * Returns the shared configuration with the {@code public} document's {@code info.title} set to a
     * marked value, which a failure message must not echo.
     */
    private static JsonObject markedShared() {
        JsonObject config = DocsConfigs.shared();
        DocsConfigs.withDocumentInfo(config, PUBLIC, MARKED_TITLE, DocsConfigs.PUBLIC_VERSION);
        return config;
    }

    /**
     * Deploys the component's {@code HttpVerticle}, provisioned inside the deployment so that a
     * refused provision is the deployment's failure. The router spy is reset first.
     */
    private static Outcome deploy(Vertx vertx, StartupProvisions component) throws Exception {
        component.routerSpy().reset();
        return StartupDeployments.deploy(vertx, component::httpVerticle);
    }

    /** Sends {@code GET} for the {@code public} document's JSON form and waits for the whole response. */
    private HttpResponse<Buffer> getJsonDocument(int port) throws Exception {
        return Futures.await(client.get(port, LOOPBACK, PUBLIC_JSON_URL).send(), Duration.ofSeconds(15));
    }

    /** Sends {@code GET} for a path and waits for the whole response. */
    private HttpResponse<Buffer> get(int port, String path) throws Exception {
        return Futures.await(client.get(port, LOOPBACK, path).send(), Duration.ofSeconds(15));
    }

    /**
     * Asserts that {@code GET} of a path answers {@code 200} with the resource's marker and is
     * counted by that resource exactly once.
     */
    private void assertAnsweredBy(String label, int port, String path, CountingResource resource) throws Exception {
        int hitsBefore = resource.hits();
        HttpResponse<Buffer> response = get(port, path);
        assertAll(
                label + ": GET " + path + " is answered by its resource",
                () -> assertEquals(200, response.statusCode(), label + ": status of GET " + path),
                () -> assertEquals(resource.marker(), response.bodyAsString(), label + ": body of GET " + path),
                () -> assertEquals(hitsBefore + 1, resource.hits(), label + ": the resource counted GET " + path));
    }

    /**
     * Asserts a refused startup: the deployment failed, published no port, and left the spies named
     * by {@code spies} at zero; its message contains every fragment and names the application, when
     * one is given, standing on its own; it contains no absent fragment and never the marker. The
     * caller undeploys an unexpected success.
     *
     * @param label           the row
     * @param outcome         the deployment's outcome
     * @param component       the deployed component, whose spies are read
     * @param spies           which spies must count zero
     * @param application     the application the message must name, or {@code null}
     * @param fragments       the texts the message must contain
     * @param absentFragments the texts the message must not contain
     */
    private static void assertStartupFailure(
            String label,
            Outcome outcome,
            StartupProvisions component,
            SpyCheck spies,
            @Nullable String application,
            List<String> fragments,
            List<String> absentFragments)
            throws Exception {
        int routerBuilds = component.routerSpy().createRouterCalls();
        int jaxRsRouterBuilds = component.lifecycleHook().beforeAuthSetupCalls();
        assertAll(
                label + ": startup is refused before any router",
                () -> assertNotNull(outcome.failure(), label + ": the deployment succeeded on port " + outcome.port()),
                () -> assertNull(outcome.port(), label + ": no port is published"),
                () -> {
                    if (spies != SpyCheck.NONE) {
                        assertEquals(NO_ROUTER, jaxRsRouterBuilds, label + ": no JAX-RS router is built");
                    }
                },
                () -> {
                    if (spies == SpyCheck.BOTH) {
                        assertEquals(NO_ROUTER, routerBuilds, label + ": no router is created");
                    }
                });

        Throwable failure = outcome.failure();
        String message = failure.getMessage();
        assertNotNull(message, () -> label + ": the failure has a message: " + failure);
        List<Executable> checks = new ArrayList<>();
        for (String fragment : fragments) {
            checks.add(() -> assertTrue(message.contains(fragment), label + ": names " + fragment));
        }
        if (application != null) {
            checks.add(() -> assertTrue(
                    standingAlone(application).matcher(message).find(),
                    label + ": names the application '" + application + "' on its own"));
        }
        for (String absent : absentFragments) {
            checks.add(() -> assertFalse(message.contains(absent), label + ": does not state " + absent));
        }
        checks.add(() -> assertFalse(message.contains(MARKER), label + ": echoes no configured value"));
        assertAll(label + ": the failure " + failure, checks.stream());
    }

    /**
     * Returns a pattern matching {@code word} when no word character, {@code .}, {@code /}, or
     * {@code -} touches it, so a configuration path, mount path, or binary name containing the word
     * does not match.
     */
    private static Pattern standingAlone(String word) {
        return Pattern.compile("(?<![\\w./-])" + Pattern.quote(word) + "(?![\\w./-])");
    }
}
