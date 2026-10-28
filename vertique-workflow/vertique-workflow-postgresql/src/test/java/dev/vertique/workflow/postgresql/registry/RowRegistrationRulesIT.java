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
import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.service.ActivationStatus;
import dev.vertique.workflow.definition.service.CompiledDefinitionRef;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.DefinitionFailure;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that the contract and state type rules of a scope's versions of an id are checked by the store from the
 * rows of the id, so that they hold on a node that has loaded none of the earlier versions, count the versions of the
 * same scope only, and leave retired versions out.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class RowRegistrationRulesIT {

    /** The contract the first versions of these tests name. */
    @WorkflowContract
    public interface ContractC extends OrderDocumentSignals {}

    /** The other contract, which a later version names to break the rule. */
    @WorkflowContract
    public interface ContractD extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_row_registration_rules_it")
            .withMigration("classpath:db/migration/workflow");

    private static final Instant LOADED_AT = Instant.parse("2026-10-27T12:00:00Z");

    @Test
    @DisplayName("a scope version naming another contract is refused from the rows and nothing is stored")
    void scopeVersionWithAnotherContractIsRefusedFromRows(Vertx vertx) {
        String id = "row-rules-contract-order";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given acme order v1 naming contract C, published, by a node that has since stopped
            Boot loader = scenario.node(none(), unobserved());
            loadYaml(loader, "acme", id, 1, ContractC.class);
            await(loader.app().definitionService().publish(new DefinitionKey("acme", id, 1)));
            assertThat(scenario.stored("acme", id, 1).status()).isEqualTo("AVAILABLE");
            loader.close();

            // And a freshly started node that has not loaded it
            Boot node = scenario.node(none(), unobserved());

            // When acme order v2 naming contract D is loaded
            Throwable refused = failureOf(yamlLoad(node, "acme", id, 2, ContractD.class));

            // Then the load fails naming acme, the id, version 2 and the contract rule
            assertThat(refused).isInstanceOf(WorkflowDefinitionException.class);
            assertThat(refused.getMessage())
                    .contains("'acme'")
                    .contains("'" + id + "'")
                    .contains("v2")
                    .contains("same contract");

            // And no row of version 2 exists, while version 1 is as it was
            assertThat(scenario.findStored("acme", id, 2)).isEmpty();
            assertThat(scenario.rowCount(id)).isEqualTo(1);
            assertThat(scenario.stored("acme", id, 1).status()).isEqualTo("AVAILABLE");

            // When version 2 is published
            Throwable missing = failureOf(node.app().definitionService().publish(new DefinitionKey("acme", id, 2)));

            // Then there is no definition to publish
            assertThat(missing).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, lifecycle -> {
                assertThat(lifecycle.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(lifecycle.getMessage()).contains("definition not found");
            });
        }
    }

    @Test
    @DisplayName("the state type rule counts the versions of the same scope and id and no other scope")
    void stateRuleAppliesPerScopeAndIdFromRows(Vertx vertx) {
        String id = "row-rules-state-order";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given acme order v1 with the state type of the document fixture, and globex order v1 written with
            // another state type, both under contract C, by a node that has since stopped
            Boot loader = scenario.node(none(), unobserved());
            loadYaml(loader, "acme", id, 1, ContractC.class);
            await(TenantRowSeed.insertCandidate(
                    loader.app(),
                    "globex",
                    documentBytes(id, 1, ContractC.class),
                    DocumentFormat.YAML,
                    source("globex-" + id + "-v1", LOADED_AT)));
            String acmeState = stateTypeOf(scenario, "acme", id, 1);
            assertThat(stateTypeOf(scenario, "globex", id, 1)).isNotEqualTo(acmeState);
            loader.close();
            Boot node = scenario.node(none(), unobserved());

            // When acme order v2 with the state type of globex's row is written as a candidate
            Throwable refused = failureOf(TenantRowSeed.insertCandidate(
                    node.app(),
                    "acme",
                    documentBytes(id, 2, ContractC.class),
                    DocumentFormat.YAML,
                    source("acme-" + id + "-v2-other-state", LOADED_AT)));

            // Then it fails naming acme, the id, version 2 and the state type rule, and stores nothing
            assertThat(refused).isInstanceOf(WorkflowDefinitionException.class);
            assertThat(refused.getMessage())
                    .contains("'acme'")
                    .contains("'" + id + "'")
                    .contains("v2")
                    .contains("same state type");
            assertThat(scenario.findStored("acme", id, 2)).isEmpty();
            assertThat(scenario.rowCount(id)).isEqualTo(2);

            // When acme order v2 with acme's own state type is loaded
            CompiledDefinitionRef loaded = loadYaml(node, "acme", id, 2, ContractC.class);

            // Then it is stored as a candidate, and globex's other state type did not count against it
            assertThat(loaded.definitionVersion()).isEqualTo(2);
            assertThat(loaded.status()).isEqualTo(ActivationStatus.CANDIDATE);
            assertThat(scenario.stored("acme", id, 2).status()).isEqualTo("CANDIDATE");
            assertThat(stateTypeOf(scenario, "acme", id, 2)).isEqualTo(acmeState);
            assertThat(scenario.rowCount(id)).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("a retired version takes no part in the rules and a candidate version does")
    void retiredRowsAreIgnoredAndCandidateRowsCount(Vertx vertx) {
        String retiredId = "row-rules-retired-order";
        String candidateId = "row-rules-candidate-invoice";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(retiredId, candidateId);

            // Given acme order v1 naming contract C, retired, and acme invoice v1 naming C, left a candidate
            Boot loader = scenario.node(none(), unobserved());
            loadYaml(loader, "acme", retiredId, 1, ContractC.class);
            retire(loader, new DefinitionKey("acme", retiredId, 1));
            loadYaml(loader, "acme", candidateId, 1, ContractC.class);
            assertThat(scenario.stored("acme", retiredId, 1).status()).isEqualTo("RETIRED");
            assertThat(scenario.stored("acme", candidateId, 1).status()).isEqualTo("CANDIDATE");
            loader.close();
            Boot node = scenario.node(none(), unobserved());

            // When acme order v2 naming contract D is loaded
            CompiledDefinitionRef loaded = loadYaml(node, "acme", retiredId, 2, ContractD.class);

            // Then it is stored as a candidate, because the retired version does not count
            assertThat(loaded.status()).isEqualTo(ActivationStatus.CANDIDATE);
            assertThat(scenario.stored("acme", retiredId, 2).status()).isEqualTo("CANDIDATE");

            // When acme invoice v2 naming contract D is loaded
            Throwable refused = failureOf(yamlLoad(node, "acme", candidateId, 2, ContractD.class));

            // Then the load fails naming acme, the id, version 2 and the contract rule, because the candidate counts
            assertThat(refused).isInstanceOf(WorkflowDefinitionException.class);
            assertThat(refused.getMessage())
                    .contains("'acme'")
                    .contains("'" + candidateId + "'")
                    .contains("v2")
                    .contains("same contract");
            assertThat(scenario.findStored("acme", candidateId, 2)).isEmpty();
            assertThat(scenario.rowCount(candidateId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName(
            "a document reconciled with another contract than the scope's versions is refused and nothing is stored")
    void documentReconcileChecksTheRulesFromRows(Vertx vertx) {
        String id = "row-rules-reconcile-order";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given acme order v1 naming contract C, left a candidate, on a node that has not registered it
            Boot loader = scenario.node(none(), unobserved());
            loadYaml(loader, "acme", id, 1, ContractC.class);
            loader.close();
            Boot node = scenario.node(none(), unobserved());

            // When acme order v2 naming contract D is reconciled as a packaged document
            Throwable refused = failureOf(reconcile(node, "acme", id, 2, ContractD.class));

            // Then the reconcile fails naming acme, the id, version 2 and the contract rule, and stores nothing
            assertThat(refused).isInstanceOf(WorkflowDefinitionException.class);
            assertThat(refused.getMessage())
                    .contains("'acme'")
                    .contains("'" + id + "'")
                    .contains("v2")
                    .contains("same contract");
            assertThat(scenario.findStored("acme", id, 2)).isEmpty();
            assertThat(scenario.rowCount(id)).isEqualTo(1);

            // When acme order v2 naming contract C is reconciled
            ReconcileOutcome outcome = await(reconcile(node, "acme", id, 2, ContractC.class));

            // Then it is inserted
            assertThat(outcome).isEqualTo(ReconcileOutcome.INSERTED);
            assertThat(scenario.rowCount(id)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("reinstating a retired version is refused when another version of the scope names another contract")
    void reinstatementChecksTheRulesFromRows(Vertx vertx) {
        String id = "row-rules-reinstate-order";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given acme order v1 naming contract C, retired, and acme order v2 naming contract D, available, which the
            // retirement allowed
            Boot node = scenario.node(none(), unobserved());
            loadYaml(node, "acme", id, 1, ContractC.class);
            retire(node, new DefinitionKey("acme", id, 1));
            loadYaml(node, "acme", id, 2, ContractD.class);
            await(node.app().definitionService().publish(new DefinitionKey("acme", id, 2)));

            // When version 1 is reinstated in the store
            Throwable refused = failureOf(node.app()
                    .definitionRegistryStore()
                    .transition(
                            new DefinitionKey("acme", id, 1), DefinitionStatus.RETIRED, DefinitionStatus.AVAILABLE));

            // Then it is refused naming the contract rule and stays retired
            assertThat(refused).isInstanceOf(WorkflowDefinitionException.class);
            assertThat(refused.getMessage()).contains("'acme'").contains("v1").contains("same contract");
            assertThat(scenario.stored("acme", id, 1).status()).isEqualTo("RETIRED");
        }
    }

    @Test
    @DisplayName("reinstating the only version of a scope is refused when the default scope holds another contract")
    void reinstatementChecksTheDefaultContractFromRows(Vertx vertx) {
        String id = "row-rules-reinstate-default";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given acme order v1 naming contract C, retired, and then a default-scope row of another contract
            Boot node = scenario.node(none(), unobserved());
            loadYaml(node, "acme", id, 1, ContractC.class);
            retire(node, new DefinitionKey("acme", id, 1));
            scenario.seedCode(id, 1, "1".repeat(64), "AVAILABLE");

            // When version 1 is reinstated in the store
            Throwable refused = failureOf(node.app()
                    .definitionRegistryStore()
                    .transition(
                            new DefinitionKey("acme", id, 1), DefinitionStatus.RETIRED, DefinitionStatus.AVAILABLE));

            // Then it is refused as a contract mismatch and stays retired
            assertThat(refused).isInstanceOf(WorkflowDefinitionContractMismatchException.class);
            assertThat(scenario.stored("acme", id, 1).status()).isEqualTo("RETIRED");
        }
    }

    @Test
    @DisplayName("a code version with another contract than the id's other versions is refused on insert and on rehash")
    void codeReconcileChecksTheRulesFromRows(Vertx vertx) {
        String id = "row-rules-code";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given default-scope code v1 of contract example.Contract and, from another deployment, v2 of the same
            Boot node = scenario.node(none(), unobserved());
            scenario.seedCode(id, 1, "1".repeat(64), "AVAILABLE");
            scenario.seedCode(id, 2, "2".repeat(64), "AVAILABLE");

            // When v3 of another contract is reconciled
            Throwable inserted = failureOf(node.app()
                    .definitionRegistryStore()
                    .upsertCode(codeRecord(id, 3, "3".repeat(64), "other.Contract"), false));

            // Then it is refused and nothing is stored
            assertThat(inserted).isInstanceOf(WorkflowDefinitionException.class);
            assertThat(inserted.getMessage()).contains("v3").contains("same contract");
            assertThat(scenario.findStored(id, 3)).isEmpty();

            // When v2 is reconciled with a new hash and another contract
            Throwable rehashed = failureOf(node.app()
                    .definitionRegistryStore()
                    .upsertCode(codeRecord(id, 2, "4".repeat(64), "other.Contract"), false));

            // Then it is refused and the row keeps its hash
            assertThat(rehashed).isInstanceOf(WorkflowDefinitionException.class);
            assertThat(scenario.stored(id, 2).planHash()).isEqualTo("2".repeat(64));
        }
    }

    @Test
    @DisplayName("a failing mark is not written to a retired row")
    void failingMarkIsNotWrittenToARetiredRow(Vertx vertx) {
        String id = "row-rules-mark-retired";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given a retired acme document
            Boot node = scenario.node(none(), unobserved());
            loadYaml(node, "acme", id, 1, ContractC.class);
            DefinitionKey key = new DefinitionKey("acme", id, 1);
            retire(node, key);

            // When a failing mark is recorded for it
            await(node.app()
                    .definitionRegistryStore()
                    .recordFailure(key, DefinitionFailure.of(DefinitionFailure.Rule.STATE_TYPE_NOT_EXTENDED)));

            // Then the row carries no mark
            assertThat(await(scenario.admin()
                                    .preparedQuery("SELECT failure_reason FROM workflow_definitions"
                                            + " WHERE scope_key = 'acme' AND definition_id = $1")
                                    .execute(Tuple.of(id)))
                            .iterator()
                            .next()
                            .getString("failure_reason"))
                    .isNull();
        }
    }

    private static WorkflowDefinitionRecord codeRecord(String id, long version, String hash, String contract) {
        Instant now = Instant.now();
        return new WorkflowDefinitionRecord(
                new DefinitionKey("default", id, version),
                DefinitionKind.CODE,
                contract,
                "example.State",
                hash,
                DefinitionStatus.AVAILABLE,
                new DefinitionProvenance("code", "memory:code", null, null, null, now),
                null,
                now,
                now,
                Map.of(),
                null);
    }

    /** Reconciles the version as a packaged document row of the scope through the registry store. */
    private static Future<ReconcileOutcome> reconcile(
            Boot node, String scope, String id, long version, Class<?> contract) {
        byte[] bytes = documentBytes(id, version, contract);
        SourceMetadata source = source(scope + "-" + id + "-v" + version, LOADED_AT);
        CompiledDefinition compiled =
                node.app().definitionPipeline().load(new DefinitionResource(bytes, DocumentFormat.YAML, source));
        DefinitionKey key = new DefinitionKey(scope, id, version);
        Instant now = Instant.now();
        WorkflowDefinitionRecord record = new WorkflowDefinitionRecord(
                key,
                DefinitionKind.DOCUMENT,
                compiled.contractName(),
                compiled.stateTypeName(),
                compiled.planHash(),
                DefinitionStatus.AVAILABLE,
                new DefinitionProvenance(
                        source.sourceType(),
                        source.uri(),
                        source.revision(),
                        source.author(),
                        source.uploadId(),
                        source.loadedAt()),
                DocumentFormat.YAML.name(),
                now,
                now,
                Map.of(),
                null);
        WorkflowDefinitionDocument text = new WorkflowDefinitionDocument(
                key, new String(bytes, StandardCharsets.UTF_8), DocumentFormat.YAML.name());
        return node.app().definitionRegistryStore().upsertDocument(record, text, false);
    }

    /** The state type name a row records. */
    private static String stateTypeOf(DefinitionScenario scenario, String scope, String id, long version) {
        return await(scenario.admin()
                        .preparedQuery("SELECT state_type_name FROM workflow_definitions"
                                + " WHERE scope_key = $1 AND definition_id = $2 AND definition_version = $3")
                        .execute(Tuple.of(scope, id, version)))
                .iterator()
                .next()
                .getString("state_type_name");
    }

    /** Publishes, deprecates and retires the version, which nothing pins. */
    private static void retire(Boot node, DefinitionKey key) {
        WorkflowDefinitionService service = node.app().definitionService();
        await(service.publish(key));
        await(service.deprecate(key));
        await(service.retire(key));
    }

    /** Loads the version through the definition service and waits for the stored candidate. */
    private static CompiledDefinitionRef loadYaml(Boot node, String scope, String id, long version, Class<?> contract) {
        return await(yamlLoad(node, scope, id, version, contract));
    }

    private static Future<CompiledDefinitionRef> yamlLoad(
            Boot node, String scope, String id, long version, Class<?> contract) {
        return node.app()
                .definitionService()
                .load(
                        scope,
                        documentBytes(id, version, contract),
                        DocumentFormat.YAML,
                        source(scope + "-" + id + "-v" + version, LOADED_AT));
    }

    private static byte[] documentBytes(String id, long version, Class<?> contract) {
        return DefinitionScenario.documentText(id, version, contract).getBytes(StandardCharsets.UTF_8);
    }

    private static WorkflowDefinitionSource none() {
        return List::of;
    }

    private static BootObservation unobserved() {
        return new BootObservation("row-rules-unused", 1, "unused", false);
    }
}
