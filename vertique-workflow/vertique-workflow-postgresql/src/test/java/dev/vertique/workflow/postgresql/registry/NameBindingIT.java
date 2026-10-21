// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.failureOf;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static dev.vertique.workflow.postgresql.registry.IsolatedFixtureCode.APPROVAL_NAME;
import static dev.vertique.workflow.postgresql.registry.IsolatedFixtureCode.DEFINITION_ID;
import static dev.vertique.workflow.postgresql.registry.IsolatedFixtureCode.STATE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.IsolatedFixtureCode.Naming;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a version keeps the type names its plan hash was computed with. A fixture package is compiled
 * unannotated for the first start and annotated, or moved to another package, for later ones, each loaded through its
 * own class loader, so the same binary name is present without and then with a logical name across restarts on one
 * database.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class NameBindingIT {

    /** The contract of the fixture definitions; it is named by its binary name and never moves. */
    @WorkflowContract
    public interface BindingContract {}

    /** The start payload of the fixture definitions; it is not part of a plan hash. */
    public record StartOrder(String id) {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_name_binding_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String ORIGINAL = "fixture.binding";
    private static final String MOVED = "fixture.binding.moved";
    private static final String DOCUMENT_ID = "binding-doc";
    private static final String ACME = "acme";
    private static final Instant LOADED_AT = Instant.parse("2026-10-21T12:00:00Z");
    private static final String SECRET_ORDER = "secret-order-77";

    @Test
    @DisplayName("annotating published types keeps the version's hash and its instance resumes")
    void annotatingPublishedTypesKeepsHashAndResumesInstances(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, DEFINITION_ID);

            // Given boot 1 with the unannotated fixture, version 1 published and one instance waiting on its task
            IsolatedFixtureCode unannotated = fixture(database, ORIGINAL, Naming.UNANNOTATED, false);
            Boot first = boot(database, unannotated, 1);
            UUID instance = startOrder(first, "order-1");
            String publishedHash = hash(database, DEFINITION_ID, 1);
            assertThat(taskSnapshot(database, instance)).contains(unannotated.approvalClassName());
            first.close();

            // When boot 2 replaces boot 1 with the variant where both classes carry a logical name
            IsolatedFixtureCode annotated = fixture(database, ORIGINAL, Naming.ANNOTATED, false);
            Boot second = boot(database, annotated, 1);

            // Then startup succeeded and the row's hash is the one computed over the binary names, unchanged
            assertThat(annotated.planHash(1)).as("the hash over logical names").isNotEqualTo(publishedHash);
            assertThat(unannotated.planHash(1)).isEqualTo(publishedHash);
            StoredRow afterAnnotation = row(database, DEFINITION_ID, 1);
            assertThat(afterAnnotation.hash()).isEqualTo(publishedHash);

            // And the binding maps both binary names to themselves
            assertThat(afterAnnotation.binding())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            annotated.stateClassName(), annotated.stateClassName(),
                            annotated.approvalClassName(), annotated.approvalClassName()));

            // And completing the task with an approval completes the instance
            approve(second, instance);
            assertThat(database.instanceStatus(instance)).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("a version first published after annotating binds the logical names")
    void versionPublishedAfterAnnotationBindsLogicalNames(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, DEFINITION_ID);

            // Given boot 2 of the first scenario: version 1 published unannotated and kept after annotating
            IsolatedFixtureCode annotated =
                    publishedThenAnnotated(database, true).annotated();
            StoredRow versionOne = row(database, DEFINITION_ID, 1);

            // When boot 3 adds version 2 with the annotated variant
            boot(database, annotated, 1, 2);

            // Then version 2 binds the binary names to the logical names
            StoredRow versionTwo = row(database, DEFINITION_ID, 2);
            assertThat(versionTwo.binding())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            annotated.stateClassName(), STATE_NAME,
                            annotated.approvalClassName(), APPROVAL_NAME));

            // And its hash is the one computed over those names
            assertThat(versionTwo.hash()).isEqualTo(annotated.planHash(2));

            // And version 1's row is unchanged
            assertThat(row(database, DEFINITION_ID, 1)).isEqualTo(versionOne);
        }
    }

    @Test
    @DisplayName("after the move, logically bound instances resume and a retired version does not block startup")
    void movingAnnotatedTypesKeepsLogicallyBoundVersions(Vertx vertx) {
        JsonObject config = new JsonObject()
                .put("workflow", new JsonObject().put("definitions", new JsonObject().put("maxStalenessSeconds", 1)));
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, DEFINITION_ID);

            // Given version 1, bound by binary name, finished and then deprecated and retired, and version 2,
            // published after annotating, with an instance waiting on its task
            Published published = publishedThenAnnotated(database, true);
            IsolatedFixtureCode annotated = published.annotated();
            Boot third = database.node(none(), unobserved(), definitions(annotated, 1, 2), config, annotated.catalog());
            approve(third, published.instance());
            UUID instance = startOrder(third, "order-2");
            DefinitionKey versionOne = new DefinitionKey("default", DEFINITION_ID, 1);
            await(third.app().definitionService().deprecate(versionOne));
            database.ageStatus(versionOne, 60);
            await(third.app().definitionService().retire(versionOne));
            StoredRow versionTwo = row(database, DEFINITION_ID, 2);
            assertThat(row(database, DEFINITION_ID, 1).status()).isEqualTo("RETIRED");
            assertThat(row(database, DEFINITION_ID, 1).binding())
                    .containsKey(annotated.stateClassName())
                    .containsValue(annotated.stateClassName());
            third.close();

            // When boot 4 uses the annotated variant moved to another package, without version 1
            IsolatedFixtureCode moved = fixture(database, MOVED, Naming.ANNOTATED, false);
            Boot fourth = boot(database, moved, 2);

            // Then startup succeeded although the retired version names classes that no longer exist, and version 2's
            // hash is unchanged
            assertThat(row(database, DEFINITION_ID, 2).hash()).isEqualTo(versionTwo.hash());
            assertThat(moved.planHash(2)).isEqualTo(versionTwo.hash());

            // And the waiting instance resumes and completes with an approval
            approve(fourth, instance);
            assertThat(database.instanceStatus(instance)).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("moving a type still bound by binary name fails startup naming the version")
    void movingBinaryBoundTypeFailsStartupNamingVersion(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, DEFINITION_ID);

            // Given version 1 bound by binary name and not retired, with an instance, and version 2 bound by logical
            // name
            IsolatedFixtureCode annotated =
                    publishedThenAnnotated(database, true).annotated();
            boot(database, annotated, 1, 2).close();

            // When a node starts with the variant moved to another package
            IsolatedFixtureCode moved = fixture(database, MOVED, Naming.ANNOTATED, false);
            Boot refused = database.attempt(
                    none(), unobserved(), definitions(moved, 1, 2), null, new JsonObject(), moved.catalog());

            // Then startup fails with an unknown type name naming the scope, the definition, the version and the
            // class, and no state value
            assertThat(refused.failure()).isNotNull();
            WorkflowUnknownTypeNameException unknown =
                    causeOf(refused.failure(), WorkflowUnknownTypeNameException.class);
            assertThat(unknown.getMessage())
                    .contains("default", DEFINITION_ID, ORIGINAL + ".OrderState")
                    .containsPattern("\\b1\\b")
                    .doesNotContain(SECRET_ORDER);
        }
    }

    @Test
    @DisplayName("only a rehash of changed code without live pins refreshes the binding")
    void rehashWithoutLivePinsRefreshesBinding(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, DEFINITION_ID);

            // Given unannotated version 1 published and no live instance
            IsolatedFixtureCode unannotated = fixture(database, ORIGINAL, Naming.UNANNOTATED, false);
            boot(database, unannotated, 1).close();
            StoredRow published = row(database, DEFINITION_ID, 1);

            // When a node starts with the annotated variant and the same step graph
            IsolatedFixtureCode annotated = fixture(database, ORIGINAL, Naming.ANNOTATED, false);
            WorkflowDefinitionSqlObserver sameGraphWrites = new WorkflowDefinitionSqlObserver();
            Boot sameGraph = database.attempt(
                    none(),
                    unobserved(),
                    definitions(annotated, 1),
                    sameGraphWrites,
                    new JsonObject(),
                    annotated.catalog());
            assertThat(sameGraph.failure()).isNull();
            sameGraph.close();

            // Then the row is untouched and no statement wrote a hash or a binding
            assertThat(row(database, DEFINITION_ID, 1)).isEqualTo(published);
            assertThat(definitionWrites(sameGraphWrites)).isEmpty();

            // When a node starts with the annotated variant whose version 1 adds one step
            IsolatedFixtureCode changed = fixture(database, ORIGINAL, Naming.ANNOTATED, true);
            WorkflowDefinitionSqlObserver rehashWrites = new WorkflowDefinitionSqlObserver();
            Boot rehashed = database.attempt(
                    none(), unobserved(), definitions(changed, 1), rehashWrites, new JsonObject(), changed.catalog());
            assertThat(rehashed.failure()).isNull();
            rehashed.close();

            // Then the row's hash and binding both use the logical names
            StoredRow afterRehash = row(database, DEFINITION_ID, 1);
            assertThat(afterRehash.hash()).isNotEqualTo(published.hash()).isEqualTo(changed.planHash(1));
            assertThat(afterRehash.binding())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            changed.stateClassName(), STATE_NAME,
                            changed.approvalClassName(), APPROVAL_NAME));

            // And the hash and the binding were written by one statement
            List<String> writes = definitionWrites(rehashWrites);
            assertThat(writes)
                    .isNotEmpty()
                    .allSatisfy(sql -> assertThat(sql).contains("plan_hash").contains("name_binding"));
        }
    }

    @Test
    @DisplayName("a document version restored after annotating keeps its bound hash")
    void documentRestoredAfterAnnotationKeepsBoundHash(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            ActivationFixtures.clearActivations(database.admin(), DOCUMENT_ID);
            database.ownRows(DOCUMENT_ID);
            database.onClose(() -> ActivationFixtures.clearActivations(database.admin(), DOCUMENT_ID));

            // Given a document naming the unannotated payload class by binary name, loaded into acme, published and
            // activated, with a live instance
            IsolatedFixtureCode unannotated = fixture(database, ORIGINAL, Naming.UNANNOTATED, false);
            Boot first = boot(database, unannotated);
            await(first.app()
                    .definitionService()
                    .load(
                            ACME,
                            documentText(unannotated.approvalClassName()).getBytes(StandardCharsets.UTF_8),
                            DocumentFormat.YAML,
                            source("binding-doc", LOADED_AT)));
            DefinitionKey key = new DefinitionKey(ACME, DOCUMENT_ID, 1);
            await(first.app().definitionService().publish(key));
            await(first.app().definitionService().activate(ACME, DOCUMENT_ID, ACME, 1));
            UUID instance = await(first.app()
                            .workflowOperations()
                            .start(new StartCommand(
                                    DOCUMENT_ID,
                                    BindingContract.class.getName(),
                                    new PlaceOrderDoc("order-doc-1"),
                                    "start-" + UUID.randomUUID(),
                                    null,
                                    null,
                                    null,
                                    ACME,
                                    null)))
                    .value();
            StoredRow published = row(database, ACME, DOCUMENT_ID, 1);
            first.close();

            // When boot 2 with the annotated variant restores the row at startup
            IsolatedFixtureCode annotated = fixture(database, ORIGINAL, Naming.ANNOTATED, false);
            Boot second = boot(database, annotated);

            // Then the restored runtime's plan hash equals the row's, and the row is unchanged
            assertThat(second.app().workflowRegistry().resolve(key).plan().planHash())
                    .isEqualTo(published.hash());
            assertThat(row(database, ACME, DOCUMENT_ID, 1)).isEqualTo(published);

            // And the instance resumes
            approve(second, instance);
            assertThat(database.instanceStatus(instance)).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("a startup refusal lists at most ten unresolved names and counts the rest")
    void startupRefusalBoundsTheListedNames(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, DEFINITION_ID);

            // Given version 1 published and a stored binding that names twenty-five classes that do not exist
            IsolatedFixtureCode annotated =
                    publishedThenAnnotated(database, false).annotated();
            JsonObject gone = new JsonObject();
            for (int index = 0; index < 25; index++)
                gone.put(String.format("gone.Type%02d", index), String.format("gone.Type%02d", index));
            await(database.admin()
                    .preparedQuery("UPDATE workflow_definitions SET name_binding = $1::jsonb"
                            + " WHERE scope_key = 'default' AND definition_id = $2 AND definition_version = 1")
                    .execute(Tuple.of(gone, DEFINITION_ID)));

            // When a node starts over it
            Boot refused = database.attempt(
                    none(), unobserved(), definitions(annotated, 1), null, new JsonObject(), annotated.catalog());

            // Then startup fails with an unknown type name that lists ten names and states the total
            assertThat(refused.failure()).isNotNull();
            WorkflowUnknownTypeNameException unknown =
                    causeOf(refused.failure(), WorkflowUnknownTypeNameException.class);
            assertThat(unknown.getMessage().split("gone\\.Type", -1).length - 1)
                    .as("listed names")
                    .isLessThanOrEqualTo(10);
            assertThat(unknown.getMessage()).containsPattern("\\b25\\b");
        }
    }

    @Test
    @DisplayName("reinstating a retired document whose bound class has moved fails naming the version and the names")
    void reinstatingRetiredDocumentAfterMoveFailsNamingVersion(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            ActivationFixtures.clearActivations(database.admin(), DOCUMENT_ID);
            database.ownRows(DOCUMENT_ID);
            database.onClose(() -> ActivationFixtures.clearActivations(database.admin(), DOCUMENT_ID));

            // Given a document bound to the binary name of its payload class, published and then retired
            IsolatedFixtureCode unannotated = fixture(database, ORIGINAL, Naming.UNANNOTATED, false);
            Boot first = boot(database, unannotated);
            DefinitionKey key = new DefinitionKey(ACME, DOCUMENT_ID, 1);
            await(first.app()
                    .definitionService()
                    .load(
                            ACME,
                            documentText(unannotated.approvalClassName()).getBytes(StandardCharsets.UTF_8),
                            DocumentFormat.YAML,
                            source("binding-doc", LOADED_AT)));
            await(first.app().definitionService().publish(key));
            await(first.app().definitionService().deprecate(key));
            database.ageStatus(key, 60);
            await(first.app().definitionService().retire(key));
            assertThat(row(database, ACME, DOCUMENT_ID, 1).status()).isEqualTo("RETIRED");
            first.close();

            // And a node whose deployed code has the classes in another package
            IsolatedFixtureCode moved = fixture(database, MOVED, Naming.ANNOTATED, false);
            Boot second = boot(database, moved);

            // When the retired version is reinstated
            Throwable failure = failureOf(second.app().definitionService().reinstate(key));

            // Then it fails with an unknown type name naming the scope, definition, version and the class
            assertThat(failure).isNotNull();
            WorkflowUnknownTypeNameException unknown = causeOf(failure, WorkflowUnknownTypeNameException.class);
            assertThat(unknown.getMessage())
                    .contains(ACME, DOCUMENT_ID, unannotated.approvalClassName())
                    .containsPattern("\\b1\\b");

            // And the version stays retired
            assertThat(row(database, ACME, DOCUMENT_ID, 1).status()).isEqualTo("RETIRED");
        }
    }

    @Test
    @DisplayName("publishing a candidate document whose bound class has moved fails naming the version and the names")
    void publishingCandidateDocumentAfterMoveFailsNamingVersion(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            ActivationFixtures.clearActivations(database.admin(), DOCUMENT_ID);
            database.ownRows(DOCUMENT_ID);
            database.onClose(() -> ActivationFixtures.clearActivations(database.admin(), DOCUMENT_ID));

            // Given a candidate document bound to the binary name of its payload class
            IsolatedFixtureCode unannotated = fixture(database, ORIGINAL, Naming.UNANNOTATED, false);
            Boot first = boot(database, unannotated);
            DefinitionKey key = new DefinitionKey(ACME, DOCUMENT_ID, 1);
            await(first.app()
                    .definitionService()
                    .load(
                            ACME,
                            documentText(unannotated.approvalClassName()).getBytes(StandardCharsets.UTF_8),
                            DocumentFormat.YAML,
                            source("binding-doc", LOADED_AT)));
            assertThat(row(database, ACME, DOCUMENT_ID, 1).status()).isEqualTo("CANDIDATE");
            first.close();

            // And a node whose deployed code has the classes in another package
            IsolatedFixtureCode moved = fixture(database, MOVED, Naming.ANNOTATED, false);
            Boot second = boot(database, moved);

            // When the candidate is published
            Throwable failure = failureOf(second.app().definitionService().publish(key));

            // Then it fails with an unknown type name naming the scope, definition, version and the class
            assertThat(failure).isNotNull();
            WorkflowUnknownTypeNameException unknown = causeOf(failure, WorkflowUnknownTypeNameException.class);
            assertThat(unknown.getMessage())
                    .contains(ACME, DOCUMENT_ID, unannotated.approvalClassName())
                    .containsPattern("\\b1\\b");
        }
    }

    // --- Scenarios shared by the proofs ---

    /** The annotated fixture of the first scenario and the instance version 1 started with, when it has one. */
    private record Published(IsolatedFixtureCode annotated, UUID instance) {}

    /**
     * Boots 1 and 2 of the first scenario: version 1 published with the unannotated fixture, then the annotated variant
     * started over it. Version 1 has an instance waiting on its task when asked.
     */
    private static Published publishedThenAnnotated(DefinitionScenario database, boolean withInstance) {
        IsolatedFixtureCode unannotated = fixture(database, ORIGINAL, Naming.UNANNOTATED, false);
        Boot first = boot(database, unannotated, 1);
        UUID instance = withInstance ? startOrder(first, SECRET_ORDER) : null;
        first.close();
        IsolatedFixtureCode annotated = fixture(database, ORIGINAL, Naming.ANNOTATED, false);
        boot(database, annotated, 1).close();
        return new Published(annotated, instance);
    }

    // --- Starting, completing and reading ---

    private static IsolatedFixtureCode fixture(
            DefinitionScenario database, String packageName, Naming naming, boolean secondReview) {
        IsolatedFixtureCode code = IsolatedFixtureCode.compile(packageName, naming, secondReview);
        database.onClose(code::close);
        return code;
    }

    private static Boot boot(DefinitionScenario database, IsolatedFixtureCode code, long... versions) {
        return database.node(none(), unobserved(), definitions(code, versions), new JsonObject(), code.catalog());
    }

    private static List<WorkflowDefinition<?, ?>> definitions(IsolatedFixtureCode code, long... versions) {
        List<WorkflowDefinition<?, ?>> definitions = new ArrayList<>();
        for (long version : versions) definitions.add(code.definition(version));
        return definitions;
    }

    private static UUID startOrder(Boot node, String orderId) {
        return await(node.app()
                        .workflowOperations()
                        .start(new StartCommand(
                                DEFINITION_ID,
                                BindingContract.class.getName(),
                                new StartOrder(orderId),
                                "start-" + UUID.randomUUID(),
                                null,
                                null,
                                null,
                                null,
                                null)))
                .value();
    }

    /** Completes the instance's open task with an approval decision. */
    private static void approve(Boot node, UUID instance) {
        UUID task = await(node.app()
                        .pool()
                        .preparedQuery("SELECT task_id FROM workflow_tasks WHERE workflow_id = $1 AND status = 'OPEN'")
                        .execute(Tuple.of(instance)))
                .iterator()
                .next()
                .getUUID("task_id");
        TaskCompletionCommand approval = new TaskCompletionCommand(
                task, "approve", Map.of("by", "alice"), "approve-" + task, new WorkflowActor.User("alice"), null);
        TaskMutationResult result = await(node.app()
                .pool()
                .withTransaction(tx -> node.app().taskCallbacks().taskCompleted(approval, tx)));
        assertThat(result).isEqualTo(TaskMutationResult.APPLIED);
    }

    /** The decision options stored with the instance's open task. */
    private static String taskSnapshot(DefinitionScenario database, UUID instance) {
        return await(database.admin()
                        .preparedQuery("SELECT decisions_snapshot_json::text AS snapshot FROM workflow_tasks"
                                + " WHERE workflow_id = $1")
                        .execute(Tuple.of(instance)))
                .iterator()
                .next()
                .getString("snapshot");
    }

    /** One definition row as the proofs compare it: status, plan hash and name binding. */
    private record StoredRow(String status, String hash, Map<String, String> binding) {}

    private static String hash(DefinitionScenario database, String id, long version) {
        return await(database.admin()
                        .preparedQuery("SELECT plan_hash FROM workflow_definitions"
                                + " WHERE scope_key = 'default' AND definition_id = $1 AND definition_version = $2")
                        .execute(Tuple.of(id, version)))
                .iterator()
                .next()
                .getString("plan_hash");
    }

    private static StoredRow row(DefinitionScenario database, String id, long version) {
        return row(database, "default", id, version);
    }

    private static StoredRow row(DefinitionScenario database, String scopeKey, String id, long version) {
        io.vertx.sqlclient.Row stored = await(
                        database.admin().preparedQuery("""
                                SELECT status, plan_hash, name_binding FROM workflow_definitions
                                WHERE scope_key = $1 AND definition_id = $2 AND definition_version = $3
                                """).execute(Tuple.of(scopeKey, id, version)))
                .iterator()
                .next();
        Map<String, String> binding = new TreeMap<>();
        stored.getJsonObject("name_binding").forEach(entry -> binding.put(entry.getKey(), (String) entry.getValue()));
        return new StoredRow(stored.getString("status"), stored.getString("plan_hash"), binding);
    }

    /** The statements that wrote the plan hash or the name binding of a definition row. */
    private static List<String> definitionWrites(WorkflowDefinitionSqlObserver observer) {
        return observer.statements().stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT))
                .filter(sql -> sql.contains("workflow_definitions") && !sql.contains("workflow_instances"))
                .filter(sql -> sql.stripLeading().startsWith("update")
                        || sql.stripLeading().startsWith("insert"))
                .filter(sql -> sql.contains("plan_hash") || sql.contains("name_binding"))
                .toList();
    }

    private static <T extends Throwable> T causeOf(Throwable failure, Class<T> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return type.cast(cause);
        }
        throw new AssertionError("Expected a " + type.getSimpleName() + " in the cause chain", failure);
    }

    /** A document whose only step is a human task with an approval decision, naming the payload class by name. */
    private static String documentText(String approvalClassName) {
        return """
                definitionId: %s
                definitionVersion: 1
                stateType: dev.vertique.workflow.definition.it.fixture.OrderDocState
                contract: %s
                startPayloadType: dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc
                initialStateMapper: doc.order.fromPlaceOrder
                initialStep: review
                steps:
                  - id: review
                    type: human-task
                    taskType: approval
                    assignment:
                      mode: role
                      value: reviewers
                    decisions:
                      - name: approve
                        payloadType: %s
                        applicator: doc.order.keepState
                        next: done
                  - id: done
                    type: complete
                """.formatted(DOCUMENT_ID, BindingContract.class.getName(), approvalClassName);
    }

    /** Clears the ids' rows and records now and again when the scenario closes. */
    private static void own(DefinitionScenario database, String... ids) {
        ActivationFixtures.clearActivations(database.admin(), ids);
        database.ownRows(ids);
        database.onClose(() -> ActivationFixtures.clearActivations(database.admin(), ids));
    }

    private static BootObservation unobserved() {
        return new BootObservation("binding-unused", 1, "unused", false);
    }

    private static WorkflowDefinitionSource none() {
        return List::of;
    }
}
