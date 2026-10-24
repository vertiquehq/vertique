// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.dsl.ForkScope;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
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
 * Verifies that a definition row carries the signal names its version declares and the schema of its start payload,
 * written with the plan hash by a node that has schema production, and left null by a node that has none.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DefinitionRowSchemasIT {

    /** The contract of the fixture definitions; it declares every signal they wait for. */
    @WorkflowContract("row-schemas-shipment")
    public interface ShipmentContract {

        @WorkflowSignal("approve")
        Future<Void> approve(WorkflowInstanceId id, SignalNote payload, @SignalDedupKey String dedupKey);

        @WorkflowSignal("cancel")
        Future<Void> cancel(WorkflowInstanceId id, SignalNote payload, @SignalDedupKey String dedupKey);

        @WorkflowSignal("escalate")
        Future<Void> escalate(WorkflowInstanceId id, SignalNote payload, @SignalDedupKey String dedupKey);

        @WorkflowSignal("ack")
        Future<Void> ack(WorkflowInstanceId id, SignalNote payload, @SignalDedupKey String dedupKey);

        @WorkflowSignal("nack")
        Future<Void> nack(WorkflowInstanceId id, SignalNote payload, @SignalDedupKey String dedupKey);
    }

    /** The contract of the document fixture; it declares the one signal the document waits for. */
    @WorkflowContract("row-schemas-refund")
    public interface RefundContract {

        @WorkflowSignal("confirm")
        Future<Void> confirm(WorkflowInstanceId id, InventoryReservedDoc payload, @SignalDedupKey String dedupKey);
    }

    public record SignalNote(String note) {}

    public record ShipState(String id) {}

    public record ShipStart(String shipmentId) {}

    public record ShipStartChanged(String shipmentId, String note) {}

    /** The three columns of one definition row that this test reads. */
    private record RowColumns(String planHash, JsonObject signalNames, JsonObject startSchema) {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_row_schemas_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String SHIPMENT = "shipment";
    private static final String LEGACY = "legacy";
    private static final String REFUND = "refund";
    private static final Instant LOADED_AT = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    @DisplayName("a code row stores its signal names and start schema with its plan hash")
    void codeRowStoresSignalNamesAndStartSchemaWithItsPlanHash(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            database.ownRows(SHIPMENT);

            // Given a code definition that waits for approve and cancel, then forks split into branch b1 waiting
            // for ack

            // When the graph with the schema module starts and the startup reconciliation inserts the row
            Boot first = schemaGraph(database, vertx, new ShipmentCode(SHIPMENT, Shape.BASE));
            RowColumns inserted = rowColumns(database, WorkflowScopes.DEFAULT, SHIPMENT, 1);
            first.close();

            // Then the row holds the declared names and the schema of the start payload
            assertThat(inserted.signalNames()).isEqualTo(new JsonObject("""
                            {"instance":["approve","cancel"],"branches":{"split/b1":["ack"]}}"""));
            assertThat(propertyNames(inserted.startSchema())).containsExactlyInAnyOrder("shipmentId");

            // When the definition changes so that its plan hash differs, no instance is live, and the graph starts
            // again
            Boot second = schemaGraph(database, vertx, new ShipmentCode(SHIPMENT, Shape.CHANGED));
            RowColumns rehashed = rowColumns(database, WorkflowScopes.DEFAULT, SHIPMENT, 1);
            second.close();

            // Then the rehash wrote the changed plan's names and start schema with the new hash
            assertThat(rehashed.planHash()).isNotEqualTo(inserted.planHash());
            assertThat(rehashed.signalNames()).isEqualTo(new JsonObject("""
                            {"instance":["approve","cancel","escalate"],\
                            "branches":{"split/b1":["ack"],"split/b2":["nack"]}}"""));
            assertThat(propertyNames(rehashed.startSchema())).containsExactlyInAnyOrder("shipmentId", "note");
        }
    }

    @Test
    @DisplayName("document rows get them when loaded and pre-0.4.0 rows are filled at the next build")
    void documentRowsAndLegacyRowsGetSignalNamesAndStartSchema(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            database.ownRows(REFUND, LEGACY);

            // Given the graph with the schema module and a second code row whose two columns are null, as a 0.3.0 row
            Boot first = schemaGraph(database, vertx, new ShipmentCode(LEGACY, Shape.LEGACY));
            database.execute("""
                    UPDATE workflow_definitions SET signal_names_json = NULL, start_schema_json = NULL
                    WHERE scope_key = $1 AND definition_id = $2 AND definition_version = 1""", Tuple.of(WorkflowScopes.DEFAULT, LEGACY));
            RowColumns nulled = rowColumns(database, WorkflowScopes.DEFAULT, LEGACY, 1);
            assertThat(nulled.signalNames()).isNull();
            assertThat(nulled.startSchema()).isNull();

            // When the refund document, which waits for confirm, is loaded into the default scope
            await(first.app()
                    .definitionService()
                    .load(WorkflowScopes.DEFAULT, refundDocument(), DocumentFormat.YAML, source("refund", LOADED_AT)));
            RowColumns loaded = rowColumns(database, WorkflowScopes.DEFAULT, REFUND, 1);
            first.close();

            // Then the document row holds its names and a start schema immediately after the load
            assertThat(loaded.signalNames()).isEqualTo(new JsonObject("""
                            {"instance":["confirm"],"branches":{}}"""));
            assertThat(propertyNames(loaded.startSchema())).containsExactlyInAnyOrder("orderId");

            // When the graph restarts
            Boot second = schemaGraph(database, vertx, new ShipmentCode(LEGACY, Shape.LEGACY));
            RowColumns filled = rowColumns(database, WorkflowScopes.DEFAULT, LEGACY, 1);
            second.close();

            // Then the legacy row holds its names and schema and its plan hash is unchanged
            assertThat(filled.signalNames()).isEqualTo(new JsonObject("""
                            {"instance":["approve"],"branches":{}}"""));
            assertThat(propertyNames(filled.startSchema())).containsExactlyInAnyOrder("shipmentId");
            assertThat(filled.planHash()).isEqualTo(nulled.planHash());
        }
    }

    @Test
    @DisplayName("a node without the schema producer writes rows without the columns")
    void nodeWithoutTheSchemaProducerWritesRowsWithoutColumns(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            database.ownRows(SHIPMENT);

            // Given a graph that omits the schema module and registers the shipment definition
            // When the graph starts
            Boot node = graphWithoutSchemaModule(database, new ShipmentCode(SHIPMENT, Shape.BASE));
            RowColumns row = rowColumns(database, WorkflowScopes.DEFAULT, SHIPMENT, 1);
            node.close();

            // Then startup succeeded and the row has its plan hash and neither column
            assertThat(row.planHash()).matches("[0-9a-f]{64}");
            assertThat(row.signalNames()).isNull();
            assertThat(row.startSchema()).isNull();
        }
    }

    private static Boot schemaGraph(DefinitionScenario database, Vertx vertx, WorkflowDefinition<?, ?> code) {
        return database.nodeOf(pool ->
                SchemaDefinitionGraph.build(vertx, pool, DefinitionRowSchemasIT.class, List.of(code), List.of()));
    }

    private static Boot graphWithoutSchemaModule(DefinitionScenario database, WorkflowDefinition<?, ?> code) {
        return database.node(List::of, new BootObservation("row-schemas-unused", 1, "unused", false), List.of(code));
    }

    private static RowColumns rowColumns(DefinitionScenario database, String scope, String id, long version) {
        Row row = await(database.admin().preparedQuery("""
                                SELECT plan_hash, signal_names_json, start_schema_json FROM workflow_definitions
                                WHERE scope_key = $1 AND definition_id = $2 AND definition_version = $3""").execute(Tuple.of(scope, id, version)))
                .iterator()
                .next();
        return new RowColumns(
                row.getString("plan_hash"),
                row.getJsonObject("signal_names_json"),
                row.getJsonObject("start_schema_json"));
    }

    private static java.util.Set<String> propertyNames(JsonObject schema) {
        assertThat(schema).as("start schema").isNotNull();
        return schema.getJsonObject("properties").fieldNames();
    }

    private static byte[] refundDocument() {
        return ("""
                definitionId: refund
                definitionVersion: 1
                stateType: dev.vertique.workflow.definition.it.fixture.OrderDocState
                contract: %s
                startPayloadType: dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc
                initialStateMapper: doc.order.fromPlaceOrder
                initialStep: wait-confirm
                steps:
                  - id: wait-confirm
                    type: wait-signal
                    signal: confirm
                    payloadType: dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc
                    stateReducer: doc.order.applyInventoryReserved
                    next: done
                  - id: done
                    type: complete
                """.formatted(RefundContract.class.getName())).getBytes(StandardCharsets.UTF_8);
    }

    /** The plan variants of the code fixture. */
    private enum Shape {
        /** Waits for approve and cancel, then forks split into b1, which waits for ack. */
        BASE,
        /** Adds an escalate wait and a second branch b2 waiting for nack, and a second start property. */
        CHANGED,
        /** Waits for approve only. */
        LEGACY
    }

    private static final class ShipmentCode implements WorkflowDefinition<ShipState, ShipmentContract> {

        private final String id;
        private final Shape shape;

        private ShipmentCode(String id, Shape shape) {
            this.id = id;
            this.shape = shape;
        }

        @Override
        public Class<ShipmentContract> contract() {
            return ShipmentContract.class;
        }

        @Override
        public Class<ShipState> stateType() {
            return ShipState.class;
        }

        @Override
        public String definitionId() {
            return id;
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<ShipState> workflow) {
            WorkflowBuilder<ShipState> start = shape == Shape.CHANGED
                    ? workflow.init(ShipStartChanged.class, s -> new ShipState(s.shipmentId()))
                    : workflow.init(ShipStart.class, s -> new ShipState(s.shipmentId()));
            if (shape == Shape.LEGACY) {
                start.initialStep("await-approve")
                        .waitForSignal("await-approve", "approve", SignalNote.class)
                        .onSignal((state, note) -> state)
                        .toStepOnSignal("done")
                        .build()
                        .complete("done");
                return;
            }
            boolean changed = shape == Shape.CHANGED;
            start.initialStep("await-approve")
                    .waitForSignal("await-approve", "approve", SignalNote.class)
                    .onSignal((state, note) -> state)
                    .toStepOnSignal("await-cancel")
                    .build()
                    .waitForSignal("await-cancel", "cancel", SignalNote.class)
                    .onSignal((state, note) -> state)
                    .toStepOnSignal(changed ? "await-escalate" : "split")
                    .build();
            if (changed) {
                workflow.waitForSignal("await-escalate", "escalate", SignalNote.class)
                        .onSignal((state, note) -> state)
                        .toStepOnSignal("split")
                        .build();
            }
            ForkScope<ShipState> fork = workflow.fork("split");
            fork = fork.branch("b1", "await-ack", RaceSafety.IGNORE_LATE_RESULT_SAFE);
            if (changed) fork = fork.branch("b2", "await-nack", RaceSafety.IGNORE_LATE_RESULT_SAFE);
            fork.join("join");
            workflow.waitForSignal("await-ack", "ack", SignalNote.class)
                    .onSignal((state, note) -> state)
                    .toStepOnSignal("b1-done")
                    .build()
                    .complete("b1-done");
            if (changed) {
                workflow.waitForSignal("await-nack", "nack", SignalNote.class)
                        .onSignal((state, note) -> state)
                        .toStepOnSignal("b2-done")
                        .build()
                        .complete("b2-done");
            }
            workflow.join("join")
                    .allRequired((state, results) -> state)
                    .toStep("done")
                    .onFailure("done")
                    .endJoin()
                    .complete("done");
        }
    }
}
