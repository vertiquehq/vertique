// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.documentText;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.failureOf;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.CompiledDefinitionRef;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionFilter.TenantScope;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a tenant can load only definitions inside its own id namespace, while rows stored before the rule
 * keep their ownership collision at startup and every lifecycle operation.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DefinitionNamespaceIT {

    @WorkflowContract(definitionId = "namespace-load-doc", definitionVersion = 1)
    public interface UnprefixedV1 extends OrderDocumentSignals {}

    @WorkflowContract(definitionId = "t1:namespace-load-doc", definitionVersion = 1)
    public interface NamespacedV1 extends OrderDocumentSignals {}

    @WorkflowContract(definitionId = "namespace-squatter-doc", definitionVersion = 1)
    public interface SquatterV1 extends OrderDocumentSignals {}

    @WorkflowContract(definitionId = "namespace-legacy-candidate-doc", definitionVersion = 1)
    public interface LegacyCandidateV1 extends OrderDocumentSignals {}

    @WorkflowContract(definitionId = "namespace-legacy-active-doc", definitionVersion = 1)
    public interface LegacyActiveV1 extends OrderDocumentSignals {}

    @WorkflowContract(definitionId = "namespace-legacy-deprecated-doc", definitionVersion = 1)
    public interface LegacyDeprecatedV1 extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_namespace_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("a tenant load outside its namespace leaves no trace, and a namespaced load stores and activates")
    void namespacedTenantLoadStoresAndActivatesWhileRejectedLoadLeavesNoTrace(Vertx vertx) {
        String unprefixedId = "namespace-load-doc";
        String namespacedId = "t1:namespace-load-doc";
        DefinitionKey namespacedKey = new DefinitionKey("t1", namespacedId, 1);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(unprefixedId, namespacedId);

            // Given a started durable component and an empty definitions table
            Boot node = scenario.node(none(), unobserved());
            assertThat(scenario.rowCount(unprefixedId)).isZero();
            assertThat(scenario.rowCount(namespacedId)).isZero();

            // When tenant t1 loads a document whose id is not namespaced
            Throwable rejected = failureOf(node.app()
                    .definitionService()
                    .load(
                            documentText(unprefixedId, 1, UnprefixedV1.class).getBytes(StandardCharsets.UTF_8),
                            DocumentFormat.YAML,
                            source("unprefixed", Instant.parse("2026-10-19T12:00:00Z")),
                            "t1"));

            // Then it fails naming the tenant and the id, and no row or registration results
            assertThat(rejected).isInstanceOfSatisfying(WorkflowDefinitionException.class, failure -> assertThat(
                            failure.getMessage())
                    .contains("t1")
                    .contains(unprefixedId));
            assertThat(scenario.rowCount(unprefixedId)).isZero();
            assertThat(node.app().workflowRegistry().allRegistered())
                    .noneMatch(runtime -> runtime.plan().definitionId().equals(unprefixedId));

            // When tenant t1 loads the same document under its namespaced id and activates it
            CompiledDefinitionRef loaded = await(node.app()
                    .definitionService()
                    .load(
                            documentText(namespacedId, 1, NamespacedV1.class).getBytes(StandardCharsets.UTF_8),
                            DocumentFormat.YAML,
                            source("namespaced", Instant.parse("2026-10-19T12:00:00Z")),
                            "t1"));

            // Then a candidate owned by t1 is stored under the namespaced id
            assertThat(loaded.definitionId()).isEqualTo(namespacedId);
            assertThat(scenario.stored("t1", namespacedId, 1).status()).isEqualTo("CANDIDATE");
            assertThat(scenario.rowCount(namespacedId)).isEqualTo(1);
            await(node.app().definitionService().activate(namespacedKey));

            // And it is active and registered, and the tenant listing returns exactly that row
            assertThat(scenario.stored("t1", namespacedId, 1).status()).isEqualTo("ACTIVE");
            assertThat(node.app().workflowRegistry().resolvePinned(namespacedId, 1))
                    .isNotNull();
            List<WorkflowDefinitionRecord> listed =
                    await(node.app().definitionService().list(new DefinitionFilter(TenantScope.TENANT, "t1", null)));
            assertThat(listed).extracting(WorkflowDefinitionRecord::key).containsExactly(namespacedKey);
        }
    }

    @Test
    @DisplayName("a seeded squatter still collides with code at startup and legacy tenant rows keep their lifecycle")
    void seededSquatterStillCollidesAndLegacyTenantRowKeepsItsLifecycle(Vertx vertx) {
        String squatterId = "namespace-squatter-doc";
        String candidateId = "namespace-legacy-candidate-doc";
        String activeId = "namespace-legacy-active-doc";
        String deprecatedId = "namespace-legacy-deprecated-doc";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(squatterId, candidateId, activeId, deprecatedId);
            Instant loadedAt = Instant.parse("2026-10-19T12:00:00Z");

            // Given an ACTIVE tenant t1 row of an unprefixed id, as an older release could have stored it
            Boot seeder = scenario.node(none(), unobserved());
            TenantRowSeed.seed(
                    seeder.app(),
                    "t1",
                    documentText(squatterId, 1, SquatterV1.class).getBytes(StandardCharsets.UTF_8),
                    source("squatter", loadedAt),
                    DefinitionStatus.ACTIVE);
            seeder.close();
            assertThat(scenario.stored("t1", squatterId, 1).status()).isEqualTo("ACTIVE");

            // When the application starts with code that registers the same id globally
            Boot collided = scenario.attempt(
                    none(), unobserved(), List.of(new CodeWorkflow(squatterId, 1, SquatterV1.class)), null, 10);

            // Then startup fails naming both owners and stores no global row
            assertThat(collided.failure())
                    .isNotNull()
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.OWNER_CONFLICT);
                        assertThat(failure.key()).isEqualTo(new DefinitionKey(null, squatterId, 1));
                        assertThat(failure.getMessage()).contains("t1").containsIgnoringCase("global");
                    });
            collided.close();
            assertThat(scenario.findStored(squatterId, 1)).isEmpty();
            assertThat(scenario.stored("t1", squatterId, 1).status()).isEqualTo("ACTIVE");

            // Given legacy tenant rows of unprefixed ids in the states candidate, active and deprecated
            scenario.removeRows(squatterId);
            Boot legacySeeder = scenario.node(none(), unobserved());
            DefinitionKey candidateKey = TenantRowSeed.seed(
                    legacySeeder.app(),
                    "t1",
                    documentText(candidateId, 1, LegacyCandidateV1.class).getBytes(StandardCharsets.UTF_8),
                    source("legacy-candidate", loadedAt),
                    DefinitionStatus.CANDIDATE);
            DefinitionKey activeKey = TenantRowSeed.seed(
                    legacySeeder.app(),
                    "t1",
                    documentText(activeId, 1, LegacyActiveV1.class).getBytes(StandardCharsets.UTF_8),
                    source("legacy-active", loadedAt),
                    DefinitionStatus.ACTIVE);
            DefinitionKey deprecatedKey = TenantRowSeed.seed(
                    legacySeeder.app(),
                    "t1",
                    documentText(deprecatedId, 1, LegacyDeprecatedV1.class).getBytes(StandardCharsets.UTF_8),
                    source("legacy-deprecated", loadedAt),
                    DefinitionStatus.DEPRECATED);
            legacySeeder.close();
            assertThat(scenario.stored("t1", candidateId, 1).status()).isEqualTo("CANDIDATE");
            assertThat(scenario.stored("t1", activeId, 1).status()).isEqualTo("ACTIVE");
            assertThat(scenario.stored("t1", deprecatedId, 1).status()).isEqualTo("DEPRECATED");
            Boot node = scenario.node(none(), unobserved());
            long coolingSeconds = node.app().workflowDefinitionsConfig().maxStalenessSeconds();

            // When the candidate is activated
            await(node.app().definitionService().activate(candidateKey));

            // Then it is ACTIVE and registered
            assertThat(scenario.stored("t1", candidateId, 1).status()).isEqualTo("ACTIVE");
            assertThat(node.app().workflowRegistry().resolvePinned(candidateId, 1))
                    .isNotNull();

            // When the active row is deprecated
            await(node.app().definitionService().deprecate(activeKey));

            // Then it is DEPRECATED and stays pinnable
            assertThat(scenario.stored("t1", activeId, 1).status()).isEqualTo("DEPRECATED");
            assertThat(node.app().workflowRegistry().resolvePinned(activeId, 1)).isNotNull();

            // When the deprecated row is retired after its cooling window
            scenario.ageStatus(deprecatedKey, coolingSeconds + 60);
            await(node.app().definitionService().retire(deprecatedKey));

            // Then it is RETIRED
            assertThat(scenario.stored("t1", deprecatedId, 1).status()).isEqualTo("RETIRED");

            // When the retired row is reinstated
            await(node.app().definitionService().reinstate(deprecatedKey));

            // Then it is the only version of its owner, so it is ACTIVE and registered again
            assertThat(scenario.stored("t1", deprecatedId, 1).status()).isEqualTo("ACTIVE");
            assertThat(node.app().workflowRegistry().resolvePinned(deprecatedId, 1))
                    .isNotNull();
        }
    }

    private static WorkflowDefinitionSource none() {
        return List::of;
    }

    private static BootObservation unobserved() {
        return new BootObservation("namespace-unused", 1, "unused", false);
    }

    private record CodeState(String value) {}

    private record CodeStart(String value) {}

    private static final class CodeWorkflow implements WorkflowDefinition<CodeState, Object> {

        private final String definitionId;
        private final long version;
        private final Class<?> contract;

        private CodeWorkflow(String definitionId, long version, Class<?> contract) {
            this.definitionId = definitionId;
            this.version = version;
            this.contract = contract;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<Object> contract() {
            return (Class<Object>) contract;
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
