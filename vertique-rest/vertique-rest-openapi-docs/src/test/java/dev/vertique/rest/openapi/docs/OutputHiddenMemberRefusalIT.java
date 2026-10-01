// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.openapi.docs.ResponseTestComponents.Served;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.AuditZx;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.NoteZx;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.PinReceiptZx;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.ReceiptZx;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.TierZx;
import dev.vertique.rest.openapi.docs.fixture.responses.it.FixedApi;
import dev.vertique.rest.openapi.docs.fixture.responses.it.FixedReceiptResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.HiddenOpApi;
import dev.vertique.rest.openapi.docs.fixture.responses.it.HiddenOperationResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.LedgerApi;
import dev.vertique.rest.openapi.docs.fixture.responses.it.LedgerResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.NoteReceiptResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.NoteReceiptsApi;
import dev.vertique.rest.openapi.docs.fixture.responses.it.PinResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.PinsApi;
import dev.vertique.rest.openapi.docs.fixture.responses.it.ReceiptExplicitResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.ReceiptResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.ReceiptsApi;
import dev.vertique.rest.openapi.docs.fixture.responses.it.ReceiptsExplicitApi;
import dev.vertique.rest.openapi.docs.fixture.responses.it.TierResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.TiersApi;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupDeployments.Outcome;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Deploys documented applications whose published output types describe a member or type carrying
 * a hiding marker the output generator does not honor, and checks that an enabled document refuses
 * the deployment before a port is published, naming the entry, its marker, and the one fix its
 * position implies; while a member marked {@code @Schema(hidden = true)} on its own field publishes
 * without it, a composition without an enabled document deploys unchanged, and a hidden operation's
 * output type is never checked.
 *
 * <p>The fixture DTO names state their annotation layout: {@code ReceiptZx} marks a field
 * {@code @Hidden} only; {@code LedgerZx} reaches the type {@code AuditZx} annotated {@code @Hidden};
 * {@code FixedReceiptZx} marks the field with both markers; {@code PinReceiptZx} marks its setter
 * {@code @Schema(hidden = true)}; {@code TierReceiptZx} reaches the enum constant {@code
 * TierZx.INTERNAL_ZX} marked {@code @Schema(hidden = true)}; and {@code NoteReceiptZx} reaches the
 * type {@code NoteZx} annotated {@code @Schema(hidden = true)}.
 *
 * <p>Each case is its own composition and configuration, deployed through {@code deploy}. A
 * refusal message must start with the document's configuration path and the application's
 * subject, name the operation and the case's fragments, carry exactly the expected fix among the
 * four, and hold no schema text. A deployed case is observed while it runs; every successful
 * deployment is undeployed before the case's assertions run.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class OutputHiddenMemberRefusalIT {

    private static final String HOST = "127.0.0.1";

    /** The mount every fixture application is served under. */
    private static final String MOUNT = "/api/*";

    /** The fix for a hidden type ({@code member} absent). */
    static final String TYPE_FIX = "the output generator does not hide a type; declare @Schema(hidden = true) on the"
            + " field or getter of each member that references it, or hide the operation";

    /** The fix for a member the generator could leave out, marked {@code @Hidden} only. */
    static final String HIDDEN_FIX = "the output generator ignores @Hidden; declare @Schema(hidden = true) on the"
            + " property's own field or getter";

    /** The fix for a member the generator could leave out, marked {@code @Schema(hidden = true)} elsewhere. */
    static final String MISPLACED_FIX = "the output generator ignores @Schema(hidden = true) where it is declared;"
            + " declare it directly on the property's own field or getter, not through a bundle or mix-in";

    /** The fix for a member the generator cannot leave out. */
    static final String CANNOT_FIX = "the output generator cannot leave this member out where the document describes"
            + " it (an enum constant or @JsonUnwrapped content); remove it from the published type, or hide the"
            + " operation";

    /** Every fix wording; a refusal carries exactly one. */
    private static final List<String> FIXES = List.of(TYPE_FIX, HIDDEN_FIX, MISPLACED_FIX, CANNOT_FIX);

    /** The {@code @Hidden} marker. */
    private static final String HIDDEN_MARKER = "@Hidden";

    /** The {@code @Schema(hidden = true)} marker. */
    private static final String SCHEMA_HIDDEN_MARKER = "@Schema(hidden = true)";

    /** The member of {@code ReceiptZx} and {@code FixedReceiptZx} carrying a hiding marker. */
    private static final String INTERNAL_MEMBER = "internalZx";

    /** Fragments only schema text would carry; no refusal message may contain one. */
    private static final List<String> SCHEMA_TEXT = List.of("\"properties\"", "\"type\"", "{");

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

    /**
     * What a refused case's message must carry.
     *
     * @param operationId the operation the message names
     * @param fix         the one fix the message carries
     * @param fragments   what else the message names: the type, the member, the marker
     */
    record Refusal(String operationId, String fix, List<String> fragments) {}

    /** Observes a deployed case while it runs and returns the checks to assert once it is undeployed. */
    @FunctionalInterface
    interface WhileDeployed {

        /**
         * Observes the deployment.
         *
         * @param port the published port
         * @return the checks on what was observed
         * @throws Exception when a request fails
         */
        List<Executable> observe(int port) throws Exception;
    }

    /**
     * One deployment of the matrix.
     *
     * @param application   the application, which also names its document
     * @param declaringType the application's declaring interface
     * @param components    creates the case's component from its configuration
     * @param config        the case's configuration
     * @param refusal       what the refusal must carry, or {@code null} when the case deploys
     * @param started       what a deployed case observes, or {@code null} when it is refused
     */
    record Case(
            String application,
            Class<?> declaringType,
            Function<JsonObject, Served> components,
            JsonObject config,
            Refusal refusal,
            WhileDeployed started) {}

    static Stream<Arguments> cases() {
        return Stream.of(
                row(
                        "(a) inferred ReceiptZx, @Hidden-only field",
                        refused(
                                ReceiptsApi.NAME,
                                ReceiptsApi.class,
                                config -> DaggerResponseTestComponents_ReceiptsComponent.factory()
                                        .create(config),
                                new Refusal(
                                        ReceiptResource.OPERATION_ID,
                                        HIDDEN_FIX,
                                        List.of(ReceiptZx.class.getName(), INTERNAL_MEMBER, HIDDEN_MARKER)))),
                row(
                        "(b) declared ReceiptZx content, @Hidden-only field",
                        refused(
                                ReceiptsExplicitApi.NAME,
                                ReceiptsExplicitApi.class,
                                config -> DaggerResponseTestComponents_ReceiptsExplicitComponent.factory()
                                        .create(config),
                                new Refusal(
                                        ReceiptExplicitResource.OPERATION_ID,
                                        HIDDEN_FIX,
                                        List.of(ReceiptZx.class.getName(), INTERNAL_MEMBER, HIDDEN_MARKER)))),
                row(
                        "(c) LedgerZx reaching the @Hidden type AuditZx",
                        refused(
                                LedgerApi.NAME,
                                LedgerApi.class,
                                config -> DaggerResponseTestComponents_LedgerComponent.factory()
                                        .create(config),
                                new Refusal(
                                        LedgerResource.OPERATION_ID,
                                        TYPE_FIX,
                                        List.of(AuditZx.class.getName(), HIDDEN_MARKER)))),
                row(
                        "(d) FixedReceiptZx, both markers on the field",
                        new Case(
                                FixedApi.NAME,
                                FixedApi.class,
                                config -> DaggerResponseTestComponents_FixedComponent.factory()
                                        .create(config),
                                InputAssemblyIT.webValidationConfig(FixedApi.NAME),
                                null,
                                OutputHiddenMemberRefusalIT::observeFixed)),
                row(
                        "(e) inferred ReceiptZx, document disabled",
                        new Case(
                                ReceiptsApi.NAME,
                                ReceiptsApi.class,
                                config -> DaggerResponseTestComponents_ReceiptsComponent.factory()
                                        .create(config),
                                DocsConfigs.withDocumentEnabled(
                                        InputAssemblyIT.webValidationConfig(ReceiptsApi.NAME), ReceiptsApi.NAME, false),
                                null,
                                OutputHiddenMemberRefusalIT::observeUnpublished)),
                row(
                        "(f) hidden operation returning ReceiptZx beside a visible one",
                        new Case(
                                HiddenOpApi.NAME,
                                HiddenOpApi.class,
                                config -> DaggerResponseTestComponents_HiddenOpComponent.factory()
                                        .create(config),
                                InputAssemblyIT.webValidationConfig(HiddenOpApi.NAME),
                                null,
                                OutputHiddenMemberRefusalIT::observeHiddenOperation)),
                row(
                        "(g) PinReceiptZx, @Schema(hidden = true) on the setter",
                        refused(
                                PinsApi.NAME,
                                PinsApi.class,
                                config -> DaggerResponseTestComponents_PinsComponent.factory()
                                        .create(config),
                                new Refusal(
                                        PinResource.OPERATION_ID,
                                        MISPLACED_FIX,
                                        List.of(PinReceiptZx.class.getName(), "setPinZx", SCHEMA_HIDDEN_MARKER)))),
                row(
                        "(h) TierReceiptZx reaching the hidden enum constant TierZx.INTERNAL_ZX",
                        refused(
                                TiersApi.NAME,
                                TiersApi.class,
                                config -> DaggerResponseTestComponents_TiersComponent.factory()
                                        .create(config),
                                new Refusal(
                                        TierResource.OPERATION_ID,
                                        CANNOT_FIX,
                                        List.of(TierZx.class.getName(), "INTERNAL_ZX", SCHEMA_HIDDEN_MARKER)))),
                row(
                        "(i) NoteReceiptZx reaching the @Schema(hidden = true) type NoteZx",
                        refused(
                                NoteReceiptsApi.NAME,
                                NoteReceiptsApi.class,
                                config -> DaggerResponseTestComponents_NoteReceiptsComponent.factory()
                                        .create(config),
                                new Refusal(
                                        NoteReceiptResource.OPERATION_ID,
                                        TYPE_FIX,
                                        List.of(NoteZx.class.getName(), SCHEMA_HIDDEN_MARKER)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName(
            "A published output member or type carrying a hiding marker the generator does not honor fails an enabled document's deployment naming its fix; the fixed type, a disabled document, and a hidden operation start")
    void hiddenOutputMemberFailsPublication(Case row) throws Exception {
        // Given: the case's composition and configuration
        // When: it is deployed and, when it deploys and is expected to, observed while it runs
        Outcome outcome = deploy(row.components(), row.config());
        List<Executable> observed = new ArrayList<>();
        try {
            if (row.refusal() == null && outcome.deployed() && outcome.port() != null) {
                observed.addAll(row.started().observe(outcome.port()));
            }
        } finally {
            undeploy(outcome);
        }

        // Then
        if (row.refusal() != null) {
            assertRefused(row, outcome);
        } else {
            assertAll(
                    "the deployment",
                    () -> assertNull(outcome.failure(), () -> "the deployment succeeds: " + outcome.failure()),
                    () -> assertNotNull(outcome.port(), "a port is published"));
            assertAll("what the running deployment answered", observed);
        }
    }

    // --- Observations of deployed cases ---

    /**
     * (d): the document publishes the output component without the marked member, in no byte, and the
     * resource still answers.
     */
    private static List<Executable> observeFixed(int port) throws Exception {
        Exchange document = get(port, documentPath(FixedApi.NAME));
        Exchange answer = get(port, FixedApi.PATH + FixedReceiptResource.ROUTE);
        String component = FixedReceiptResource.OPERATION_ID + ".response";
        return List.of(
                () -> {
                    assertEquals(200, document.status(), () -> "the document is served: " + document);
                    JsonObject properties = properties(new JsonObject(document.text()), component);
                    assertAll(
                            "component '" + component + "': " + properties.fieldNames(),
                            () -> assertTrue(properties.containsKey("total"), "it publishes total"),
                            () -> assertFalse(
                                    properties.containsKey(INTERNAL_MEMBER), "it does not publish " + INTERNAL_MEMBER));
                },
                () -> assertFalse(
                        document.text().contains(INTERNAL_MEMBER),
                        () -> "no byte of the document contains " + INTERNAL_MEMBER),
                () -> assertEquals(200, answer.status(), () -> "GET answers 200: " + answer));
    }

    /** (e): without an enabled document the resource answers as without the documentation module. */
    private static List<Executable> observeUnpublished(int port) throws Exception {
        Exchange answer = get(port, ReceiptsApi.PATH + ReceiptResource.ROUTE);
        return List.of(() -> assertEquals(200, answer.status(), () -> "GET answers 200: " + answer));
    }

    /** (f): the document lists only the visible operation. */
    private static List<Executable> observeHiddenOperation(int port) throws Exception {
        Exchange document = get(port, documentPath(HiddenOpApi.NAME));
        String visiblePath = HiddenOperationResource.ROUTE + HiddenOperationResource.VISIBLE_PATH;
        return List.of(
                () -> {
                    assertEquals(200, document.status(), () -> "the document is served: " + document);
                    JsonObject paths = new JsonObject(document.text()).getJsonObject("paths");
                    assertNotNull(paths, () -> "the document has paths: " + document);
                    assertEquals(List.of(visiblePath), List.copyOf(paths.fieldNames()), "the published paths");
                    JsonObject item = paths.getJsonObject(visiblePath);
                    assertEquals(List.of("get"), List.copyOf(item.fieldNames()), "the operations of " + visiblePath);
                    assertEquals(
                            HiddenOperationResource.VISIBLE_OPERATION_ID,
                            item.getJsonObject("get").getString("operationId"),
                            "the visible operation id");
                },
                () -> assertFalse(
                        document.text().contains(HiddenOperationResource.HIDDEN_OPERATION_ID),
                        () -> "no byte of the document contains " + HiddenOperationResource.HIDDEN_OPERATION_ID));
    }

    // --- Refusals ---

    /** An enabled document refuses the deployment before listening, naming the entry and its one fix. */
    private static void assertRefused(Case row, Outcome outcome) {
        Refusal refusal = row.refusal();
        Throwable failure = outcome.failure();
        String message = failure == null ? "" : String.valueOf(failure.getMessage());
        String prefix = "apidocs.documents." + row.application() + ": Application '" + row.application()
                + "' (declared by " + row.declaringType().getName() + ") at mount '" + MOUNT + "'";
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertNotNull(failure, "the deployment fails"));
        checks.add(() -> assertNull(outcome.port(), "no port is published"));
        checks.add(() -> assertTrue(message.startsWith(prefix), "the message starts with " + prefix));
        checks.add(() -> assertTrue(
                message.contains(refusal.operationId()), "the message names the operation " + refusal.operationId()));
        for (String fragment : refusal.fragments()) {
            checks.add(() -> assertTrue(message.contains(fragment), "the message names " + fragment));
        }
        for (String fix : FIXES) {
            if (fix.equals(refusal.fix())) {
                checks.add(() -> assertTrue(message.contains(fix), "the message carries the fix: " + fix));
            } else {
                checks.add(() -> assertFalse(message.contains(fix), "the message does not carry the fix: " + fix));
            }
        }
        for (String text : SCHEMA_TEXT) {
            checks.add(() -> assertFalse(message.contains(text), "the message holds no " + text));
        }
        assertAll("the refusal; message: " + message, checks);
    }

    // --- Helpers ---

    private static Arguments row(String label, Case row) {
        return Arguments.of(Named.of(label, row));
    }

    /** A case whose enabled public document must refuse the deployment. */
    private static Case refused(
            String application, Class<?> declaringType, Function<JsonObject, Served> components, Refusal refusal) {
        return new Case(
                application,
                declaringType,
                components,
                InputAssemblyIT.webValidationConfig(application),
                refusal,
                null);
    }

    private static String documentPath(String application) {
        return InputAssemblyIT.documentPath(application, InputAssemblyIT.JSON_FORM);
    }

    /** Returns a component's properties, failing with what is published when they are absent. */
    private static JsonObject properties(JsonObject document, String component) {
        JsonObject components = document.getJsonObject("components");
        assertNotNull(components, () -> "the document has components: " + document.encode());
        JsonObject schemas = components.getJsonObject("schemas");
        assertNotNull(schemas, () -> "the document has component schemas: " + components.encode());
        JsonObject schema = schemas.getJsonObject(component);
        assertNotNull(schema, () -> "component '" + component + "' is published; keys: " + schemas.fieldNames());
        JsonObject properties = schema.getJsonObject("properties");
        assertNotNull(properties, () -> "component '" + component + "' has properties: " + schema.encode());
        return properties;
    }

    /**
     * Deploys a fresh component of the case on the class's Vert.x instance; the outcome holds the
     * deployment's failure, or its id and published port. The component is created inside the
     * verticle supplier, so a failure while provisioning it fails the deployment.
     */
    private static Outcome deploy(Function<JsonObject, Served> components, JsonObject config) throws Exception {
        vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).clear();
        return StartupDeployments.deploy(vertx, () -> components.apply(config).httpVerticle());
    }

    /** Undeploys a successful deployment, if any, and clears the {@code vertique} local map. */
    private static void undeploy(Outcome outcome) throws Exception {
        try {
            StartupDeployments.undeploy(vertx, outcome);
        } finally {
            vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).clear();
        }
    }

    /**
     * One answered request.
     *
     * @param status the status code
     * @param text   the body as text, empty when there was none
     */
    private record Exchange(int status, String text) {

        @Override
        public String toString() {
            return status + " " + text;
        }
    }

    private static Exchange get(int port, String path) throws Exception {
        try {
            HttpResponse<Buffer> response = client.get(port, HOST, path)
                    .send()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(StartupDeployments.BOUND.toMillis(), TimeUnit.MILLISECONDS);
            String text = response.bodyAsString();
            return new Exchange(response.statusCode(), text == null ? "" : text);
        } catch (ExecutionException failed) {
            throw new AssertionError("GET " + path + " failed", failed.getCause());
        }
    }
}
