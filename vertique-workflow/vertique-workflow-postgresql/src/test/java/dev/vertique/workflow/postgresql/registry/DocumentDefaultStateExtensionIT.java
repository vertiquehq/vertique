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
import dev.vertique.workflow.definition.it.fixture.OrderDocState;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.store.DefinitionFailure.Rule;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that the state type of a scope's document is compared with the state type of the default scope's current
 * row when that row is a document, which no registry holds: publication refuses a state type that does not extend it,
 * a load flags it, and a state type that is the default scope's passes.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DocumentDefaultStateExtensionIT {

    /** The one contract of the default scope's document row and of the scope's document. */
    @WorkflowContract
    public interface ExtensionContract extends OrderDocumentSignals {}

    /** A state type that the document state does not extend. */
    public record DefaultState(String value) {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_document_default_state_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String ACME = "acme";
    private static final Instant LOADED_AT = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    @DisplayName("publication refuses a state type that does not extend the default document's and leaves a candidate")
    void publicationRefusesAStateTypeThatDoesNotExtendTheDefaultDocument(Vertx vertx) {
        String id = "ext-publish";
        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given a default-scope document row whose state type the acme document does not extend, and its record
            seedDefaultDocument(scenario, id, DefaultState.class);
            WorkflowDefinitionService service = node(vertx, scenario).definitionService();
            await(service.load(ACME, document(id), DocumentFormat.YAML, source(id, LOADED_AT)));

            // When the acme candidate is published
            Throwable refusal = failureOf(service.publish(new DefinitionKey(ACME, id, 1)));

            // Then it is refused as a definition refusal and the row stays a candidate
            assertThat(refusal).isInstanceOf(WorkflowDefinitionException.class);
            assertThat(refusal.getMessage()).contains("state type");
            assertThat(scenario.stored(ACME, id, 1).status()).isEqualTo("CANDIDATE");
        }
    }

    @Test
    @DisplayName("a state type that is the default document's state type publishes")
    void sameStateTypePublishes(Vertx vertx) {
        String id = "ext-same";
        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given a default-scope document row with the state type of the acme document
            seedDefaultDocument(scenario, id, OrderDocState.class);
            WorkflowDefinitionService service = node(vertx, scenario).definitionService();
            await(service.load(ACME, document(id), DocumentFormat.YAML, source(id, LOADED_AT)));

            // When the acme candidate is published
            await(service.publish(new DefinitionKey(ACME, id, 1)));

            // Then it is available
            assertThat(scenario.stored(ACME, id, 1).status()).isEqualTo("AVAILABLE");
        }
    }

    @Test
    @DisplayName("a load flags a state type that does not extend the default document's state type")
    void loadFlagsAStateTypeThatDoesNotExtendTheDefaultDocument(Vertx vertx) {
        String id = "ext-load";
        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given an available acme document published before the default scope held the id, and then a
            // default-scope document row whose state type it does not extend
            PublicationNodeGraph.Node node = node(vertx, scenario);
            await(node.definitionService().load(ACME, document(id), DocumentFormat.YAML, source(id, LOADED_AT)));
            DefinitionKey key = new DefinitionKey(ACME, id, 1);
            await(node.definitionService().publish(key));
            seedDefaultDocument(scenario, id, DefaultState.class);

            // When the plan cache loads the plan
            await(node.planCache().load(key));

            // Then its evaluation carries the state rule
            assertThat(node.planCache().evaluation(key).orElseThrow().failure())
                    .hasValueSatisfying(failure -> assertThat(failure.rule()).isEqualTo(Rule.STATE_TYPE_NOT_EXTENDED));
        }
    }

    private static PublicationNodeGraph.Node node(Vertx vertx, DefinitionScenario scenario) {
        return (PublicationNodeGraph.Node) scenario.nodeOf(pool -> PublicationNodeGraph.node(
                        vertx, pool, DocumentDefaultStateExtensionIT.class, List.of(), new RecordingCacheObserver()))
                .app();
    }

    /** Inserts an available default-scope document row of the id with the state type, and the default's record. */
    private static void seedDefaultDocument(DefinitionScenario scenario, String id, Class<?> stateType) {
        scenario.seedDocument("default", id, 1, "0".repeat(64), "AVAILABLE", "definitionId: " + id + "\n");
        scenario.execute(
                "UPDATE workflow_definitions SET contract_name = $1, state_type_name = $2"
                        + " WHERE scope_key = 'default' AND definition_id = $3",
                Tuple.of(ExtensionContract.class.getName(), stateType.getName(), id));
        scenario.execute(
                "INSERT INTO workflow_definition_activations"
                        + " (scope_key, definition_id, target_scope_key, definition_version, activated_at)"
                        + " VALUES ('default', $1, 'default', 1, now())",
                Tuple.of(id));
    }

    private static byte[] document(String id) {
        return """
                definitionId: %s
                definitionVersion: 1
                stateType: %s
                contract: %s
                startPayloadType: dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc
                initialStateMapper: doc.order.fromPlaceOrder
                initialStep: wait-paid
                steps:
                  - id: wait-paid
                    type: wait-signal
                    signal: inventory.reserved
                    payloadType: dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc
                    stateReducer: doc.order.applyInventoryReserved
                    next: done
                  - id: done
                    type: complete
                """.formatted(id, OrderDocState.class.getName(), ExtensionContract.class.getName())
                .getBytes(StandardCharsets.UTF_8);
    }
}
