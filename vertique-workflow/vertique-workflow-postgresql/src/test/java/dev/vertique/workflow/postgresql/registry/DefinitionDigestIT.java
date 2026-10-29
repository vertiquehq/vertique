// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.documentText;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.failureOf;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.sha256Hex;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.CompiledDefinitionRef;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Stored;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies that stored document text is checked against its recorded digest wherever the guarded read is used. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DefinitionDigestIT {

    @WorkflowContract
    public interface ActiveCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface EditedCaseV1 extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_digest_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName(
            "the guarded read verifies the stored text against its digest for activation, direct reads and restart")
    void verifiesStoredTextDigestOnGuardedRead(Vertx vertx) {
        String activeId = "digest-guarded-read-active-doc";
        String editedId = "digest-guarded-read-edited-doc";
        String validOversizedId = "digest-oversized-valid-doc";
        String badOversizedId = "digest-oversized-bad-doc";
        DefinitionKey activeKey = new DefinitionKey(WorkflowScopes.DEFAULT, activeId, 1);
        DefinitionKey editedKey = new DefinitionKey(WorkflowScopes.DEFAULT, editedId, 1);
        String editMarker = "# edited directly in the database";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(activeId, editedId, validOversizedId, badOversizedId);

            // Given a running node and two documents loaded at runtime as candidates
            Boot node = scenario.node(List::of);
            AppComponent app = node.app();
            WorkflowDefinitionService service = app.definitionService();
            WorkflowDefinitionRegistryStore store = app.definitionRegistryStore();
            String activeText = documentText(activeId, 1, ActiveCaseV1.class);
            String editedText = documentText(editedId, 1, EditedCaseV1.class);
            CompiledDefinitionRef activeLoaded = await(service.load(
                    WorkflowScopes.DEFAULT,
                    activeText.getBytes(StandardCharsets.UTF_8),
                    DocumentFormat.YAML,
                    source("active", Instant.parse("2026-10-18T12:00:00Z"))));
            CompiledDefinitionRef editedLoaded = await(service.load(
                    WorkflowScopes.DEFAULT,
                    editedText.getBytes(StandardCharsets.UTF_8),
                    DocumentFormat.YAML,
                    source("edited", Instant.parse("2026-10-18T12:01:00Z"))));

            // When the unedited candidate is activated
            await(service.publish(activeKey));

            // Then it is active and registered, and the guarded read returns exactly the text that was loaded
            assertThat(scenario.stored(activeId, 1).status()).isEqualTo("AVAILABLE");
            assertThat(DefinitionScenario.planOf(
                                    app.workflowRegistry(), new DefinitionKey(WorkflowScopes.DEFAULT, activeId, 1))
                            .plan()
                            .planHash())
                    .isEqualTo(activeLoaded.planHash());
            assertThat(await(store.findDocument(activeKey)))
                    .map(WorkflowDefinitionDocument::document)
                    .contains(activeText);

            // Given the second candidate's stored text edited by SQL, changing only a comment so the plan hash holds
            String editedStored = editedText + editMarker + "\n";
            assertThat(planHashOf(app, editedStored, "edited-check")).isEqualTo(editedLoaded.planHash());
            scenario.execute("""
                    UPDATE workflow_definitions SET document = $1
                    WHERE scope_key = 'default' AND definition_id = $2 AND definition_version = 1
                    """, Tuple.of(editedStored, editedId));
            Stored candidateBefore = scenario.stored(editedId, 1);
            assertThat(candidateBefore.status()).isEqualTo("CANDIDATE");
            assertThat(candidateBefore.planHash()).isEqualTo(editedLoaded.planHash());
            assertThat(candidateBefore.document()).isEqualTo(editedStored);

            // When the edited candidate is activated
            Throwable activation = failureOf(service.publish(editedKey));

            // Then activation fails as drift naming the row, without echoing the document
            assertThat(activation)
                    .as("activating a row whose stored text no longer matches its recorded digest")
                    .isNotNull()
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
                        assertThat(failure.key()).isEqualTo(editedKey);
                        assertThat(failure.getMessage())
                                .contains(editedId)
                                .containsIgnoringCase("digest")
                                .doesNotContain(editMarker);
                    });

            // And the claim went back to a candidate, with the row otherwise untouched and nothing registered
            assertThat(scenario.stored(editedId, 1))
                    .usingRecursiveComparison()
                    .ignoringFields("changedAt")
                    .isEqualTo(candidateBefore);
            assertThat(app.workflowRegistry().codeDefinitions())
                    .noneMatch(runtime -> runtime.plan().definitionId().equals(editedId));
            assertThatThrownBy(() -> DefinitionScenario.planOf(
                            app.workflowRegistry(), new DefinitionKey(WorkflowScopes.DEFAULT, editedId, 1)))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);

            // When the edited row is read through the guarded accessor
            Future<Optional<WorkflowDefinitionDocument>> guardedRead = store.findDocument(editedKey);
            Throwable readFailure = failureOf(guardedRead);

            // Then the altered text is never returned and the refusal is the same typed drift
            assertThat(guardedRead.result())
                    .as("the guarded read must not return text that differs from its recorded digest")
                    .isNull();
            assertThat(readFailure)
                    .as("the guarded read of an edited row must fail")
                    .isNotNull()
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
                        assertThat(failure.key()).isEqualTo(editedKey);
                        assertThat(failure.getMessage()).contains(editedId).doesNotContain(editMarker);
                    });

            // Given the active document's stored text edited by SQL while the application is stopped
            String activeEdited = activeText + "# edited while stopped\n";
            assertThat(planHashOf(app, activeEdited, "active-check")).isEqualTo(activeLoaded.planHash());
            node.close();
            scenario.execute("""
                    UPDATE workflow_definitions SET document = $1
                    WHERE scope_key = 'default' AND definition_id = $2 AND definition_version = 1
                    """, Tuple.of(activeEdited, activeId));

            // When the application restarts, building the default scope's documents as it starts
            BootObservation restartObservation = new BootObservation(activeId, 1, activeLoaded.planHash(), true);
            Boot restart = scenario.attempt(
                    List::of,
                    restartObservation,
                    List.of(),
                    null,
                    ManagedDefinitionDatabase.buildingDefaultScopeDocuments(),
                    List.of());

            // Then startup fails as drift naming the edited active row, and nothing is deployed
            assertThat(restart.failure())
                    .as("restart with an active row whose stored text was edited")
                    .isNotNull()
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
                        assertThat(failure.key()).isEqualTo(activeKey);
                        assertThat(failure.getMessage()).contains(activeId);
                    });
            assertThat(restartObservation.events()).isEmpty();
            restart.close();

            // Then the digest recorded at load time is that of the loaded text, not of either edit
            assertThat(storedDigest(scenario, activeId))
                    .isEqualTo(sha256Hex(activeText))
                    .isNotEqualTo(sha256Hex(activeEdited));
            assertThat(storedDigest(scenario, editedId))
                    .isEqualTo(sha256Hex(editedText))
                    .isNotEqualTo(sha256Hex(editedStored));

            // Given the edited active row removed so that a fresh node can start, and two rows above the source
            // envelope, one with a matching digest and one with a wrong digest
            scenario.execute("DELETE FROM workflow_definitions WHERE definition_id = $1", Tuple.of(activeId));
            Boot reader = scenario.node(List::of);
            int envelope = reader.app().workflowDefinitionsConfig().maxDocumentSizeBytes();
            int oversizedLength = envelope + 1;
            insertOversizedRowWithDigest(
                    scenario, validOversizedId, oversizedLength, sha256Hex("x".repeat(oversizedLength)));
            insertOversizedRowWithDigest(scenario, badOversizedId, oversizedLength, "0".repeat(64));

            // When each is read through the guarded accessor
            WorkflowDefinitionRegistryStore oversizedStore = reader.app().definitionRegistryStore();
            Throwable validDigestFailure = failureOf(
                    oversizedStore.findDocument(new DefinitionKey(WorkflowScopes.DEFAULT, validOversizedId, 1)));
            Throwable badDigestFailure = failureOf(
                    oversizedStore.findDocument(new DefinitionKey(WorkflowScopes.DEFAULT, badOversizedId, 1)));

            // Then both give the existing size error, because the envelope is checked before any digest
            for (Throwable failure : new Throwable[] {validDigestFailure, badDigestFailure}) {
                assertThat(failure)
                        .isNotNull()
                        .isInstanceOf(WorkflowDefinitionException.class)
                        .isNotInstanceOf(WorkflowDefinitionLifecycleException.class);
                assertThat(failure.getMessage()).contains("exceeds source envelope");
            }
            assertThat(validDigestFailure.getMessage()).contains(validOversizedId);
            assertThat(badDigestFailure.getMessage()).contains(badOversizedId);
        }
    }

    /** The plan hash that the running graph compiles for the given YAML text. */
    private static String planHashOf(AppComponent app, String yaml, String label) {
        return app.definitionPipeline()
                .load(new DefinitionResource(
                        yaml.getBytes(StandardCharsets.UTF_8),
                        DocumentFormat.YAML,
                        source(label, Instant.parse("2026-10-18T12:30:00Z"))))
                .planHash();
    }

    /** Reads the recorded text digest of a global row. */
    private static String storedDigest(DefinitionScenario scenario, String definitionId) {
        return await(scenario.admin().preparedQuery("""
                                SELECT document_digest FROM workflow_definitions
                                WHERE scope_key = 'default' AND definition_id = $1 AND definition_version = 1
                                """).execute(Tuple.of(definitionId)))
                .iterator()
                .next()
                .getString("document_digest");
    }

    /** Inserts a candidate document row of the given length with an explicit digest. */
    private static void insertOversizedRowWithDigest(
            DefinitionScenario scenario, String definitionId, int length, String digest) {
        scenario.execute("""
                INSERT INTO workflow_definitions
                    (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status,
                     document, document_format, document_digest, source_type, loaded_at)
                VALUES ('default', $1, 1, 'DOCUMENT', 'example.Contract', 'example.State', repeat('a', 64), 'CANDIDATE',
                        repeat('x', $2), 'JSON', $3, 'digest-test', now())
                """, Tuple.of(definitionId, length, digest));
    }
}
