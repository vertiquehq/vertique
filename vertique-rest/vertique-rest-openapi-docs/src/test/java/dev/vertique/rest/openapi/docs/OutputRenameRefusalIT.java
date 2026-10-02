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
import dev.vertique.rest.openapi.docs.fixture.responses.dto.Note;
import dev.vertique.rest.openapi.docs.fixture.responses.it.ElsewhereApi;
import dev.vertique.rest.openapi.docs.fixture.responses.it.FixedReceiptResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.NoteExplicitResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.NoteResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.NotesApi;
import dev.vertique.rest.openapi.docs.fixture.responses.it.NotesExplicitApi;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments.Outcome;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * Deploys documented applications whose published output type {@code Note} carries a member whose
 * {@code @Schema(name)} differs from the name Jackson serializes, and checks that an enabled
 * document refuses the deployment before a port is published, whether the type is inferred from
 * the return type or declared as response content, while the same composition without an enabled
 * document deploys and answers with the serialized name.
 *
 * <p>Each case is its own composition and configuration, deployed through {@code deploy}. A
 * refusal message must start with the document's configuration path and the application's
 * subject, name the operation, the declaring type, the member, the serialized name, and the schema
 * name, and hold no schema text. Every successful deployment is undeployed before the case's
 * assertions run.
 *
 * <p>The application's document is also disabled beside another application of the same
 * composition whose document stays enabled: the deployment starts, the resource answers with the
 * serialized name, and the enabled document describes neither the operation nor the schema name of
 * the disabled one.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class OutputRenameRefusalIT {

    private static final String HOST = "127.0.0.1";

    /** The mount the applications whose output type is {@code Note} are served under. */
    private static final String MOUNT = "/api/*";

    /** The clause naming the member of {@code Note}, its serialized name, and its schema name. */
    private static final String RENAME_CLAUSE = "its member 'note' is serialized as 'note' but described as 'remark'";

    /** The name Jackson serializes the member under. */
    private static final String SERIALIZED_NAME = "note";

    /** The name {@code @Schema(name)} gives the member. */
    private static final String SCHEMA_NAME = "remark";

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
     * One deployment of the matrix.
     *
     * @param application        the application, which also names its document
     * @param declaringType      the application's declaring interface
     * @param components         creates the case's component from its configuration
     * @param config             the case's configuration
     * @param operationId        the operation a refusal must name, or {@code null} when the case deploys
     * @param route              the path a deployed case is requested on
     * @param enabledDocument    the document of another application of the composition that stays
     *     enabled and is fetched once the case deploys, or {@code null} when there is none
     * @param enabledOperationId the operation {@code enabledDocument} must describe, or {@code null}
     */
    record Case(
            String application,
            Class<?> declaringType,
            Function<JsonObject, Served> components,
            JsonObject config,
            String operationId,
            String route,
            String enabledDocument,
            String enabledOperationId) {

        Case(
                String application,
                Class<?> declaringType,
                Function<JsonObject, Served> components,
                JsonObject config,
                String operationId,
                String route) {
            this(application, declaringType, components, config, operationId, route, null, null);
        }

        boolean refused() {
            return operationId != null;
        }
    }

    static Stream<Arguments> cases() {
        JsonObject disabled = DocsConfigs.withDocumentEnabled(
                InputAssemblyIT.webValidationConfig(NotesApi.NAME), NotesApi.NAME, false);
        return Stream.of(
                Arguments.of(Named.of(
                        "inferred Future<Note>, document enabled",
                        new Case(
                                NotesApi.NAME,
                                NotesApi.class,
                                config -> DaggerResponseTestComponents_NotesComponent.factory()
                                        .create(config),
                                InputAssemblyIT.webValidationConfig(NotesApi.NAME),
                                NoteResource.OPERATION_ID,
                                NotesApi.PATH + NoteResource.ROUTE))),
                Arguments.of(Named.of(
                        "declared Note content, document enabled",
                        new Case(
                                NotesExplicitApi.NAME,
                                NotesExplicitApi.class,
                                config -> DaggerResponseTestComponents_NotesExplicitComponent.factory()
                                        .create(config),
                                InputAssemblyIT.webValidationConfig(NotesExplicitApi.NAME),
                                NoteExplicitResource.OPERATION_ID,
                                NotesExplicitApi.PATH + NoteExplicitResource.ROUTE))),
                Arguments.of(Named.of(
                        "inferred Future<Note>, document disabled",
                        new Case(
                                NotesApi.NAME,
                                NotesApi.class,
                                config -> DaggerResponseTestComponents_NotesComponent.factory()
                                        .create(config),
                                disabled,
                                null,
                                NotesApi.PATH + NoteResource.ROUTE))),
                Arguments.of(Named.of(
                        "disabled document beside an enabled one",
                        new Case(
                                NotesApi.NAME,
                                NotesApi.class,
                                config -> DaggerResponseTestComponents_NotesBesideElsewhereComponent.factory()
                                        .create(config),
                                DocsConfigs.withDocumentEnabled(
                                        InputAssemblyIT.webValidationConfig(NotesApi.NAME, ElsewhereApi.NAME),
                                        NotesApi.NAME,
                                        false),
                                null,
                                NotesApi.PATH + NoteResource.ROUTE,
                                ElsewhereApi.NAME,
                                FixedReceiptResource.OPERATION_ID))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName(
            "An output member renamed away from its serialized name fails an enabled document's deployment, inferred or declared, and does not affect startup without one")
    void renamedOutputPropertyFailsPublication(Case row) throws Exception {
        // Given: the case's composition and configuration
        // When: it is deployed and, when it deploys, its resource is requested
        Outcome outcome = deploy(row.components(), row.config());
        Integer status = null;
        String body = null;
        Integer documentStatus = null;
        String documentBody = null;
        try {
            if (!row.refused() && outcome.deployed() && outcome.port() != null) {
                HttpResponse<Buffer> answer = Futures.await(
                        client.get(outcome.port(), HOST, row.route()).send(), StartupDeployments.BOUND);
                status = answer.statusCode();
                body = answer.bodyAsString();
                if (row.enabledDocument() != null) {
                    String documentUri = InputAssemblyIT.documentPath(row.enabledDocument(), InputAssemblyIT.JSON_FORM);
                    HttpResponse<Buffer> document = Futures.await(
                            client.get(outcome.port(), HOST, documentUri).send(), StartupDeployments.BOUND);
                    documentStatus = document.statusCode();
                    documentBody = document.bodyAsString();
                }
            }
        } finally {
            StartupDeployments.undeployAndClear(vertx, outcome);
        }

        // Then
        if (row.refused()) {
            assertRefused(row, outcome);
        } else {
            assertStarted(outcome, status, body);
            if (row.enabledDocument() != null) {
                assertEnabledDocument(row, documentStatus, documentBody);
            }
        }
    }

    /**
     * The other application's enabled document is served and describes only its own operation: the
     * application whose document is disabled contributes neither its operation nor its schema name.
     */
    private static void assertEnabledDocument(Case row, Integer status, String body) {
        assertEquals(200, status, () -> "the document '" + row.enabledDocument() + "' is served: " + body);
        assertNotNull(body, "the document has a body");
        assertAll(
                "the document '" + row.enabledDocument() + "': " + body,
                () -> assertTrue(
                        body.contains(row.enabledOperationId()),
                        "it describes the operation " + row.enabledOperationId()),
                () -> assertFalse(
                        body.contains(NoteResource.OPERATION_ID),
                        "it does not describe the operation " + NoteResource.OPERATION_ID),
                () -> assertFalse(body.contains(SCHEMA_NAME), "it holds no " + SCHEMA_NAME));
    }

    /** An enabled document refuses the deployment before listening, naming the rename. */
    private static void assertRefused(Case row, Outcome outcome) {
        Throwable failure = outcome.failure();
        String message = failure == null ? "" : String.valueOf(failure.getMessage());
        String prefix = "apidocs.documents." + row.application() + ": Application '" + row.application()
                + "' (declared by " + row.declaringType().getName() + ") at mount '" + MOUNT + "'";
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertNotNull(failure, "the deployment fails"));
        checks.add(() -> assertNull(outcome.port(), "no port is published"));
        checks.add(() -> assertTrue(message.startsWith(prefix), "the message starts with " + prefix));
        checks.add(() -> assertTrue(
                message.contains(row.operationId()), "the message names the operation " + row.operationId()));
        checks.add(() -> assertTrue(
                message.contains(Note.class.getName()), "the message names the type " + Note.class.getName()));
        checks.add(() -> assertTrue(
                message.contains(RENAME_CLAUSE),
                "the message names the member, its serialized name, and its schema name: " + RENAME_CLAUSE));
        checks.add(() -> assertTrue(message.contains(SCHEMA_NAME), "the message names the schema name " + SCHEMA_NAME));
        for (String text : SCHEMA_TEXT) {
            checks.add(() -> assertFalse(message.contains(text), "the message holds no " + text));
        }
        assertAll("the refusal; message: " + message, checks);
    }

    /** Without an enabled document the composition deploys and answers with the serialized name only. */
    private static void assertStarted(Outcome outcome, Integer status, String body) {
        assertAll(
                "the unpublished composition",
                () -> assertNull(outcome.failure(), () -> "the deployment succeeds: " + outcome.failure()),
                () -> assertNotNull(outcome.port(), "a port is published"));
        assertEquals(200, status, () -> "GET answers 200: " + body);
        assertNotNull(body, "GET answers with a body");
        JsonObject json = new JsonObject(body);
        assertEquals(Set.of(SERIALIZED_NAME), json.fieldNames(), () -> "the body's only key: " + body);
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
}
