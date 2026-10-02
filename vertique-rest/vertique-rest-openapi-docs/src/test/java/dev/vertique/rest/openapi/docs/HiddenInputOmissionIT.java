// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.rest.openapi.docs.HiddenInputTestComponents.FixedAccountsComponent;
import dev.vertique.rest.openapi.docs.HiddenInputTestComponents.HiddenFieldAccountsComponent;
import dev.vertique.rest.openapi.docs.HiddenInputTestComponents.ProbeComponent;
import dev.vertique.rest.openapi.docs.HiddenInputTestComponents.ProtectedFixedAccountsComponent;
import dev.vertique.rest.openapi.docs.HiddenInputTestComponents.ProtectedProbeComponent;
import dev.vertique.rest.openapi.docs.HiddenInputTestComponents.Served;
import dev.vertique.rest.openapi.docs.ProtectedRenderingSink.InventoryEntry;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AccountBeanZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AccountCtorZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AccountFixedZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AccountHiddenFieldZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AuditTrailZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.LedgerNoteZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.TierZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden.AccountsApplication;
import dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden.HiddenProbeApi;
import dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden.HiddenProbeResource;
import dev.vertique.rest.openapi.docs.fixture.input.GeneratedBodies;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments.Outcome;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * Deploys declared applications under {@code web-validation} with the canonical schema source over a
 * real, loopback-bound {@code HttpVerticle} and checks what their documents say about hidden inputs:
 * an input the binding inventory flags hidden, including every field of a hidden composite parameter
 * and a hidden request body, appears in no public or protected rendering and still binds; and a
 * request body whose description reaches a member or type carrying a hiding marker fails the
 * deployment with a message naming the mount, the operation, the member or type, its marker, and the
 * one fix that works at that position, while the same body binds unchanged once nothing publishes it.
 *
 * <p>Each application is deployed twice: by a component that serves its public document, and by a
 * component that registers its protected twin and renders the protected document without serving it,
 * keeping the rendered bytes and the binding inventory. Expected values are fixed literals; the
 * positive controls read the inventory the rendering component kept and the input-direction
 * generator's own description of a body type. One Vert.x instance and one client serve the class;
 * every deployment is undeployed, clearing the published port, before the assertions run.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class HiddenInputOmissionIT {

    private static final String HOST = "127.0.0.1";

    /** The name of the member that records a document's validation facts. */
    private static final String VALIDATION_MEMBER = "x-vertique-validation";

    /** The root validation member that counts the inputs left out of a protected document. */
    private static final String HIDDEN_INPUTS = "hiddenInputs";

    /** The root validation member of every public document: the pattern dialect only. */
    private static final JsonObject PUBLIC_ROOT = new JsonObject().put("patternDialect", "java.util.regex");

    /** The root validation member that records reserved names removed from a protected document. */
    private static final String RESERVED_NAMES_REFUSED = "reservedNamesRefused";

    /** The marker {@code io.swagger.v3.oas.annotations.Hidden}, as a refusal names it. */
    private static final String HIDDEN_MARKER = "@Hidden";

    /** The marker {@code @Schema(hidden = true)}, as a refusal names it. */
    private static final String SCHEMA_HIDDEN_MARKER = "@Schema(hidden = true)";

    /** The fix for a member whose own field or getter can carry the generator's hiding marker. */
    private static final String FIELD_OR_GETTER_FIX =
            "declare @Schema(hidden = true) on the property's own field or getter";

    /** The fix for a type carrying a hiding marker, which no marker on the type itself can hide. */
    private static final String TYPE_FIX = "does not hide a type";

    /** The fix for a member the generator cannot leave out. */
    private static final String REMOVE_FIX = "remove it from the published type, or hide the operation";

    /** The fix for a marker the generator reports at a position other than the property's own. */
    private static final String MISPLACED_FIX =
            "declare it directly on the property's own field or getter, not through a bundle or mix-in";

    /** The status of a {@code void} resource method that completed. */
    private static final int NO_CONTENT = 204;

    private static Vertx vertx;
    private static WebClient client;

    @BeforeAll
    static void startClient(Vertx sharedVertx) {
        vertx = sharedVertx;
        client = WebClient.create(vertx);
    }

    @AfterAll
    static void closeClient() {
        client.close();
    }

    // --- Hidden inputs ---

    @Test
    @DisplayName(
            "Inputs the inventory flags hidden, hidden composite fields and a hidden body included, appear in no public or protected rendering and still bind")
    void hiddenInputsAppearNowhereAndStillBind() throws Exception {
        // Given: one application whose read operation has hidden inputs of every kind beside the
        // visible 'page', and whose write operation has a hidden body; one component renders its
        // protected twin and keeps the inventory, the other serves its public document.
        ProtectedProbeComponent renderingComponent = DaggerHiddenInputTestComponents_ProtectedProbeComponent.factory()
                .create(webValidationConfig());
        ProbeComponent servedComponent = DaggerHiddenInputTestComponents_ProbeComponent.factory()
                .create(webValidationConfig(HiddenProbeApi.NAME));

        // When: the protected rendering and the inventory are read ...
        DisclosureDocuments.Rendering protectedRendering = renderProtected(renderingComponent, HiddenProbeApi.NAME);
        ProtectedRenderingSink sink = renderingComponent.protectedRendering();
        List<InventoryEntry> listInventory = sink.inventory(HiddenProbeApi.NAME, HiddenProbeResource.LIST_OPERATION_ID);
        List<InventoryEntry> createInventory =
                sink.inventory(HiddenProbeApi.NAME, HiddenProbeResource.CREATE_OPERATION_ID);

        // ... then the public JSON and YAML, and the three requests.
        DisclosureDocuments.Rendering publicRendering;
        Exchange invalidDebug;
        Exchange allBound;
        Exchange hiddenBody;
        String route = HiddenProbeApi.PATH + HiddenProbeResource.ROUTE;
        Outcome outcome = StartupDeployments.deploy(vertx, servedComponent::httpVerticle);
        try {
            assertDeployed("the served public document of 'hidden'", outcome);
            int port = outcome.port();
            publicRendering = fetchPublic(port, HiddenProbeApi.NAME);
            invalidDebug = exchange(client.get(port, HOST, route + "?" + HiddenProbeResource.DEBUG + "=abc")
                    .send());
            allBound = exchange(client.get(
                            port,
                            HOST,
                            route + "?" + HiddenProbeResource.DEBUG + "=12&" + HiddenProbeResource.INTERNAL + "=v&"
                                    + HiddenProbeResource.CURSOR + "=c1&" + HiddenProbeResource.WINDOW + "=w1&"
                                    + HiddenProbeResource.AUDIT + "=a1")
                    .putHeader(HiddenProbeResource.TRACE, "t1")
                    .putHeader(HiddenProbeResource.PAGE_HEADER, "p1")
                    .send());
            hiddenBody = exchange(client.post(port, HOST, route).sendJsonObject(new JsonObject().put("text", "ok")));
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }

        // Then: the inventory flags all eight bindings hidden and 'page' visible (positive control).
        Executable inventoryFlags = () -> assertAll(
                "the binding inventory: list " + listInventory + "; create " + createInventory,
                flagged(listInventory, "QUERY", HiddenProbeResource.DEBUG, true),
                flagged(listInventory, "QUERY", HiddenProbeResource.PAGE, false),
                flagged(listInventory, "HEADER", HiddenProbeResource.TRACE, true),
                flagged(listInventory, "QUERY", HiddenProbeResource.INTERNAL, true),
                flagged(listInventory, "QUERY", HiddenProbeResource.CURSOR, true),
                flagged(listInventory, "HEADER", HiddenProbeResource.PAGE_HEADER, true),
                flagged(listInventory, "QUERY", HiddenProbeResource.WINDOW, true),
                flagged(listInventory, "QUERY", HiddenProbeResource.AUDIT, true),
                () -> {
                    List<InventoryEntry> bodies = createInventory.stream()
                            .filter(entry -> "BODY".equals(entry.origin()))
                            .toList();
                    assertEquals(1, bodies.size(), "the create operation binds exactly one body");
                    assertTrue(bodies.get(0).hidden(), "the body binding is flagged hidden");
                });

        // Then: no rendering names a hidden input, describes the hidden body, or drops 'page'.
        List<Executable> absences = new ArrayList<>();
        absences.addAll(hiddenInputAbsences("public", publicRendering));
        absences.addAll(hiddenInputAbsences("protected", protectedRendering));
        Executable nothingDisclosed = () -> assertAll("no rendering discloses a hidden input", absences.stream());

        // Then: only the protected root counts the hidden inputs.
        JsonObject protectedRoot = protectedRendering.rootValidation();
        JsonObject publicRoot = publicRendering.rootValidation();
        Executable rootMembers = () -> assertAll(
                "the root validation members: protected " + protectedRoot + "; public " + publicRoot,
                () -> assertNotNull(protectedRoot, "the protected root carries " + VALIDATION_MEMBER),
                () -> assertEquals(
                        Boolean.TRUE,
                        protectedRoot == null ? null : protectedRoot.getValue(HIDDEN_INPUTS),
                        "the protected root has " + HIDDEN_INPUTS + ": true"),
                () -> assertEquals(
                        PUBLIC_ROOT,
                        publicRoot,
                        "the public JSON root records the pattern dialect only, although inputs were left out"),
                () -> assertEquals(
                        PUBLIC_ROOT,
                        yamlRootValidation(publicRendering),
                        "the public YAML root records the pattern dialect only, although inputs were left out"));

        // Then: every hidden input still binds and is still validated.
        Executable requests = () -> assertAll(
                "the requests",
                () -> assertEquals(400, invalidDebug.status(), () -> "debugZx=abc: " + invalidDebug),
                () -> assertEquals(200, allBound.status(), () -> "every hidden value: " + allBound),
                () -> assertEquals(
                        "12,t1,v,c1,p1,w1,a1", allBound.body(), "the hidden values are bound and echoed in order"),
                () -> assertEquals(NO_CONTENT, hiddenBody.status(), () -> "the hidden body: " + hiddenBody));
        assertAll("hidden inputs", inventoryFlags, nothingDisclosed, rootMembers, requests);
    }

    /**
     * Returns the absences one rendering must satisfy: no hidden name in either form (header names
     * compared case-insensitively), no request body for the create operation in either tree, no
     * component for it, and the visible {@code page} still listed in either tree.
     */
    private static List<Executable> hiddenInputAbsences(String label, DisclosureDocuments.Rendering rendering) {
        List<Executable> checks = new ArrayList<>();
        String json = rendering.jsonText();
        String yaml = rendering.yamlText();
        for (String form : List.of("JSON", "YAML")) {
            String text = form.equals("JSON") ? json : yaml;
            String where = label + " " + form;
            for (String name : List.of(
                    HiddenProbeResource.DEBUG,
                    HiddenProbeResource.INTERNAL,
                    HiddenProbeResource.CURSOR,
                    HiddenProbeResource.WINDOW,
                    HiddenProbeResource.AUDIT,
                    HiddenProbeResource.CREATE_OPERATION_ID + ".request")) {
                checks.add(() -> assertFalse(text.contains(name), () -> where + " contains '" + name + "'"));
            }
            String folded = text.toLowerCase(Locale.ROOT);
            for (String header : List.of(HiddenProbeResource.TRACE, HiddenProbeResource.PAGE_HEADER)) {
                String lower = header.toLowerCase(Locale.ROOT);
                checks.add(() -> assertFalse(
                        folded.contains(lower), () -> where + " contains '" + lower + "' in some letter case"));
            }
        }
        String post = "/paths/" + pointerSegment(HiddenProbeResource.ROUTE) + "/post";
        String get = "/paths/" + pointerSegment(HiddenProbeResource.ROUTE) + "/get";
        for (String form : List.of("JSON", "YAML")) {
            String where = label + " " + form;
            checks.add(() -> {
                JsonNode tree = form.equals("JSON") ? rendering.jsonTree() : rendering.yamlTree();
                JsonNode operation = tree.at(post);
                assertFalse(operation.isMissingNode(), () -> where + " publishes POST " + HiddenProbeResource.ROUTE);
                assertFalse(
                        operation.has("requestBody"),
                        () -> where + " describes no request body for " + HiddenProbeResource.CREATE_OPERATION_ID);
                List<String> names = new ArrayList<>();
                tree.at(get + "/parameters")
                        .forEach(parameter -> names.add(parameter.path("name").asText()));
                assertTrue(
                        names.contains(HiddenProbeResource.PAGE),
                        () -> where + " still publishes '" + HiddenProbeResource.PAGE + "'; parameters: " + names);
            });
        }
        return checks;
    }

    private static Executable flagged(List<InventoryEntry> inventory, String location, String name, boolean hidden) {
        return () -> {
            List<InventoryEntry> matches = inventory.stream()
                    .filter(entry -> location.equals(entry.location()) && name.equalsIgnoreCase(entry.name()))
                    .toList();
            assertEquals(1, matches.size(), () -> "exactly one " + location + " binding '" + name + "'");
            assertEquals(
                    hidden,
                    matches.get(0).hidden(),
                    () -> "'" + name + "' is flagged " + (hidden ? "hidden" : "visible"));
        };
    }

    // --- Hidden body members ---

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName(
            "A published request body describing a member or type that carries a hiding marker fails the deployment naming the fix for its position, and binds once unpublished")
    void hiddenBodyMemberFailsPublication() throws Exception {
        // Given: compositions of the application 'accounts' whose one operation takes a different body.
        String mount = AccountsApplication.PATH;
        String operationId = AccountsApplication.OPERATION_ID;
        List<RefusalCase> refusals = List.of(
                new RefusalCase(
                        "(a) a member carrying @Hidden only",
                        () -> DaggerHiddenInputTestComponents_HiddenFieldAccountsComponent.factory()
                                .create(accountsConfig()),
                        List.of(
                                AccountHiddenFieldZx.class.getName(),
                                "backdoorZx",
                                HIDDEN_MARKER,
                                FIELD_OR_GETTER_FIX)),
                new RefusalCase(
                        "(b) a member whose type carries @Hidden",
                        () -> DaggerHiddenInputTestComponents_HiddenTypeAccountsComponent.factory()
                                .create(accountsConfig()),
                        List.of(AuditTrailZx.class.getName(), HIDDEN_MARKER, TYPE_FIX)),
                new RefusalCase(
                        "(e) both kinds of member, the first reported entry named",
                        () -> DaggerHiddenInputTestComponents_FormAccountsComponent.factory()
                                .create(accountsConfig()),
                        List.of("backdoorZx")),
                new RefusalCase(
                        "(f) a JavaBean getter carrying @Schema(hidden = true)",
                        () -> DaggerHiddenInputTestComponents_BeanAccountsComponent.factory()
                                .create(accountsConfig()),
                        List.of(AccountBeanZx.class.getName(), "getPinZx", SCHEMA_HIDDEN_MARKER, REMOVE_FIX)),
                new RefusalCase(
                        "(g) an enum constant carrying @Schema(hidden = true)",
                        () -> DaggerHiddenInputTestComponents_TierAccountsComponent.factory()
                                .create(accountsConfig()),
                        List.of(TierZx.class.getName(), "INTERNAL_ZX", SCHEMA_HIDDEN_MARKER, REMOVE_FIX)),
                new RefusalCase(
                        "(h) a class carrying @Schema(hidden = true)",
                        () -> DaggerHiddenInputTestComponents_LedgerAccountsComponent.factory()
                                .create(accountsConfig()),
                        List.of(LedgerNoteZx.class.getName(), SCHEMA_HIDDEN_MARKER, TYPE_FIX)),
                new RefusalCase(
                        "(i) a creator parameter carrying @Schema(hidden = true)",
                        () -> DaggerHiddenInputTestComponents_CtorAccountsComponent.factory()
                                .create(accountsConfig()),
                        List.of(AccountCtorZx.class.getName(), "<init>#0", SCHEMA_HIDDEN_MARKER, MISPLACED_FIX)));

        // When: each refused composition is deployed, its outcome kept, and anything deployed removed.
        List<Outcome> refusalOutcomes = new ArrayList<>();
        for (RefusalCase refusal : refusals) {
            Served component = refusal.component().get();
            Outcome outcome = deployAndRelease(component::httpVerticle);
            refusalOutcomes.add(outcome);
            // Evidence: the refusal message alone, which by design quotes no schema value.
            Throwable failure = outcome.failure();
            System.out.println("FAILURE " + refusal.label() + ": " + (failure == null ? null : failure.getMessage()));
        }

        // When: (c) is served and posted to, and its protected twin rendered.
        FixedAccountsComponent fixed =
                DaggerHiddenInputTestComponents_FixedAccountsComponent.factory().create(accountsConfig());
        ProtectedFixedAccountsComponent protectedFixed =
                DaggerHiddenInputTestComponents_ProtectedFixedAccountsComponent.factory()
                        .create(webValidationConfig());
        String route = AccountsApplication.PATH + AccountsApplication.ROUTE;
        JsonObject backdoorBody = new JsonObject().put("backdoorZx", "x");
        DisclosureDocuments.Rendering fixedPublic;
        Exchange fixedPost;
        Outcome fixedOutcome = StartupDeployments.deploy(vertx, fixed::httpVerticle);
        try {
            assertDeployed("(c) the fixed member", fixedOutcome);
            fixedPublic = fetchPublic(fixedOutcome.port(), AccountsApplication.NAME);
            fixedPost = exchange(client.post(fixedOutcome.port(), HOST, route).sendJsonObject(backdoorBody));
        } finally {
            StartupDeployments.undeploy(vertx, fixedOutcome);
        }
        DisclosureDocuments.Rendering fixedProtected = renderProtected(protectedFixed, AccountsApplication.NAME);

        // When: (d), composition (a) without an enabled document, is deployed and posted to.
        JsonObject undocumented = DocsConfigs.withDocumentEnabled(accountsConfig(), AccountsApplication.NAME, false);
        HiddenFieldAccountsComponent unpublished =
                DaggerHiddenInputTestComponents_HiddenFieldAccountsComponent.factory()
                        .create(undocumented);
        Exchange unpublishedPost;
        Outcome unpublishedOutcome = StartupDeployments.deploy(vertx, unpublished::httpVerticle);
        try {
            assertDeployed("(d) the hidden member without an enabled document", unpublishedOutcome);
            unpublishedPost =
                    exchange(client.post(unpublishedOutcome.port(), HOST, route).sendJsonObject(backdoorBody));
        } finally {
            StartupDeployments.undeploy(vertx, unpublishedOutcome);
        }

        // Then: each refused composition fails before publishing a port, naming what it must.
        List<Executable> refusalChecks = new ArrayList<>();
        for (int index = 0; index < refusals.size(); index++) {
            RefusalCase refusal = refusals.get(index);
            Outcome deployed = refusalOutcomes.get(index);
            refusalChecks.add(() -> assertRefused(refusal, deployed, mount, operationId));
        }
        Executable refused = () -> assertAll("the refused compositions", refusalChecks.stream());

        // Then: (c) deploys, nothing renders its reserved member, and it is still refused at the gate.
        GeneratedBodies.GeneratedBody described = GeneratedBodies.describe(AccountFixedZx.class);
        JsonObject fixedRoot = fixedProtected.rootValidation();
        Executable fixedMember = () -> assertAll(
                "(c) the fixed member",
                () -> assertTrue(
                        described.schema().encode().contains("backdoorZx"),
                        "positive control: the generator's description of the body names backdoorZx"),
                () -> assertFalse(
                        described.manifestIsEmpty(), "positive control: the generator's manifest lists a pointer"),
                () -> assertFalse(fixedPublic.jsonText().contains("backdoorZx"), "public JSON names backdoorZx"),
                () -> assertFalse(fixedPublic.yamlText().contains("backdoorZx"), "public YAML names backdoorZx"),
                () -> assertFalse(fixedProtected.jsonText().contains("backdoorZx"), "protected JSON names backdoorZx"),
                () -> assertFalse(fixedProtected.yamlText().contains("backdoorZx"), "protected YAML names backdoorZx"),
                () -> assertEquals(
                        Boolean.TRUE,
                        fixedRoot == null ? null : fixedRoot.getValue(RESERVED_NAMES_REFUSED),
                        () -> "the protected root has " + RESERVED_NAMES_REFUSED + ": true; root: " + fixedRoot),
                () -> assertEquals(400, fixedPost.status(), () -> "the post naming backdoorZx: " + fixedPost));

        // Then: (d) deploys and the hidden member binds as without the documentation module.
        Executable unpublishedMember = () -> assertEquals(
                NO_CONTENT,
                unpublishedPost.status(),
                () -> "(d) the post naming backdoorZx without an enabled document: " + unpublishedPost);
        assertAll("hidden body members", refused, fixedMember, unpublishedMember);
    }

    private static void assertRefused(RefusalCase refusal, Outcome outcome, String mount, String operationId) {
        Throwable failure = outcome.failure();
        String message = failure == null ? "" : String.valueOf(failure.getMessage());
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertNotNull(failure, "the deployment fails"));
        checks.add(() -> assertNull(outcome.port(), "no port is published"));
        for (String fragment : refusal.fragments()) {
            checks.add(() -> assertTrue(message.contains(fragment), "the message names: " + fragment));
        }
        String mountFragment = "mount '" + mount + "/*'";
        checks.add(() -> assertTrue(message.contains(mountFragment), "the message names " + mountFragment));
        checks.add(() -> assertTrue(message.contains(operationId), "the message names the operation " + operationId));
        checks.add(() -> assertFalse(message.contains("{"), "the message holds no '{'"));
        checks.add(() -> assertFalse(message.contains("\"type\""), "the message holds no \"type\""));
        assertAll(refusal.label() + "; message: " + message, checks.stream());
    }

    /**
     * One composition expected to fail its deployment.
     *
     * @param label     names the composition
     * @param component builds a fresh component of the composition
     * @param fragments what the failure message must contain beside the mount and the operation
     */
    private record RefusalCase(String label, Supplier<Served> component, List<String> fragments) {}

    // --- Shared helpers ---

    /**
     * Returns the loopback configuration with the {@code web-validation} strategy and an {@code info}
     * for each named document. A rendering component takes it without names: its sink renders with a
     * fixed {@code info} and reads no {@code apidocs} configuration.
     */
    private static JsonObject webValidationConfig(String... documentNames) {
        JsonObject config = DocsConfigs.loopback();
        config.getJsonObject("jaxrs").put("validationStrategy", "web-validation");
        for (String name : documentNames) {
            DocsConfigs.withDocumentInfo(config, name, name, "1");
        }
        return config;
    }

    private static JsonObject accountsConfig() {
        return webValidationConfig(AccountsApplication.NAME);
    }

    /**
     * Deploys a rendering component, reads the protected rendering of one application, and
     * undeploys it; the component's sink keeps the binding inventory for later reads.
     */
    private static DisclosureDocuments.Rendering renderProtected(
            HiddenInputTestComponents.Renders component, String application) throws Exception {
        Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
        try {
            assertDeployed("the protected rendering of '" + application + "'", outcome);
            ProtectedRenderingSink sink = component.protectedRendering();
            Optional<String> failure = sink.failure(application);
            assertTrue(failure.isEmpty(), () -> "the protected document of '" + application + "' failed: " + failure);
            return new DisclosureDocuments.Rendering(sink.json(application), sink.yaml(application));
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    /** Fetches both forms of an application's public document; each must answer {@code 200}. */
    private static DisclosureDocuments.Rendering fetchPublic(int port, String application) throws Exception {
        String base = DocsConfigs.DEFAULT_APIDOCS_PATH + "/" + application + "/";
        Exchange json = exchange(client.get(port, HOST, base + "openapi.json").send());
        Exchange yaml = exchange(client.get(port, HOST, base + "openapi.yaml").send());
        assertEquals(200, json.status(), () -> "the public JSON of '" + application + "': " + json);
        assertEquals(200, yaml.status(), () -> "the public YAML of '" + application + "': " + yaml);
        return new DisclosureDocuments.Rendering(json.bytes(), yaml.bytes());
    }

    /** Deploys a verticle, keeps the outcome, and undeploys whatever deployed. */
    private static Outcome deployAndRelease(Supplier<Verticle> verticles) throws Exception {
        Outcome outcome = StartupDeployments.deploy(vertx, verticles);
        StartupDeployments.undeploy(vertx, outcome);
        return outcome;
    }

    private static void assertDeployed(String what, Outcome outcome) {
        assertNull(outcome.failure(), () -> what + ": the deployment failed: " + outcome.failure());
        assertNotNull(outcome.port(), () -> what + ": the deployment published no port");
    }

    /** Returns the root validation member of the YAML form, or {@code null} when it is absent. */
    private static JsonObject yamlRootValidation(DisclosureDocuments.Rendering rendering) {
        JsonNode root = rendering.yamlTree().get(VALIDATION_MEMBER);
        return root == null ? null : new JsonObject(root.toString());
    }

    private static String pointerSegment(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private static Exchange exchange(Future<HttpResponse<Buffer>> request) throws Exception {
        HttpResponse<Buffer> response = Futures.await(request, Duration.ofSeconds(15));
        Buffer body = response.body();
        return new Exchange(response.statusCode(), body == null ? new byte[0] : body.getBytes());
    }

    /**
     * One answered request.
     *
     * @param status the status code
     * @param bytes  the body bytes, empty when there was none
     */
    private record Exchange(int status, byte[] bytes) {

        String body() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return status + " " + body();
        }
    }
}
