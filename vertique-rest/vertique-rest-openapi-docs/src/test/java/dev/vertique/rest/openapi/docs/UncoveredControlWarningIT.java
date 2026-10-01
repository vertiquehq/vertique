// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupDeployments;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Integration proof that each enabled document gets one startup warning naming the mount-scoped
 * controls that apply to its application's mount but not to the document routes.
 *
 * <p>Every component binds the same controls: a customizer matching only {@code /api/mgmt/*}, a
 * customizer matching every mount (the documentation mount included), an {@code API}-scoped
 * middleware, a router lifecycle hook, an interceptor overriding {@code beforeRequest}, an
 * interceptor overriding only {@code onRequest}, and the framework's content-type middleware. Three
 * compositions are observed:
 *
 * <ul>
 *   <li>two public documents with resources, deployed as two instances of one component: each
 *       document is warned about once, naming the controls that reach its mount, and the listed
 *       controls leave out the match-all customizer, the content-type middleware, and the
 *       {@code onRequest}-only interceptor;
 *   <li>a protected document: its warning names the same controls but not the statement that no
 *       operation handler contributor runs, since its routes run them; the warning comes from the
 *       composition checks, before any router is created, so it is observed whatever the
 *       deployment's outcome;
 *   <li>a document whose mount holds no resource: hooks, interceptors, and middleware never run on
 *       that mount, so no warning names it.
 * </ul>
 *
 * <p>Warnings are captured on the documentation module's warning logger. Only the fixed fragments,
 * the configuration path, the mount path, and each {@code <kind> <binary class name>} entry are
 * asserted; entries are read from the message with the {@code CONTROL_ENTRY} pattern, so the list's exact
 * contents and order are checked independently of its separators.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class UncoveredControlWarningIT {

    /** The documentation module's warning logger. */
    private static final String WARNINGS_LOGGER = "dev.vertique.rest.openapi.docs.DocumentWarnings";

    /** The configuration path of the management document. */
    private static final String MGMT_DOCUMENT = "apidocs.documents.mgmt";

    /** The configuration path of the public document. */
    private static final String PUBLIC_DOCUMENT = "apidocs.documents.public";

    /** The configuration path of the empty application's document. */
    private static final String EMPTY_DOCUMENT = "apidocs.documents.empty";

    /** The management application's mount path. */
    private static final String MGMT_MOUNT = "/api/mgmt/*";

    /** The public application's mount path. */
    private static final String PUBLIC_MOUNT = "/api/public/*";

    /** The empty application's path. */
    private static final String EMPTY_PATH = "/api/empty";

    /** The statement every warning carries. */
    private static final String NOT_COVERED = "do not cover the document routes";

    /** The statement only a public document's warning carries. */
    private static final String NO_CONTRIBUTOR = "no OperationHandlerContributor runs";

    /** The URL of the management document's JSON form. */
    private static final String MGMT_JSON_URL = "/apidocs/mgmt/openapi.json";

    /** The URL of the public document's JSON form. */
    private static final String PUBLIC_JSON_URL = "/apidocs/public/openapi.json";

    /** The controls listed for the management mount, in the specified order. */
    private static final List<String> MGMT_CONTROLS = List.of(
            "MountCustomizer dev.vertique.rest.openapi.docs.fixture.startup.warning.MgmtOnlyCustomizer",
            "API middleware dev.vertique.rest.openapi.docs.fixture.startup.warning.ApiAllowlistMiddleware",
            "RouterLifecycleHook dev.vertique.rest.openapi.docs.fixture.startup.warning.AuditHook",
            "RequestInterceptor dev.vertique.rest.openapi.docs.fixture.startup.warning.TenantInterceptor");

    /** The controls listed for the public mount, in the specified order: no customizer matches it alone. */
    private static final List<String> PUBLIC_CONTROLS = List.of(
            "API middleware dev.vertique.rest.openapi.docs.fixture.startup.warning.ApiAllowlistMiddleware",
            "RouterLifecycleHook dev.vertique.rest.openapi.docs.fixture.startup.warning.AuditHook",
            "RequestInterceptor dev.vertique.rest.openapi.docs.fixture.startup.warning.TenantInterceptor");

    /** Controls that cover the document routes or never run for them, which no event may name. */
    private static final List<String> LEFT_OUT_CONTROLS =
            List.of("EveryMountCustomizer", "ContentTypeValidationMiddleware", "LoggingOnlyInterceptor");

    /** One {@code <kind> <binary class name>} entry of a warning's control list. */
    private static final Pattern CONTROL_ENTRY =
            Pattern.compile("\\b(MountCustomizer|API middleware|RouterLifecycleHook|RequestInterceptor)"
                    + " ([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+)");

    /** The number of instances the two-document composition is deployed as. */
    private static final int INSTANCES = 2;

    /** The longest one document request is awaited. */
    private static final long REQUEST_SECONDS = 5;

    private Logger warningsLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    /** Sends the document requests; closed before the Vert.x instance. */
    private WebClient client;

    @BeforeEach
    void captureWarnings() {
        warningsLogger = (Logger) LoggerFactory.getLogger(WARNINGS_LOGGER);
        previousLevel = warningsLogger.getLevel();
        warningsLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        warningsLogger.addAppender(appender);
    }

    @AfterEach
    void releaseWarningsAndClient() {
        warningsLogger.detachAppender(appender);
        appender.stop();
        warningsLogger.setLevel(previousLevel);
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @Test
    @DisplayName("Each enabled document is warned about once, naming only the mount-scoped controls its routes bypass")
    void warnsOncePerDocumentNamingUncoveredControls(Vertx vertx) {
        client = WebClient.create(vertx, new WebClientOptions().setDefaultHost("127.0.0.1"));

        assertAll(
                () -> twoPublicDocumentsOverTwoInstances(vertx),
                () -> protectedDocument(vertx),
                () -> emptyMountDocument(vertx));
    }

    /** Variant (i): two public documents, one component deployed as two instances. */
    private void twoPublicDocumentsOverTwoInstances(Vertx vertx) throws Exception {
        // Given: public and mgmt documented and enabled, with the proof's controls bound.
        clearCapturedEvents();
        WarningTestComponents.DocumentedMgmtComponent component =
                DaggerWarningTestComponents_DocumentedMgmtComponent.factory().create(DocsConfigs.sharedWithMgmtInfo());
        StartupDeployments.Outcome outcome = null;
        try {
            // When: the component's HttpVerticle is deployed as two instances.
            outcome = StartupDeployments.deploy(
                    vertx, component::httpVerticle, new DeploymentOptions().setInstances(INSTANCES));
            List<ILoggingEvent> events = capturedEvents();

            // Then: the deployment succeeds and serves both documents.
            StartupDeployments.Outcome deployed = outcome;
            assertTrue(deployed.deployed(), () -> "two instances: deployment failed: " + deployed.failure());
            assertNotNull(deployed.port(), "two instances: no port was published");
            for (String url : List.of(MGMT_JSON_URL, PUBLIC_JSON_URL)) {
                HttpResponse<Buffer> response = get(deployed.port(), url);
                assertEquals(200, response.statusCode(), () -> "two instances: " + url + ": status");
                assertTrue(
                        response.body() != null && response.body().length() > 0,
                        () -> "two instances: " + url + ": empty body");
            }

            // Then: one warning for mgmt, naming its mount and the four controls in order, with both statements.
            String mgmtWarning = soleWarning(events, MGMT_DOCUMENT, "two instances");
            assertTrue(
                    mgmtWarning.startsWith(MGMT_DOCUMENT),
                    () -> "two instances: the mgmt warning does not start with its configuration path: " + mgmtWarning);
            assertTrue(
                    mgmtWarning.contains(MGMT_MOUNT),
                    () -> "two instances: the mgmt warning does not name " + MGMT_MOUNT + ": " + mgmtWarning);
            assertEquals(
                    MGMT_CONTROLS,
                    listedControls(mgmtWarning),
                    () -> "two instances: the mgmt warning's controls: " + mgmtWarning);
            assertTrue(
                    mgmtWarning.contains(NOT_COVERED),
                    () -> "two instances: the mgmt warning lacks the mount-scoped statement: " + mgmtWarning);
            assertTrue(
                    mgmtWarning.contains(NO_CONTRIBUTOR),
                    () -> "two instances: the mgmt warning lacks the contributor statement: " + mgmtWarning);

            // Then: one warning for public, naming the same controls except the mgmt-only customizer.
            String publicWarning = soleWarning(events, PUBLIC_DOCUMENT, "two instances");
            assertTrue(
                    publicWarning.startsWith(PUBLIC_DOCUMENT),
                    () -> "two instances: the public warning does not start with its configuration path: "
                            + publicWarning);
            assertTrue(
                    publicWarning.contains(PUBLIC_MOUNT),
                    () -> "two instances: the public warning does not name " + PUBLIC_MOUNT + ": " + publicWarning);
            assertEquals(
                    PUBLIC_CONTROLS,
                    listedControls(publicWarning),
                    () -> "two instances: the public warning's controls: " + publicWarning);
            assertTrue(
                    publicWarning.contains(NOT_COVERED),
                    () -> "two instances: the public warning lacks the mount-scoped statement: " + publicWarning);
            assertTrue(
                    publicWarning.contains(NO_CONTRIBUTOR),
                    () -> "two instances: the public warning lacks the contributor statement: " + publicWarning);

            // Then: no event names a control that covers the document routes or never runs for them.
            assertNoEventNamesLeftOutControls(events, "two instances");
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    /** Variant (ii): the management document is protected. */
    private void protectedDocument(Vertx vertx) throws Exception {
        // Given: ProtectedMgmtApi in place of the public mgmt declaration, bearerAuth and enforcement bound.
        clearCapturedEvents();
        WarningTestComponents.ProtectedMgmtComponent component =
                DaggerWarningTestComponents_ProtectedMgmtComponent.factory().create(DocsConfigs.sharedWithMgmtInfo());
        StartupDeployments.Outcome outcome = null;
        try {
            // When: deployed once; its outcome is not part of this proof.
            outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
            List<ILoggingEvent> events = capturedEvents();

            // Then: one warning for mgmt, with the four controls and the mount-scoped statement only.
            String mgmtWarning = soleWarning(events, MGMT_DOCUMENT, "protected");
            assertTrue(
                    mgmtWarning.contains(MGMT_MOUNT),
                    () -> "protected: the mgmt warning does not name " + MGMT_MOUNT + ": " + mgmtWarning);
            assertEquals(
                    MGMT_CONTROLS,
                    listedControls(mgmtWarning),
                    () -> "protected: the mgmt warning's controls: " + mgmtWarning);
            assertTrue(
                    mgmtWarning.contains(NOT_COVERED),
                    () -> "protected: the mgmt warning lacks the mount-scoped statement: " + mgmtWarning);
            assertFalse(
                    mgmtWarning.contains(NO_CONTRIBUTOR),
                    () -> "protected: the mgmt warning states that no contributor runs: " + mgmtWarning);
            assertNoEventNamesLeftOutControls(events, "protected");
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    /** Variant (iii): the only document's mount holds no resource. */
    private void emptyMountDocument(Vertx vertx) throws Exception {
        // Given: EmptyApi as the sole registration, its document enabled, with the same controls bound.
        clearCapturedEvents();
        JsonObject config = DocsConfigs.withDocumentInfo(DocsConfigs.loopback(), "empty", "Empty", "1");
        WarningTestComponents.EmptyMountComponent component =
                DaggerWarningTestComponents_EmptyMountComponent.factory().create(config);
        StartupDeployments.Outcome outcome = null;
        try {
            // When: deployed once.
            outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
            List<ILoggingEvent> events = capturedEvents();

            // Then: the deployment succeeds and no warning names the empty application.
            StartupDeployments.Outcome deployed = outcome;
            assertTrue(deployed.deployed(), () -> "empty mount: deployment failed: " + deployed.failure());
            List<String> namingEmpty = warnings(events).stream()
                    .filter(message -> message.contains(EMPTY_DOCUMENT) || message.contains(EMPTY_PATH))
                    .toList();
            assertEquals(List.of(), namingEmpty, "empty mount: warnings naming the empty application");
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    // --- Helpers ---

    /** Returns the one {@code WARN} naming a document's configuration path, failing on none or several. */
    private static String soleWarning(List<ILoggingEvent> events, String documentPath, String variant) {
        List<String> matching = warnings(events).stream()
                .filter(message -> message.contains(documentPath))
                .toList();
        assertEquals(
                1,
                matching.size(),
                () -> variant + ": WARN events for " + documentPath + " (expected exactly one): " + matching);
        return matching.get(0);
    }

    /** Returns the formatted messages of every captured {@code WARN} event, in capture order. */
    private static List<String> warnings(List<ILoggingEvent> events) {
        return events.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** Returns each {@code <kind> <binary class name>} entry of a warning, in message order. */
    private static List<String> listedControls(String message) {
        List<String> entries = new ArrayList<>();
        Matcher matcher = CONTROL_ENTRY.matcher(message);
        while (matcher.find()) {
            entries.add(matcher.group(1) + " " + matcher.group(2));
        }
        return entries;
    }

    /** Asserts that no captured event, at any level, names a left-out control. */
    private static void assertNoEventNamesLeftOutControls(List<ILoggingEvent> events, String variant) {
        for (ILoggingEvent event : events) {
            String message = event.getFormattedMessage();
            for (String leftOut : LEFT_OUT_CONTROLS) {
                assertFalse(message.contains(leftOut), () -> variant + ": an event names " + leftOut + ": " + message);
            }
        }
    }

    /** Returns a copy of every event captured so far. */
    private List<ILoggingEvent> capturedEvents() {
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }

    /** Forgets every event captured so far. */
    private void clearCapturedEvents() {
        synchronized (appender) {
            appender.list.clear();
        }
    }

    /** Sends one GET to the loopback server and waits for its response. */
    private HttpResponse<Buffer> get(int port, String url) throws Exception {
        return client.get(port, "127.0.0.1", url)
                .send()
                .toCompletionStage()
                .toCompletableFuture()
                .get(REQUEST_SECONDS, TimeUnit.SECONDS);
    }
}
