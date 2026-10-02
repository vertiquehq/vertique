// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.jaxrs.publication.CapturedSchemas;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.publication.ResponseShape;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.DocsTestComponents.DocsProvisions;
import dev.vertique.rest.openapi.docs.DocsTestComponents.Provisions;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.ManualMountModule;
import dev.vertique.rest.openapi.docs.fixture.MarkerRouterMount;
import dev.vertique.rest.openapi.docs.fixture.MgmtApi;
import dev.vertique.rest.openapi.docs.fixture.PublicApi;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Deploys the documentation module's test components over loopback HTTP and observes what it
 * publishes: nothing when no document is enabled, a document served without further schema
 * discovery, and a stored entry that retains only extracted data.
 *
 * <p>Every test deploys through {@link #deploy(Vertx, Provisions)}, which reads the bound port from
 * the {@code vertique} local map once the deployment completes, and undeploys through
 * {@link #undeploy(Vertx, Deployment)}, which clears that map. Requests go through
 * {@link #send(HttpMethod, int, String)}; a response answered by the fixture's last marker mount is
 * recognized by {@link #answeredByLastMarker(HttpResponse)}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class OpenApiDocsPublicationIT {

    /** The host every server binds and every request dials. */
    private static final String LOOPBACK = "127.0.0.1";

    /** The local map the HTTP verticle publishes its bound port into. */
    private static final String LOCAL_MAP = "vertique";

    /** The local-map key of the bound port. */
    private static final String PORT_KEY = "http.port";

    /** The JSON form's file name under a document's URL prefix. */
    private static final String JSON_FORM = "openapi.json";

    /** The YAML form's file name under a document's URL prefix. */
    private static final String YAML_FORM = "openapi.yaml";

    /** The mount id of the documentation module's mount. */
    private static final String DOCS_MOUNT_ID = "apidocs";

    /** The prefix every mount path of the documentation module's mount starts with by default. */
    private static final String DOCS_MOUNT_PREFIX = DocsConfigs.DEFAULT_APIDOCS_PATH;

    /** An {@code apidocs.path} value that fails validation whenever any document is enabled. */
    private static final String INVALID_APIDOCS_PATH = "/";

    /** Operations of the shared declarations: three of {@code CatalogResource}, one of the management resource. */
    private static final int SHARED_OPERATION_COUNT = 4;

    /** Operations when the documented application's registration is inactive: the management resource's only. */
    private static final int INACTIVE_PUBLIC_OPERATION_COUNT = 1;

    /** Operations of the legacy default mount: {@code CatalogResource}'s three. */
    private static final int LEGACY_OPERATION_COUNT = 3;

    /** The number of document requests the discovery test sends. */
    private static final int DOCUMENT_REQUESTS = 50;

    /** The largest number of objects the retention walk visits before it gives up. */
    private static final int WALK_LIMIT = 100_000;

    /**
     * The types no object reachable from a stored document may be an instance of. A reflective
     * {@link Type} other than a {@link Class} is refused separately.
     */
    private static final List<Class<?>> FORBIDDEN_RETAINED_TYPES = List.of(
            RestOperationDescriptor.class,
            MountPublication.class,
            OperationPublication.class,
            OperationDetail.class,
            CapturedSchemas.class,
            InputBinding.class,
            ResponseShape.class,
            GeneratedRestApplicationRegistration.class,
            JsonObject.class,
            JsonArray.class,
            Buffer.class,
            Annotation.class,
            Member.class,
            CatalogResource.class);

    /** The parent logger of every documentation module logger. */
    private static final String DOCS_LOGGER = "dev.vertique.rest.openapi.docs";

    /**
     * The logger of the declared-application view, which logs the notice that a documented
     * application's documentation is not installed; captured by name because the class is not
     * visible here.
     */
    private static final String VIEW_LOGGER = "dev.vertique.rest.jaxrs.RestApplicationsBuilder";

    /** The keyword of the {@code INFO} line logged once per stored document. */
    private static final String STORED_KEYWORD = "stored";

    /** The application the documented fixture's stored line names. */
    private static final String STORED_APPLICATION = "public";

    /**
     * The documented application's name standing on its own, so that the mount path
     * {@value #STORED_MOUNT_PATH} alone cannot satisfy it.
     */
    private static final Pattern STORED_APPLICATION_NAMED = standingAlone(STORED_APPLICATION);

    /** The mount path the documented fixture's stored line names. */
    private static final String STORED_MOUNT_PATH = "/api/public/*";

    /** The document source the stored line names for a document assembled from the running code. */
    private static final String GENERATED_SOURCE = "generated";

    /** The number of stored lines a deployment of the documented fixture logs. */
    private static final int EXPECTED_STORED_LINES = 1;

    /** The fixed text of the view's notice that a documented application's documentation is not installed. */
    private static final String NOT_INSTALLED_NOTICE =
            "carries @ApiDocs, but the OpenAPI documentation module is not included";

    /** The documented application as the notice quotes it. */
    private static final String NOTICE_APPLICATION = "'public'";

    /** The notices logged for the documented application by a component without the module. */
    private static final int NOTICES_WITHOUT_MODULE = 1;

    /** The notices logged by a component that includes the module, whatever its configuration. */
    private static final int NOTICES_WITH_MODULE = 0;

    private WebClient client;

    private Logger docsLogger;
    private Level previousDocsLevel;
    private ListAppender<ILoggingEvent> docsAppender;

    private Logger viewLogger;
    private Level previousViewLevel;
    private ListAppender<ILoggingEvent> viewAppender;

    @BeforeEach
    void createClient(Vertx vertx) {
        client = WebClient.create(vertx);
    }

    @BeforeEach
    void captureLogs() {
        docsLogger = (Logger) LoggerFactory.getLogger(DOCS_LOGGER);
        previousDocsLevel = docsLogger.getLevel();
        docsLogger.setLevel(Level.INFO);
        docsAppender = new ListAppender<>();
        docsAppender.start();
        docsLogger.addAppender(docsAppender);

        viewLogger = (Logger) LoggerFactory.getLogger(VIEW_LOGGER);
        previousViewLevel = viewLogger.getLevel();
        viewLogger.setLevel(Level.INFO);
        viewAppender = new ListAppender<>();
        viewAppender.start();
        viewLogger.addAppender(viewAppender);
    }

    @AfterEach
    void closeClient(Vertx vertx) {
        client.close();
        vertx.sharedData().getLocalMap(LOCAL_MAP).clear();
    }

    @AfterEach
    void releaseLogs() {
        docsLogger.detachAppender(docsAppender);
        docsAppender.stop();
        docsLogger.setLevel(previousDocsLevel);

        viewLogger.detachAppender(viewAppender);
        viewAppender.stop();
        viewLogger.setLevel(previousViewLevel);
    }

    // ---------------------------------------------------------------------------------------------
    // Without an enabled document the module adds nothing
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("without an enabled document the module adds no mount, no sink, no store entry, and no schema call")
    void noEnabledDocumentAddsNothing(Vertx vertx) throws Exception {
        // Given: six compositions, none of which enables a document
        JsonObject disabledGlobally = DocsConfigs.withApidocsPath(
                DocsConfigs.withApidocsEnabled(DocsConfigs.shared(), false), INVALID_APIDOCS_PATH);
        JsonObject disabledEntry = DocsConfigs.withApidocsPath(
                DocsConfigs.withDocumentEnabled(DocsConfigs.shared(), PublicApi.NAME, false), INVALID_APIDOCS_PATH);
        List<Variant> variants = List.of(
                new Variant(
                        "undocumented declaration",
                        DaggerDocsTestComponents_UndocumentedComponent.factory().create(DocsConfigs.shared()),
                        withoutDocsModule(DocsConfigs.shared()),
                        SHARED_OPERATION_COUNT,
                        Set.of(PublicApi.MOUNT_PATH, MgmtApi.MOUNT_PATH)),
                new Variant(
                        "apidocs.enabled false with an invalid apidocs.path",
                        DaggerDocsTestComponents_SharedComponent.factory().create(disabledGlobally),
                        withoutDocsModule(disabledGlobally.copy()),
                        SHARED_OPERATION_COUNT,
                        Set.of(PublicApi.MOUNT_PATH, MgmtApi.MOUNT_PATH)),
                new Variant(
                        "entry enabled false with an invalid apidocs.path",
                        DaggerDocsTestComponents_SharedComponent.factory().create(disabledEntry),
                        withoutDocsModule(disabledEntry.copy()),
                        SHARED_OPERATION_COUNT,
                        Set.of(PublicApi.MOUNT_PATH, MgmtApi.MOUNT_PATH)),
                new Variant(
                        "documented registration inactive",
                        DaggerDocsTestComponents_InactivePublicComponent.factory()
                                .create(DocsConfigs.shared()),
                        DaggerDocsTestComponents_InactivePublicWithoutDocsModuleComponent.factory()
                                .create(DocsConfigs.shared()),
                        INACTIVE_PUBLIC_OPERATION_COUNT,
                        Set.of(MgmtApi.MOUNT_PATH)),
                new Variant(
                        "no registration, legacy default mount",
                        DaggerDocsTestComponents_LegacyDefaultMountComponent.factory()
                                .create(DocsConfigs.legacyDefaultMount()),
                        DaggerDocsTestComponents_LegacyDefaultMountWithoutDocsModuleComponent.factory()
                                .create(DocsConfigs.legacyDefaultMount()),
                        LEGACY_OPERATION_COUNT,
                        Set.of(DocsConfigs.LEGACY_BASE_PATH)),
                new Variant(
                        "without the documentation module",
                        withoutDocsModule(DocsConfigs.shared()),
                        null,
                        SHARED_OPERATION_COUNT,
                        Set.of(PublicApi.MOUNT_PATH, MgmtApi.MOUNT_PATH)));

        for (Variant variant : variants) {
            // When: the variant is deployed and its would-be document URL is requested
            VariantOutcome outcome = observe(vertx, variant.component());

            // Then: it deploys unchanged by the module
            String label = variant.label();
            assertTrue(
                    outcome.mountPaths().containsAll(variant.applicationMountPaths()),
                    label + ": the application mounts are recorded, got " + outcome.mountPaths());
            assertFalse(
                    outcome.mountIds().contains(DOCS_MOUNT_ID),
                    label + ": no documentation mount, got " + outcome.mountIds());
            assertTrue(
                    outcome.mountPaths().stream().noneMatch(path -> path.startsWith(DOCS_MOUNT_PREFIX)),
                    label + ": no mount under " + DOCS_MOUNT_PREFIX + ", got " + outcome.mountPaths());
            assertEquals(0, outcome.sinkCount(), label + ": no publication sink");
            assertTrue(outcome.answeredByLastMarker(), label + ": the document request reaches the marker mount");
            if (variant.component() instanceof DocsProvisions) {
                assertEquals(0, outcome.storeSize(), label + ": the store is empty");
            } else {
                assertNull(outcome.storeSize(), label + ": a component without the module has no store");
            }
            assertEquals(
                    variant.operationCount(), outcome.schemaCalls(), label + ": one schema-source call per operation");
            if (variant.control() != null) {
                VariantOutcome control = observe(vertx, variant.control());
                assertEquals(
                        control.schemaCalls(),
                        outcome.schemaCalls(),
                        label + ": the same schema-source calls as the composition without the module");
            }
        }
    }

    /** One composition of the no-document test, with its expectations. */
    private record Variant(
            String label,
            Provisions component,
            @Nullable Provisions control,
            int operationCount,
            Set<String> applicationMountPaths) {}

    /** What one deployment of a composition showed. */
    private record VariantOutcome(
            List<String> mountIds,
            List<String> mountPaths,
            int sinkCount,
            boolean answeredByLastMarker,
            @Nullable Integer storeSize,
            int schemaCalls) {}

    /**
     * Deploys a composition, requests the {@code public} document's JSON URL under the default prefix,
     * records what the composition shows, and undeploys it.
     */
    private VariantOutcome observe(Vertx vertx, Provisions component) throws Exception {
        Deployment deployment = deploy(vertx, component);
        try {
            HttpResponse<Buffer> response =
                    send(HttpMethod.GET, deployment.port(), documentPath(PublicApi.NAME, JSON_FORM));
            List<MountMeta> applied = component.mountCustomizer().applied();
            return new VariantOutcome(
                    applied.stream().map(MountMeta::mountId).toList(),
                    applied.stream().map(MountMeta::mountPath).toList(),
                    component.publicationSinks().size(),
                    answeredByLastMarker(response),
                    component instanceof DocsProvisions docs
                            ? docs.documentStore().names().size()
                            : null,
                    component.schemaSource().calls());
        } finally {
            undeploy(vertx, deployment);
        }
    }

    private static Provisions withoutDocsModule(JsonObject config) {
        return DaggerDocsTestComponents_WithoutDocsModuleComponent.factory().create(config);
    }

    // ---------------------------------------------------------------------------------------------
    // Document requests perform no discovery and return identical bytes
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("document requests call no schema source and return identical bytes per form")
    void documentRequestsPerformNoDiscovery(Vertx vertx) throws Exception {
        // Given: the shared fixture, deployed
        DocsTestComponents.SharedComponent component =
                DaggerDocsTestComponents_SharedComponent.factory().create(DocsConfigs.shared());
        Deployment deployment = deploy(vertx, component);
        try {
            int callsAfterDeployment = component.schemaSource().calls();
            assertEquals(
                    SHARED_OPERATION_COUNT, callsAfterDeployment, "one schema-source call per operation at startup");

            // When: requests alternate GET and HEAD over both forms
            byte[] firstJson = null;
            byte[] firstYaml = null;
            for (int i = 0; i < DOCUMENT_REQUESTS; i++) {
                HttpMethod method = i % 2 == 0 ? HttpMethod.GET : HttpMethod.HEAD;
                String form = (i / 2) % 2 == 0 ? JSON_FORM : YAML_FORM;
                String label = "request " + i + ": " + method + " " + form;
                HttpResponse<Buffer> response = send(method, deployment.port(), documentPath(PublicApi.NAME, form));

                // Then: the documentation mount answers every request
                assertAnsweredByDocs(response, label);
                if (method == HttpMethod.GET) {
                    byte[] body = response.body().getBytes();
                    assertTrue(body.length > 0, label + ": a non-empty document");
                    if (form.equals(JSON_FORM)) {
                        if (firstJson == null) {
                            firstJson = body;
                        }
                        assertArrayEquals(firstJson, body, label + ": the same JSON bytes");
                    } else {
                        if (firstYaml == null) {
                            firstYaml = body;
                        }
                        assertArrayEquals(firstYaml, body, label + ": the same YAML bytes");
                    }
                }
            }

            // Then: no request called the schema source
            int callsAfterRequests = component.schemaSource().calls();
            assertEquals(callsAfterDeployment, callsAfterRequests, "document requests perform no schema discovery");
        } finally {
            undeploy(vertx, deployment);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The store keeps only extracted data
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a stored document retains no publication, descriptor, JSON, buffer, reflective, or resource object")
    void storedDocumentRetainsNoPublicationData(Vertx vertx) throws Exception {
        // Given: the shared fixture, deployed
        DocsTestComponents.SharedComponent component =
                DaggerDocsTestComponents_SharedComponent.factory().create(DocsConfigs.shared());
        Deployment deployment = deploy(vertx, component);
        try {
            DocumentStore store = component.documentStore();
            assertTrue(
                    store.names().contains(PublicApi.NAME),
                    "the store holds the public document, got " + store.names());

            for (String name : store.names()) {
                // When: every object reachable from the completed entry is walked
                Optional<PublishedDocument> entry = store.lookup(name);
                assertTrue(entry.isPresent(), name + ": the entry is complete");
                PublishedDocument document = entry.get();
                List<String> retained = forbiddenReachableFrom(document);

                // Then: the forms are bytes and nothing forbidden is reachable
                assertInstanceOf(byte[].class, document.json(), name + ": the JSON form is a byte array");
                assertInstanceOf(byte[].class, document.yaml(), name + ": the YAML form is a byte array");
                assertTrue(document.json().length > 0, name + ": a non-empty JSON form");
                assertTrue(document.yaml().length > 0, name + ": a non-empty YAML form");
                assertEquals(List.of(), retained, name + ": the entry retains only extracted data");
            }
        } finally {
            undeploy(vertx, deployment);
        }
    }

    /**
     * Walks every object reachable from {@code root}: fields of non-JDK classes (static fields
     * excluded), array elements, collection elements, map keys and values, and optional values. JDK
     * classes are not entered through their fields. A forbidden object is reported and not entered.
     *
     * @return the forbidden objects found, each as its class name and the path that reached it
     */
    private static List<String> forbiddenReachableFrom(Object root) throws IllegalAccessException {
        Map<Object, Boolean> visited = new IdentityHashMap<>();
        Deque<Reached> pending = new ArrayDeque<>();
        List<String> found = new ArrayList<>();
        pending.push(new Reached(root, root.getClass().getSimpleName()));
        while (!pending.isEmpty()) {
            Reached reached = pending.pop();
            Object node = reached.value();
            if (visited.put(node, Boolean.TRUE) != null) {
                continue;
            }
            if (visited.size() > WALK_LIMIT) {
                fail("the retention walk exceeded " + WALK_LIMIT + " objects at " + reached.path());
            }
            if (isForbidden(node)) {
                found.add(node.getClass().getName() + " at " + reached.path());
                continue;
            }
            Class<?> type = node.getClass();
            if (type.isArray()) {
                if (!type.getComponentType().isPrimitive()) {
                    Object[] elements = (Object[]) node;
                    for (int i = 0; i < elements.length; i++) {
                        pushIfPresent(pending, elements[i], reached.path() + "[" + i + "]");
                    }
                }
                continue;
            }
            if (node instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    pushIfPresent(pending, e.getKey(), reached.path() + "{key}");
                    pushIfPresent(pending, e.getValue(), reached.path() + "{" + e.getKey() + "}");
                }
            } else if (node instanceof Collection<?> collection) {
                int i = 0;
                for (Object element : collection) {
                    pushIfPresent(pending, element, reached.path() + "[" + i++ + "]");
                }
            } else if (node instanceof Optional<?> optional) {
                optional.ifPresent(value -> pending.push(new Reached(value, reached.path() + ".get()")));
            }
            for (Class<?> declaring = type;
                    declaring != null && !isJdkClass(declaring);
                    declaring = declaring.getSuperclass()) {
                for (Field field : declaring.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || field.getType().isPrimitive()) {
                        continue;
                    }
                    assertTrue(field.trySetAccessible(), "the retention walk can read " + field);
                    pushIfPresent(pending, field.get(node), reached.path() + "." + field.getName());
                }
            }
        }
        return found;
    }

    /** An object the retention walk reached, with the path that reached it. */
    private record Reached(Object value, String path) {}

    private static void pushIfPresent(Deque<Reached> pending, @Nullable Object value, String path) {
        if (value != null) {
            pending.push(new Reached(value, path));
        }
    }

    private static boolean isForbidden(Object node) {
        for (Class<?> forbidden : FORBIDDEN_RETAINED_TYPES) {
            if (forbidden.isInstance(node)) {
                return true;
            }
        }
        return node instanceof Type && !(node instanceof Class<?>);
    }

    private static boolean isJdkClass(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader == null || loader == ClassLoader.getPlatformClassLoader();
    }

    // ---------------------------------------------------------------------------------------------
    // @ApiDocs publishes only its own application's document
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("only the application carrying @ApiDocs is stored and served; others reach the next mount")
    void onlyTheDocumentedApplicationIsPublished(Vertx vertx) throws Exception {
        // Given: the shared fixture, the shared configuration, and a manual JAX-RS mount
        DocsTestComponents.ManualMountComponent component =
                DaggerDocsTestComponents_ManualMountComponent.factory().create(DocsConfigs.shared());
        Deployment deployment = deploy(vertx, component);
        try {
            // When: the documented, the undocumented, and the manual mount's document URLs are requested
            HttpResponse<Buffer> publicResponse =
                    send(HttpMethod.GET, deployment.port(), documentPath(PublicApi.NAME, JSON_FORM));
            HttpResponse<Buffer> mgmtResponse =
                    send(HttpMethod.GET, deployment.port(), documentPath(MgmtApi.NAME, JSON_FORM));
            HttpResponse<Buffer> manualResponse =
                    send(HttpMethod.GET, deployment.port(), documentPath(ManualMountModule.NAME, JSON_FORM));

            // Then: the undocumented application and the manual mount were deployed
            List<String> mountPaths = component.mountCustomizer().applied().stream()
                    .map(MountMeta::mountPath)
                    .toList();
            assertTrue(
                    mountPaths.containsAll(List.of(MgmtApi.MOUNT_PATH, ManualMountModule.MOUNT_PATH)),
                    "the undocumented application and the manual mount are deployed, got " + mountPaths);

            // Then: only the documented application's document is stored and served
            Set<String> storedNames = component.documentStore().names();
            List<String> storedLines = infoLinesContaining(docsAppender, STORED_KEYWORD);
            assertAll(
                    "only the documented application is published",
                    () -> assertEquals(
                            Set.of(STORED_APPLICATION), storedNames, "the store holds exactly the public entry"),
                    () -> assertAnsweredByDocs(publicResponse, "GET the public document"),
                    () -> assertTrue(
                            answeredByLastMarker(mgmtResponse),
                            "the undocumented application's document URL reaches the marker mount"),
                    () -> assertTrue(
                            answeredByLastMarker(manualResponse),
                            "the manual mount's document URL reaches the marker mount"),
                    () -> assertEquals(
                            EXPECTED_STORED_LINES, storedLines.size(), "stored lines logged: " + storedLines),
                    () -> assertTrue(
                            storedLines.stream()
                                    .allMatch(line -> line.contains(STORED_KEYWORD)
                                            && STORED_APPLICATION_NAMED
                                                    .matcher(line)
                                                    .find()
                                            && line.contains(STORED_MOUNT_PATH)
                                            && line.contains(GENERATED_SOURCE)),
                            "the stored line names the application, its mount path, and the source " + GENERATED_SOURCE
                                    + ": " + storedLines));
        } finally {
            undeploy(vertx, deployment);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The installed module silences the not-installed notice whatever its configuration
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an installed documentation module silences the not-installed notice, even when disabled")
    void installedModuleSilencesTheNotInstalledNotice(Vertx vertx) throws Exception {
        // Given: the documented declaration built three ways
        DocsTestComponents.SharedComponent enabled =
                DaggerDocsTestComponents_SharedComponent.factory().create(DocsConfigs.shared());
        DocsTestComponents.SharedComponent disabled = DaggerDocsTestComponents_SharedComponent.factory()
                .create(DocsConfigs.withApidocsEnabled(DocsConfigs.shared(), false));
        Provisions withoutModule = withoutDocsModule(DocsConfigs.shared());

        // When: each is deployed in turn, its notices captured on their own
        List<String> enabledNotices = noticesOnDeployment(vertx, enabled);
        List<String> disabledNotices = noticesOnDeployment(vertx, disabled);
        int disabledStoreSize = disabled.documentStore().names().size();
        List<String> withoutModuleNotices = noticesOnDeployment(vertx, withoutModule);

        // Then: only the component without the module logs the notice, once, for the documented application
        assertAll(
                "the not-installed notice follows the module's presence, not its configuration",
                () -> assertEquals(
                        NOTICES_WITHOUT_MODULE,
                        withoutModuleNotices.size(),
                        "(iii) without the module, one notice: " + withoutModuleNotices),
                () -> assertTrue(
                        withoutModuleNotices.stream().allMatch(line -> line.contains(NOTICE_APPLICATION)),
                        "(iii) the notice names the documented application: " + withoutModuleNotices),
                () -> assertTrue(
                        withoutModuleNotices.stream().noneMatch(line -> line.contains(MgmtApi.NAME)),
                        "(iii) the undocumented application is never named: " + withoutModuleNotices),
                () -> assertEquals(
                        NOTICES_WITH_MODULE,
                        enabledNotices.size(),
                        "(i) the module installed and enabled, no notice: " + enabledNotices),
                () -> assertEquals(
                        NOTICES_WITH_MODULE,
                        disabledNotices.size(),
                        "(ii) the module installed with apidocs.enabled false, no notice: " + disabledNotices),
                () -> assertEquals(0, disabledStoreSize, "(ii) publishes nothing"));
    }

    /**
     * Deploys a composition and undeploys it, returning the not-installed notices its deployment
     * logged and no earlier ones.
     */
    private List<String> noticesOnDeployment(Vertx vertx, Provisions component) throws Exception {
        synchronized (viewAppender) {
            viewAppender.list.clear();
        }
        undeploy(vertx, deploy(vertx, component));
        return infoLinesContaining(viewAppender, NOT_INSTALLED_NOTICE);
    }

    /** Returns the formatted {@code INFO} messages an appender captured that contain {@code fragment}. */
    private static List<String> infoLinesContaining(ListAppender<ILoggingEvent> appender, String fragment) {
        synchronized (appender) {
            return appender.list.stream()
                    .filter(event -> event.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains(fragment))
                    .toList();
        }
    }

    /**
     * Returns a pattern matching {@code word} when neither a word character nor a {@code /} touches
     * it, so a path that contains the word does not match.
     */
    private static Pattern standingAlone(String word) {
        return Pattern.compile("(?<![/\\w])" + Pattern.quote(word) + "(?![/\\w])");
    }

    // ---------------------------------------------------------------------------------------------
    // Shared deployment and request helpers
    // ---------------------------------------------------------------------------------------------

    /** A completed deployment of one component's {@code HttpVerticle}, with its bound port. */
    record Deployment(String id, int port) {}

    /**
     * Deploys one instance of the component's {@code HttpVerticle}; the returned future fails when
     * startup fails, so a caller can capture a refused deployment.
     */
    private static Future<Deployment> deployment(Vertx vertx, Provisions component) {
        return vertx.deployVerticle(component.httpVerticle()).map(id -> {
            Object port = vertx.sharedData().getLocalMap(LOCAL_MAP).get(PORT_KEY);
            return new Deployment(id, (Integer) port);
        });
    }

    /** Deploys one instance of the component's {@code HttpVerticle} and waits for it to listen. */
    private static Deployment deploy(Vertx vertx, Provisions component) throws Exception {
        return Futures.await(deployment(vertx, component), Duration.ofSeconds(15));
    }

    /** Undeploys a deployment and clears the local map its port was published in. */
    private static void undeploy(Vertx vertx, Deployment deployment) throws Exception {
        try {
            Futures.await(vertx.undeploy(deployment.id()), Duration.ofSeconds(15));
        } finally {
            vertx.sharedData().getLocalMap(LOCAL_MAP).clear();
        }
    }

    /** Sends one request without a body to the loopback server and waits for the whole response. */
    private HttpResponse<Buffer> send(HttpMethod method, int port, String path) throws Exception {
        return Futures.await(client.request(method, port, LOOPBACK, path).send(), Duration.ofSeconds(15));
    }

    /** Returns the URL path of one form of a document under the default prefix. */
    private static String documentPath(String documentName, String form) {
        return DocsConfigs.DEFAULT_APIDOCS_PATH + "/" + documentName + "/" + form;
    }

    /** Whether the fixture's last marker mount answered the response. */
    private static boolean answeredByLastMarker(HttpResponse<Buffer> response) {
        return MarkerRouterMount.LAST.equals(response.getHeader(MarkerRouterMount.HEADER));
    }

    /** Asserts that the documentation mount answered: {@code 200} and no marker header. */
    private static void assertAnsweredByDocs(HttpResponse<Buffer> response, String label) {
        assertEquals(200, response.statusCode(), label + ": status");
        assertNull(
                response.getHeader(MarkerRouterMount.HEADER),
                label + ": answered by the documentation mount, not a marker");
    }
}
