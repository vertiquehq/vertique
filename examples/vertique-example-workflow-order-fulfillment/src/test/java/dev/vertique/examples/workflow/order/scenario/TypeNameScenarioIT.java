// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import static dev.vertique.examples.workflow.order.scenario.OrderScenarios.order;
import static dev.vertique.examples.workflow.order.scenario.PreAnnotationSeed.DEFINITION_ID;
import static dev.vertique.examples.workflow.order.scenario.ScenarioNode.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.examples.workflow.order.OrderFulfillmentWorkflow;
import dev.vertique.examples.workflow.order.scenario.PreAnnotationSeed.Seeded;
import dev.vertique.examples.workflow.order.scenario.moved.MovedReviewedOrderDefinition;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Tuple;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A state class that gains a logical name, and then moves to another package, keeps the plan hash of the version that
 * was recorded before it had one: the version resumes its instances, a version published after the annotation hashes
 * over the logical name and survives the move, and a version still bound by binary name stops the move at startup.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TypeNameScenarioIT {

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("scenario_type_name_it");

    static final PostgresContainer otherDb = new PostgresContainer().withDatabaseName("scenario_type_name_other_it");

    private static final String DEFAULT = WorkflowScopes.DEFAULT;

    @BeforeAll
    static void migrate() {
        ScenarioNode.migrate(db);
        ScenarioNode.migrate(otherDb);
    }

    @Test
    @DisplayName("annotating and moving a state type keeps the bound hashes")
    void annotatingAndMovingAStateTypeKeepsBoundHashes(Vertx vertx) {
        // Given version 1 recorded before the state class had a logical name, with an instance at its review task
        Seeded seeded = seedPreAnnotationV1(db);

        // When the component starts with the annotated state class and versions 1 and 2
        WorkflowInstanceId versionTwoInstance = restartAnnotated(vertx, seeded);

        // And version 1 is retired after its instances finished, and the component restarts with the moved class
        restartMoved(vertx, versionTwoInstance);

        // And on another database, whose version 1 is not retired, the component restarts with the moved class
        Seeded other = seedPreAnnotationV1(otherDb);
        try (ScenarioNode annotated = annotatedNode(vertx, otherDb, 1, 2)) {
            assertEquals(other.planHash(), annotated.storedHash(DEFAULT, DEFINITION_ID, 1));
        }
        try (ScenarioNode refused = ScenarioNode.on(vertx, otherDb)
                .withoutGeneratedCatalog()
                .types(ScenarioCatalogs.movedOrder())
                .code(new MovedReviewedOrderDefinition(1), new MovedReviewedOrderDefinition(2))
                .attempt()) {
            // Then startup fails naming the scope, the definition and the version
            Throwable failure = refused.failure();
            assertNotNull(failure, "the move must stop startup while version 1 is bound by binary name");
            WorkflowUnknownTypeNameException unknown = causeOf(failure);
            String message = unknown.getMessage();
            assertTrue(message.contains("default") && message.contains(DEFINITION_ID), message);
            assertTrue(message.matches("(?s).*\\b1\\b.*"), message);
        }
    }

    /** Writes the rows of version 1 as the release without logical names left them. */
    private static Seeded seedPreAnnotationV1(PostgresContainer database) {
        return PreAnnotationSeed.write(database);
    }

    /** Starts with the annotated state class; checks both hashes, resumes version 1 and starts a version 2 instance. */
    private static WorkflowInstanceId restartAnnotated(Vertx vertx, Seeded seeded) {
        try (ScenarioNode node = annotatedNode(vertx, db, 1, 2)) {
            // Then version 1 keeps the hash it was recorded with, which is not the hash over the logical name
            assertEquals(seeded.planHash(), node.storedHash(DEFAULT, DEFINITION_ID, 1));
            assertNotEquals(PreAnnotationSeed.planHash(1, "order"), seeded.planHash());

            // And version 2, first recorded now, has the hash built with the logical name
            assertEquals(PreAnnotationSeed.planHash(2, "order"), node.storedHash(DEFAULT, DEFINITION_ID, 2));

            // And version 1's instance resumes and its stored decision completes it
            decide(node, new WorkflowInstanceId(seeded.instance()));
            assertEquals("COMPLETED", node.status(new WorkflowInstanceId(seeded.instance())));

            // And a new order starts on version 2, the current version
            OrderFulfillmentWorkflow client =
                    node.graph().workflowClientFactory().create(OrderFulfillmentWorkflow.class, DEFINITION_ID);
            WorkflowInstanceId versionTwoInstance = await(client.start(order("order-after-annotation")));
            assertEquals(2L, node.pin(versionTwoInstance).version());

            // And version 1, with no instance left, can be deprecated and retired
            DefinitionKey versionOne = new DefinitionKey(DEFAULT, DEFINITION_ID, 1);
            await(node.graph().definitionService().deprecate(versionOne));
            await(node.pool().preparedQuery("""
                            UPDATE workflow_definitions SET status_changed_at = now() - interval '1 hour'
                            WHERE scope_key = 'default' AND definition_id = $1 AND definition_version = 1
                            """).execute(Tuple.of(DEFINITION_ID)));
            await(node.graph().definitionService().retire(versionOne));
            assertEquals("RETIRED", node.definitionStatus(DEFAULT, DEFINITION_ID, 1));
            return versionTwoInstance;
        }
    }

    /** Starts with the state class in another package and only version 2; version 2 keeps its hash and its instance. */
    private static void restartMoved(Vertx vertx, WorkflowInstanceId versionTwoInstance) {
        String hashBeforeMove;
        try (ScenarioNode before = annotatedNode(vertx, db, 2)) {
            hashBeforeMove = before.storedHash(DEFAULT, DEFINITION_ID, 2);
        }
        try (ScenarioNode node = ScenarioNode.on(vertx, db)
                .withoutGeneratedCatalog()
                .types(ScenarioCatalogs.movedOrder())
                .code(new MovedReviewedOrderDefinition(2))
                .start()) {
            // Then version 2's stored hash is unchanged and equals the hash the moved class builds
            assertEquals(hashBeforeMove, node.storedHash(DEFAULT, DEFINITION_ID, 2));
            assertEquals(PreAnnotationSeed.planHash(2, "order"), hashBeforeMove);

            // And its instance resumes and its stored decision completes it
            decide(node, versionTwoInstance);
            assertEquals("COMPLETED", node.status(versionTwoInstance));
        }
    }

    private static ScenarioNode annotatedNode(Vertx vertx, PostgresContainer database, long... versions) {
        ReviewedOrderDefinition[] definitions = new ReviewedOrderDefinition[versions.length];
        for (int index = 0; index < versions.length; index++) {
            definitions[index] = new ReviewedOrderDefinition(versions[index]);
        }
        return ScenarioNode.on(vertx, database)
                .withoutGeneratedCatalog()
                .types(ScenarioCatalogs.annotatedOrder())
                .code(definitions)
                .start();
    }

    /** Completes the instance's open review task with an approval. */
    private static void decide(ScenarioNode node, WorkflowInstanceId instance) {
        TaskCompletionCommand approve = new TaskCompletionCommand(
                node.openTask(instance),
                "approve",
                new OrderApproval("alice"),
                "approve-" + instance.value(),
                new WorkflowActor.User("alice"),
                null);
        await(node.graph().taskService().complete(approve));
    }

    private static WorkflowUnknownTypeNameException causeOf(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof WorkflowUnknownTypeNameException unknown) return unknown;
        }
        throw new AssertionError("Expected an unknown type name failure in the cause chain", failure);
    }
}
