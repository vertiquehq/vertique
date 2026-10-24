// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.documentText;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;

import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Documents, starts and database readers shared by the activation proofs. The test class that builds the graph owns
 * the contract interface, so every helper here takes it and none builds a graph.
 */
final class ActivationFixtures {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    static final String DEFAULT = WorkflowScopes.DEFAULT;

    private static final Instant LOADED_AT = Instant.parse("2026-10-21T12:00:00Z");

    private ActivationFixtures() {}

    /** The instance a start created, as the start scope and the exact definition it is pinned to. */
    record Pin(String startScope, String definitionScope, String definitionId, long version) {}

    /** A document that completes as soon as it starts. */
    static byte[] document(String id, long version, Class<?> contract) {
        return completingGraph(documentText(id, version, contract), "done");
    }

    /** The same document with another step graph, so its plan hash differs from {@link #document}. */
    static byte[] otherGraphDocument(String id, long version, Class<?> contract) {
        return completingGraph(documentText(id, version, contract), "finished");
    }

    private static byte[] completingGraph(String yaml, String stepId) {
        int steps = yaml.indexOf("initialStep:");
        if (steps < 0) throw new IllegalStateException("The workflow fixture has no initial step");
        return (yaml.substring(0, steps) + "initialStep: " + stepId + "\nsteps:\n  - id: " + stepId
                        + "\n    type: complete\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Runs an operation that may throw at the call or fail its future, and returns the failure or null. */
    static Throwable failureOf(Supplier<Future<?>> operation) {
        try {
            return DefinitionScenario.failureOf(operation.get());
        } catch (RuntimeException thrown) {
            return thrown;
        }
    }

    /** Removes the activation records of the ids, which must go before the definition rows they reference. */
    static void clearActivations(Pool admin, String... definitionIds) {
        for (String id : definitionIds) {
            await(admin.preparedQuery("DELETE FROM workflow_definition_activations WHERE definition_id = $1")
                    .execute(Tuple.of(id)));
        }
    }

    /** One node of a scenario with the helpers the activation proofs read top to bottom. */
    static final class Scenario {
        private final DefinitionScenario scenario;
        private final Boot node;
        private final Class<?> contract;

        Scenario(DefinitionScenario scenario, Boot node, Class<?> contract) {
            this.scenario = scenario;
            this.node = node;
            this.contract = contract;
        }

        WorkflowDefinitionService service() {
            return node.app().definitionService();
        }

        AppComponent app() {
            return node.app();
        }

        DefinitionScenario database() {
            return scenario;
        }

        /** Owns the ids: their rows and records are cleared now and again when the scenario closes. */
        void own(String... definitionIds) {
            clearActivations(scenario.admin(), definitionIds);
            scenario.ownRows(definitionIds);
            scenario.onClose(() -> clearActivations(scenario.admin(), definitionIds));
        }

        /** Loads a document of the scope as a candidate. */
        void load(String scopeKey, String id, long version) {
            await(service()
                    .load(
                            scopeKey,
                            document(id, version, contract),
                            DocumentFormat.YAML,
                            source(scopeKey + "-" + id + "-" + version, LOADED_AT)));
        }

        /** Loads a document of the scope with another step graph than {@link #load} and publishes it. */
        void publishOwnGraph(String scopeKey, String id, long version) {
            await(service()
                    .load(
                            scopeKey,
                            otherGraphDocument(id, version, contract),
                            DocumentFormat.YAML,
                            source(scopeKey + "-own-" + id + "-" + version, LOADED_AT)));
            await(service().publish(new DefinitionKey(scopeKey, id, version)));
        }

        /** Loads and publishes the versions of the scope. */
        void publish(String scopeKey, String id, long... versions) {
            for (long version : versions) {
                load(scopeKey, id, version);
                await(service().publish(new DefinitionKey(scopeKey, id, version)));
            }
        }

        /** Starts the id in the start scope without a version and returns where the instance is pinned. */
        Pin start(String startScope, String id) {
            WorkflowInstanceId instance = await(node.app().workflowOperations().start(command(startScope, id)));
            await(node.app().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));
            return pin(instance);
        }

        /** Starts the id in the start scope without a version and returns the failure. */
        Throwable startFailure(String startScope, String id) {
            return failureOf(() -> node.app().workflowOperations().start(command(startScope, id)));
        }

        /** Starts the id in the start scope on the explicit version of the default scope and returns the pin. */
        Pin startVersion(String startScope, String id, long version) {
            String key = "start-" + UUID.randomUUID();
            WorkflowInstanceId instance = await(node.app()
                    .workflowOperations()
                    .start(new StartCommand(
                            id,
                            contract.getName(),
                            new PlaceOrderDoc("order-" + key),
                            key,
                            null,
                            null,
                            version,
                            startScope,
                            null)));
            await(node.app().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));
            return pin(instance);
        }

        private StartCommand command(String startScope, String id) {
            String key = "start-" + UUID.randomUUID();
            return new StartCommand(
                    id, contract.getName(), new PlaceOrderDoc("order-" + key), key, null, null, null, startScope, null);
        }

        private Pin pin(WorkflowInstanceId instance) {
            Row row = await(scenario.admin().preparedQuery("""
                            SELECT scope_key, definition_scope_key, definition_id, definition_version
                            FROM workflow_instances WHERE id = $1
                            """).execute(Tuple.of(instance.value())))
                    .iterator()
                    .next();
            return new Pin(
                    row.getString("scope_key"),
                    row.getString("definition_scope_key"),
                    row.getString("definition_id"),
                    row.getLong("definition_version"));
        }

        /** The records of the id that the service lists for the scope, or for every scope when null. */
        List<DefinitionActivation> activations(String scopeKey, String id) {
            return await(service().activations(scopeKey)).stream()
                    .filter(activation -> activation.definitionId().equals(id))
                    .toList();
        }

        /** The status of every row the scope owns for the id, by version. */
        Map<Long, String> statuses(String scopeKey, String id) {
            Map<Long, String> statuses = new TreeMap<>();
            for (Row row : await(scenario.admin().preparedQuery("""
                            SELECT definition_version, status FROM workflow_definitions
                            WHERE scope_key = $1 AND definition_id = $2
                            """).execute(Tuple.of(scopeKey, id)))) {
                statuses.put(row.getLong("definition_version"), row.getString("status"));
            }
            return statuses;
        }
    }
}
