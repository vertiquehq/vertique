// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.failureOf;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParseException;
import dev.vertique.workflow.definition.service.ActivationStatus;
import dev.vertique.workflow.definition.service.CompiledDefinitionRef;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Stored;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a document is loaded into the scope the caller names and only that scope: the call argument is the
 * only source of the scope, a document cannot name one, and each scope numbers its own versions of an id.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class ScopedDefinitionLoadIT {

    /** The one contract that the code definitions and every scoped document of these tests name. */
    @WorkflowContract
    public interface ScopedPurchaseApprovalV1 extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_scoped_definition_load_it")
            .withMigration("classpath:db/migration/workflow");

    private static final Instant LOADED_AT = Instant.parse("2026-10-21T12:00:00Z");

    @Test
    @DisplayName("the scope of a load is the call argument, never the id or the content of the document")
    void loadScopeIsTheCallArgumentNeverTheContent(Vertx vertx) {
        String prefixedId = "globex:purchase-approval";
        String scopeFieldId = "purchase-approval-scope-field";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(prefixedId, scopeFieldId);

            // Given a started durable component, a document whose id looks like another scope's prefix, and a
            // document that carries a scope field
            Boot node = scenario.node(none(), unobserved());
            WorkflowDefinitionService service = node.app().definitionService();
            byte[] prefixed = document("prefixed-purchase-approval.workflow.yaml");
            byte[] withScopeField = document("purchase-approval-with-scope-field.workflow.yaml");

            // When the first document is loaded with scope acme
            CompiledDefinitionRef loaded =
                    await(service.load("acme", prefixed, DocumentFormat.YAML, source("prefixed", LOADED_AT)));

            // Then a candidate is stored in acme under the id as written, and no row exists in globex
            assertThat(loaded.definitionId()).isEqualTo(prefixedId);
            assertThat(loaded.status()).isEqualTo(ActivationStatus.CANDIDATE);
            Stored row = scenario.stored("acme", prefixedId, 1);
            assertThat(row.kind()).isEqualTo("DOCUMENT");
            assertThat(row.status()).isEqualTo("CANDIDATE");
            assertThat(scenario.findStored("globex", prefixedId, 1)).isEmpty();
            assertThat(scenario.findStored(WorkflowScopes.DEFAULT, prefixedId, 1))
                    .isEmpty();
            assertThat(scenario.rowCount(prefixedId)).isEqualTo(1);

            // When the second document, which names globex in a scope field, is loaded with scope acme
            Throwable rejected = failureOf(
                    service.load("acme", withScopeField, DocumentFormat.YAML, source("scope-field", LOADED_AT)));

            // Then it fails as a parse failure and stores nothing in any scope
            assertThat(rejected).isInstanceOf(WorkflowDefinitionParseException.class);
            assertThat(scenario.rowCount(scopeFieldId)).isZero();

            // Then the durable component holds no registration for the loaded candidate
            assertThat(node.app().workflowRegistry().allRegistered())
                    .noneMatch(runtime -> runtime.plan().definitionId().equals(prefixedId));
        }
    }

    @Test
    @DisplayName("each scope numbers its own versions of an id and a load never touches another scope's rows")
    void scopeOwnedVersionsAreNumberedPerScope(Vertx vertx) {
        String id = "purchase-approval";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given a started component whose code registers purchase-approval v1 to v3 in the default scope
            Boot first = scenario.node(none(), unobserved(), code(3));
            for (long version = 1; version <= 3; version++) {
                assertThat(scenario.stored(id, version).kind()).isEqualTo("CODE");
            }
            List<Stored> defaultBefore = defaultRows(scenario, id, 3);

            // When an acme document purchase-approval v1, with no prefix, is loaded with scope acme
            CompiledDefinitionRef loaded = await(first.app()
                    .definitionService()
                    .load(
                            "acme",
                            document("purchase-approval-v1.workflow.yaml"),
                            DocumentFormat.YAML,
                            source("acme-v1", LOADED_AT)));

            // Then it is acme's own version 1 beside the default scope's versions 1 to 3
            assertThat(loaded.definitionId()).isEqualTo(id);
            assertThat(loaded.definitionVersion()).isEqualTo(1);
            Stored acmeFirst = scenario.stored("acme", id, 1);
            assertThat(acmeFirst.kind()).isEqualTo("DOCUMENT");
            assertThat(acmeFirst.status()).isEqualTo("CANDIDATE");

            // And no default-scope row changed
            assertThat(defaultRows(scenario, id, 3)).isEqualTo(defaultBefore);
            first.close();

            // When the component restarts with code version 4 added
            Boot second = scenario.node(none(), unobserved(), code(4));

            // Then the default scope holds code versions 1 to 4 and acme's version 1 is unchanged
            for (long version = 1; version <= 4; version++) {
                assertThat(scenario.stored(id, version).kind()).isEqualTo("CODE");
            }
            assertThat(scenario.stored("acme", id, 1)).isEqualTo(acmeFirst);
            List<Stored> defaultAfterRestart = defaultRows(scenario, id, 4);

            // When an acme document purchase-approval v2 is loaded
            await(second.app()
                    .definitionService()
                    .load(
                            "acme",
                            document("purchase-approval-v2.workflow.yaml"),
                            DocumentFormat.YAML,
                            source("acme-v2", LOADED_AT)));

            // Then acme holds its versions 1 and 2 as candidates, and neither load refused the id
            assertThat(scenario.stored("acme", id, 1)).isEqualTo(acmeFirst);
            Stored acmeSecond = scenario.stored("acme", id, 2);
            assertThat(acmeSecond.kind()).isEqualTo("DOCUMENT");
            assertThat(acmeSecond.status()).isEqualTo("CANDIDATE");
            assertThat(scenario.rowCount(id)).isEqualTo(6);

            // And the default scope's rows are exactly as the restart left them
            assertThat(defaultRows(scenario, id, 4)).isEqualTo(defaultAfterRestart);
            assertThat(scenario.findStored("acme", id, 3)).isEmpty();
        }
    }

    private static List<Stored> defaultRows(DefinitionScenario scenario, String id, long versions) {
        return LongStream.rangeClosed(1, versions)
                .mapToObj(version -> scenario.stored(id, version))
                .toList();
    }

    private static DefinitionKey key(String id, String scopeKey, long version) {
        return new DefinitionKey(scopeKey, id, version);
    }

    private static List<WorkflowDefinition<?, ?>> code(long versions) {
        return LongStream.rangeClosed(1, versions)
                .<WorkflowDefinition<?, ?>>mapToObj(version -> new CodeWorkflow("purchase-approval", version))
                .toList();
    }

    private static byte[] document(String name) {
        try (InputStream input =
                Thread.currentThread().getContextClassLoader().getResourceAsStream("definitions/it/scoped/" + name)) {
            if (input == null) {
                throw new IllegalStateException("The scoped document " + name + " is not on the classpath");
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the scoped document " + name, e);
        }
    }

    private static WorkflowDefinitionSource none() {
        return List::of;
    }

    private static BootObservation unobserved() {
        return new BootObservation("scoped-load-unused", 1, "unused", false);
    }

    private record CodeState(String value) {}

    private record CodeStart(String value) {}

    private static final class CodeWorkflow implements WorkflowDefinition<CodeState, Object> {

        private final String definitionId;
        private final long version;

        private CodeWorkflow(String definitionId, long version) {
            this.definitionId = definitionId;
            this.version = version;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<Object> contract() {
            return (Class<Object>) (Class<?>) ScopedPurchaseApprovalV1.class;
        }

        @Override
        public Class<CodeState> stateType() {
            return CodeState.class;
        }

        @Override
        public String definitionId() {
            return definitionId;
        }

        @Override
        public long definitionVersion() {
            return version;
        }

        @Override
        public void define(WorkflowBuilder<CodeState> workflow) {
            workflow.init(CodeStart.class, start -> new CodeState(start.value()))
                    .initialStep("done")
                    .complete("done");
        }
    }
}
