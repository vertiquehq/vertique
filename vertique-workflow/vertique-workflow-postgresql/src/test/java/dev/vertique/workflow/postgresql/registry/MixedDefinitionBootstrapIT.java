// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.documentText;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.failureOf;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.db.exception.PessimisticLockingFailureException;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowContractMetadata;
import dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.ActivationStatus;
import dev.vertique.workflow.definition.service.CompiledDefinitionRef;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowPersistenceException;
import dev.vertique.workflow.exception.WorkflowProxyContractException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.PendingBoot;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Stored;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Verifies that packaged static documents are reconciled against the durable registry before traffic starts. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class MixedDefinitionBootstrapIT {

    @WorkflowContract(definitionId = "mixed-bootstrap-text-drift-doc", definitionVersion = 1)
    public interface TextDriftCaseV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-code-order", definitionVersion = 1)
    public interface OrderCodeV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-code-order", definitionVersion = 2)
    public interface OrderCodeV2 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-rehash-pinned", definitionVersion = 1)
    public interface PinnedRehashCodeV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-rehash-free", definitionVersion = 1)
    public interface FreeRehashCodeV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-static-equal", definitionVersion = 1)
    public interface StaticEqualV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-static-drift", definitionVersion = 1)
    public interface StaticDriftV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-static-kind", definitionVersion = 1)
    public interface StaticKindV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-static-held", definitionVersion = 1)
    public interface StaticHeldV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-code-held", definitionVersion = 1)
    public interface CodeHeldV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-code-held", definitionVersion = 2)
    public interface CodeHeldV2 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-stale-doc", definitionVersion = 1)
    public interface StaleDocV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-stale-doc", definitionVersion = 2)
    public interface StaleDocV2 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-stale-doc", definitionVersion = 3)
    public interface StaleDocV3 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-stale-claimed-doc", definitionVersion = 1)
    public interface StaleClaimedV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-stale-near-doc", definitionVersion = 1)
    public interface StaleNearV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-stale-code", definitionVersion = 1)
    public interface StaleCodeV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-tenant-vs-code", definitionVersion = 1)
    public interface TenantVsCodeV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-rolling-rehash", definitionVersion = 1)
    public interface RollingV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-rolling-rehash", definitionVersion = 2)
    public interface RollingV2 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-timeout-code", definitionVersion = 1)
    public interface TimeoutCodeV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-tenant-active", definitionVersion = 1)
    public interface TenantActiveV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-tenant-superseded", definitionVersion = 1)
    public interface TenantSupersededV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-tenant-superseded", definitionVersion = 2)
    public interface TenantSupersededV2 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-tenant-deprecated", definitionVersion = 1)
    public interface TenantDeprecatedV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-tenant-deprecated", definitionVersion = 2)
    public interface TenantDeprecatedV2 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-tenant-shared", definitionVersion = 1)
    public interface TenantSharedV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-tenant-candidate", definitionVersion = 1)
    public interface TenantCandidateV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-packaged-deprecated", definitionVersion = 1)
    public interface PackagedDeprecatedV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-packaged-deprecated", definitionVersion = 2)
    public interface PackagedDeprecatedV2 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-rehash-isolation", definitionVersion = 1)
    public interface RehashIsolationCodeV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-retired-code", definitionVersion = 1)
    public interface RetiredCodeV1 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-retired-code", definitionVersion = 2)
    public interface RetiredCodeV2 {}

    @WorkflowContract(definitionId = "mixed-bootstrap-key-rules\u0007id", definitionVersion = 7)
    public interface KeyRulesCodeV7 {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_mixed_bootstrap_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName(
            "a packaged document with the same plan hash but different text fails startup instead of being ignored")
    void treatsSameHashDifferentTextAsDrift(Vertx vertx) {
        String id = "mixed-bootstrap-text-drift-doc";
        DefinitionKey key = new DefinitionKey(null, id, 1);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given a packaged static document that was registered at startup and is now the active version
            String original = documentText(id, 1, TextDriftCaseV1.class);
            Boot first = scenario.node(
                    packaged(original, "first-deploy", Instant.parse("2026-10-18T12:00:00Z")),
                    new BootObservation(id, 1, "unused", false));
            Stored deployed = scenario.stored(id, 1);
            assertThat(deployed.kind()).isEqualTo("DOCUMENT");
            assertThat(deployed.status()).isEqualTo("ACTIVE");
            assertThat(deployed.document()).isEqualTo(original);
            String planHash = deployed.planHash();

            // And later deploys of the same id and version that keep the plan hash but change the document text
            Map<String, String> changed = new LinkedHashMap<>();
            changed.put(
                    "a different mapping on the signal step",
                    original.replace(
                            "stateReducer: doc.order.applyInventoryReserved\n",
                            "stateReducer: doc.order.applyInventoryReservedDrift\n"));
            changed.put("a comment added at the end", original + "# reviewed by the platform team\n");
            changed.put(
                    "whitespace added around a line",
                    original.replace("\ninitialStep: reserve-inventory\n", "\n\ninitialStep: reserve-inventory   \n"));
            changed.put("line endings changed to CRLF", original.replace("\n", "\r\n"));
            for (Map.Entry<String, String> variant : changed.entrySet()) {
                String text = variant.getValue();
                assertThat(text)
                        .as("%s must really change the text", variant.getKey())
                        .isNotEqualTo(original);
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                assertThat(first.app()
                                .definitionPipeline()
                                .load(new DefinitionResource(
                                        bytes,
                                        DocumentFormat.YAML,
                                        source("compile-check", Instant.parse("2026-10-18T12:30:00Z"))))
                                .planHash())
                        .as("%s must keep the compiled plan hash, so only the text differs", variant.getKey())
                        .isEqualTo(planHash);
            }
            first.close();

            int variantNumber = 0;
            for (Map.Entry<String, String> variant : changed.entrySet()) {
                variantNumber++;
                String variantName = variant.getKey();
                Stored before = scenario.stored(id, 1);
                BootObservation observation = new BootObservation(id, 1, planHash, true);

                // When the application starts with the changed document packaged
                Boot restart = scenario.attempt(
                        packaged(
                                variant.getValue(),
                                "variant-" + variantNumber,
                                Instant.parse("2026-10-18T13:00:00Z").plusSeconds(variantNumber)),
                        observation);

                // Then startup fails as drift naming the definition, with the digest message and the remedy
                assertThat(restart.failure())
                        .as("startup with %s must fail", variantName)
                        .isNotNull()
                        .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                            assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
                            assertThat(failure.key()).isEqualTo(key);
                            assertThat(failure.getMessage())
                                    .contains(id)
                                    .containsIgnoringCase("text digest")
                                    .containsIgnoringCase("bump the version")
                                    .containsIgnoringCase("retire");
                        });

                // And nothing was deployed and the stored row was not rewritten by the failing start
                assertThat(observation.events())
                        .as("no bootstrap deployment may happen after the failing start with %s", variantName)
                        .isEmpty();
                assertThat(scenario.stored(id, 1))
                        .as("the stored row must be untouched by the failing start with %s", variantName)
                        .isEqualTo(before);
                restart.close();
            }

            // When the very same document is deployed again
            BootObservation redeployObservation = new BootObservation(id, 1, planHash, true);
            Boot redeploy = scenario.node(
                    packaged(original, "redeploy", Instant.parse("2026-10-18T14:00:00Z")), redeployObservation);

            // Then startup succeeds and the definition is registered exactly once
            assertThat(redeployObservation.events())
                    .containsExactly("bootstrap-complete", "bootstrap-verticle-deployed");
            assertThat(redeploy.app().workflowRegistry().allRegistered())
                    .filteredOn(runtime -> runtime.plan().definitionId().equals(id))
                    .hasSize(1);
            assertThat(redeploy.app()
                            .workflowRegistry()
                            .resolvePinned(id, 1)
                            .plan()
                            .planHash())
                    .isEqualTo(planHash);
            assertThat(scenario.rowCount(id)).isEqualTo(1);
            Stored reconciled = scenario.stored(id, 1);
            assertThat(reconciled.planHash()).isEqualTo(planHash);
            assertThat(reconciled.document()).isEqualTo(original);
            assertThat(reconciled.sourceRevision()).isEqualTo("revision-redeploy");
        }
    }

    @Test
    @DisplayName("code-first versions are recorded at startup, ordered without a collision and keep their deprecation")
    void recordsAndOrdersCodeVersions(Vertx vertx) {
        String id = "mixed-bootstrap-code-order";
        DefinitionKey v1Key = new DefinitionKey(null, id, 1);
        WorkflowDefinition<?, ?> codeV1 = code(id, 1, OrderCodeV1.class, "done");
        WorkflowDefinition<?, ?> codeV2 = code(id, 2, OrderCodeV2.class, "done");
        String hashV1 = planHashOf(codeV1);
        String hashV2 = planHashOf(codeV2);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given an empty table and two Java definitions of one id with their own contracts
            assertThat(scenario.rowCount(id)).isZero();

            // When the real component starts
            Boot first = scenario.node(none(), observed(id, 2, hashV2), List.of(codeV1, codeV2));

            // Then both versions have a global code row with the registered hash and code provenance
            assertThat(scenario.findStored(id, 1))
                    .as("startup must record a row for code version 1")
                    .isPresent();
            assertThat(scenario.findStored(id, 2))
                    .as("startup must record a row for code version 2")
                    .isPresent();
            Stored rowV1 = scenario.stored(id, 1);
            Stored rowV2 = scenario.stored(id, 2);
            assertThat(rowV1.tenant()).isNull();
            assertThat(rowV1.kind()).isEqualTo("CODE");
            assertThat(rowV1.planHash()).isEqualTo(hashV1);
            assertThat(rowV1.sourceType()).isEqualTo("code");
            assertThat(rowV1.document()).isNull();
            assertThat(rowV1.documentFormat()).isNull();
            assertThat(rowV2.tenant()).isNull();
            assertThat(rowV2.kind()).isEqualTo("CODE");
            assertThat(rowV2.planHash()).isEqualTo(hashV2);
            assertThat(rowV2.sourceType()).isEqualTo("code");

            // And the highest version is active, the lower one superseded
            assertThat(rowV2.status()).isEqualTo("ACTIVE");
            assertThat(rowV1.status()).isEqualTo("SUPERSEDED");

            // When version 1 is deprecated and the application restarts
            await(first.app().definitionService().deprecate(v1Key));
            assertThat(scenario.stored(id, 1).status()).isEqualTo("DEPRECATED");
            first.close();
            Boot second = scenario.node(none(), observed(id, 2, hashV2), List.of(codeV1, codeV2));

            // Then version 2 stays active, version 1 stays deprecated and no row was added
            assertThat(scenario.stored(id, 2).status()).isEqualTo("ACTIVE");
            assertThat(scenario.stored(id, 2).planHash()).isEqualTo(hashV2);
            assertThat(scenario.stored(id, 1).status()).isEqualTo("DEPRECATED");
            assertThat(scenario.stored(id, 1).planHash()).isEqualTo(hashV1);
            assertThat(scenario.rowCount(id)).isEqualTo(2);

            // And the deprecation mark is applied again, so current resolution keeps skipping version 1
            assertThat(second.app().workflowRegistry().isDeprecated(id, 1)).isTrue();
            assertThat(second.app().workflowRegistry().isDeprecated(id, 2)).isFalse();
            assertThat(second.app().workflowRegistry().resolveCurrent(id).plan().definitionVersion())
                    .isEqualTo(2);
            assertThat(second.app()
                            .workflowRegistry()
                            .resolvePinned(id, 1)
                            .plan()
                            .planHash())
                    .isEqualTo(hashV1);
        }
    }

    @Test
    @DisplayName("a changed code hash is accepted only when no live instance is pinned to that exact version")
    void rehashesOnlyWithoutLivePins(Vertx vertx) {
        String pinnedId = "mixed-bootstrap-rehash-pinned";
        String freeId = "mixed-bootstrap-rehash-free";
        String storedPinnedHash = "c".repeat(64);
        String storedFreeHash = "d".repeat(64);
        WorkflowDefinition<?, ?> pinnedCode = code(pinnedId, 1, PinnedRehashCodeV1.class, "done");
        WorkflowDefinition<?, ?> freeCode = code(freeId, 1, FreeRehashCodeV1.class, "done");
        String pinnedHash = planHashOf(pinnedCode);
        String freeHash = planHashOf(freeCode);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(pinnedId, freeId);

            // Given code rows whose hash differs from the deployed code
            assertThat(storedPinnedHash).isNotEqualTo(pinnedHash);
            assertThat(storedFreeHash).isNotEqualTo(freeHash);
            scenario.seedCode(pinnedId, 1, storedPinnedHash, "ACTIVE");
            scenario.seedCode(freeId, 1, storedFreeHash, "ACTIVE");

            // And a live instance pinned to the exact version of the first
            scenario.insertInstance(pinnedId, 1, storedPinnedHash, "RUNNING");
            Stored pinnedBefore = scenario.stored(pinnedId, 1);

            // When the application restarts with the changed code
            BootObservation pinnedObservation = observed(pinnedId, 1, pinnedHash);
            Boot refused = scenario.attempt(none(), pinnedObservation, List.of(pinnedCode), null, 10);

            // Then startup fails as drift naming the definition, version and both hashes
            assertThat(refused.failure())
                    .as("startup with changed code under a live pin")
                    .isNotNull()
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
                        assertThat(failure.key()).isEqualTo(new DefinitionKey(null, pinnedId, 1));
                        assertThat(failure.getMessage())
                                .contains(pinnedId)
                                .contains(storedPinnedHash)
                                .contains(pinnedHash);
                    });

            // And nothing was deployed and the committed row keeps its hash
            assertThat(pinnedObservation.events()).isEmpty();
            assertThat(scenario.stored(pinnedId, 1)).isEqualTo(pinnedBefore);
            refused.close();
            scenario.removeRows(pinnedId);

            // Given, for the second id, only a finished instance of that version and a live one of another version
            scenario.insertInstance(freeId, 1, storedFreeHash, "COMPLETED");
            scenario.insertInstance(freeId, 2, storedFreeHash, "RUNNING");

            // When the application restarts with the changed code
            List<String> warnings;
            try (LogCapture logs = new LogCapture()) {
                Boot accepted = scenario.node(none(), observed(freeId, 1, freeHash), List.of(freeCode));
                assertThat(accepted.app()
                                .workflowRegistry()
                                .resolvePinned(freeId, 1)
                                .plan()
                                .planHash())
                        .isEqualTo(freeHash);
                warnings = logs.warnings();
            }

            // Then the row carries the new hash and stays active
            Stored rehashed = scenario.stored(freeId, 1);
            assertThat(rehashed.planHash()).isEqualTo(freeHash);
            assertThat(rehashed.status()).isEqualTo("ACTIVE");
            assertThat(rehashed.kind()).isEqualTo("CODE");

            // And a warning names both hashes
            assertThat(warnings)
                    .anySatisfy(message ->
                            assertThat(message).contains(storedFreeHash).contains(freeHash));
        }
    }

    @Test
    @DisplayName("control characters of a source label are escaped in the startup failure message")
    void escapesControlCharactersOfProvenanceInStartupFailures(Vertx vertx) {
        String id = "mixed-bootstrap-static-drift";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given a stored document row with a different hash, and a packaged label that tries to forge a log line
            String text = documentText(id, 1, StaticDriftV1.class);
            scenario.seedDocument(null, id, 1, "f".repeat(64), "SUPERSEDED", text);
            String label = "forged\r\nERROR injected line";

            // When the application starts with that packaged document
            Boot drifted = scenario.attempt(packaged(text, label, Instant.parse("2026-10-18T12:00:00Z")), unobserved());

            // Then startup fails as drift, and the message carries the label with its control characters escaped
            assertThat(drifted.failure())
                    .isNotNull()
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
                        assertThat(failure.getMessage())
                                .contains("forged\\u000D\\u000AERROR injected line")
                                .doesNotContain("\r")
                                .doesNotContain("\n");
                    });
            drifted.close();
        }
    }

    @Test
    @DisplayName("static documents stay document rows, and drift, kind, missing code and held owners fail precisely")
    void reconcilesStaticDocumentsAndFailsConflicts(Vertx vertx) {
        String equalId = "mixed-bootstrap-static-equal";
        String driftId = "mixed-bootstrap-static-drift";
        String kindId = "mixed-bootstrap-static-kind";
        String heldId = "mixed-bootstrap-static-held";
        String codeHeldId = "mixed-bootstrap-code-held";
        String missingActiveId = "mixed-bootstrap-missing-code-active";
        String missingDeprecatedId = "mixed-bootstrap-missing-code-deprecated";
        Instant firstLoad = Instant.parse("2026-10-18T12:00:00Z");

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(equalId, driftId, kindId, heldId, codeHeldId, missingActiveId, missingDeprecatedId);

            // Given a packaged static document and an empty table
            String equalText = documentText(equalId, 1, StaticEqualV1.class);
            Boot deployed = scenario.node(packaged(equalText, "classpath-first", firstLoad), unobserved());

            // Then it is a document row with its retained source text and provenance, not a code row
            Stored equalRow = scenario.stored(equalId, 1);
            assertThat(equalRow.kind()).isEqualTo("DOCUMENT");
            assertThat(equalRow.document()).isEqualTo(equalText);
            assertThat(equalRow.documentFormat()).isEqualTo("YAML");
            assertThat(equalRow.sourceType()).isEqualTo("digest-test");
            assertThat(equalRow.sourceUri()).isEqualTo("memory:classpath-first");
            assertThat(equalRow.sourceRevision()).isEqualTo("revision-classpath-first");
            assertThat(equalRow.status()).isEqualTo("ACTIVE");
            String staticHash = deployed.app()
                    .definitionPipeline()
                    .load(new DefinitionResource(
                            equalText.getBytes(StandardCharsets.UTF_8),
                            DocumentFormat.YAML,
                            source("hash-probe", firstLoad)))
                    .planHash();
            assertThat(equalRow.planHash()).isEqualTo(staticHash);
            deployed.close();

            // When the same document with the same hash is packaged again
            Boot redeployed = scenario.node(
                    packaged(equalText, "classpath-second", firstLoad.plusSeconds(60)),
                    observed(equalId, 1, staticHash));

            // Then it is registered exactly once and the row is still a document row
            assertThat(redeployed.app().workflowRegistry().allRegistered())
                    .filteredOn(runtime -> runtime.plan().definitionId().equals(equalId))
                    .hasSize(1);
            assertThat(scenario.rowCount(equalId)).isEqualTo(1);
            assertThat(scenario.stored(equalId, 1).kind()).isEqualTo("DOCUMENT");
            redeployed.close();
            scenario.removeRows(equalId);

            // Given a stored document row with a different hash for the same id and version
            String driftText = documentText(driftId, 1, StaticDriftV1.class);
            String storedDriftHash = "f".repeat(64);
            scenario.seedDocument(null, driftId, 1, storedDriftHash, "SUPERSEDED", driftText);
            Stored driftBefore = scenario.stored(driftId, 1);

            // When the application starts with the static document
            Boot drifted =
                    scenario.attempt(packaged(driftText, "classpath-drift", firstLoad.plusSeconds(120)), unobserved());

            // Then startup fails as drift naming both hashes and both provenance records
            assertThat(drifted.failure())
                    .as("startup with a static document whose stored hash differs")
                    .isNotNull()
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
                        assertThat(failure.key()).isEqualTo(new DefinitionKey(null, driftId, 1));
                        assertThat(failure.getMessage())
                                .contains(driftId)
                                .contains(storedDriftHash)
                                .contains("seed-docs")
                                .contains("digest-test")
                                .doesNotContain(driftText);
                    });
            assertThat(scenario.stored(driftId, 1)).isEqualTo(driftBefore);
            drifted.close();
            scenario.removeRows(driftId);

            // Given a code row for the id and version of a static document
            scenario.seedCode(kindId, 1, "9".repeat(64), "SUPERSEDED");
            Stored kindBefore = scenario.stored(kindId, 1);

            // When the application starts with the static document
            Boot kindConflict = scenario.attempt(
                    packaged(documentText(kindId, 1, StaticKindV1.class), "classpath-kind", firstLoad), unobserved());

            // Then startup fails naming both kinds and leaves the row alone
            assertThat(kindConflict.failure())
                    .as("startup with a static document over a code row")
                    .isNotNull()
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                        assertThat(failure.key()).isEqualTo(new DefinitionKey(null, kindId, 1));
                        assertThat(failure.getMessage())
                                .contains(kindId)
                                .contains("CODE")
                                .contains("DOCUMENT");
                    });
            assertThat(scenario.stored(kindId, 1)).isEqualTo(kindBefore);
            kindConflict.close();
            scenario.removeRows(kindId);

            // Given live code rows that no code registers any more
            scenario.seedCode(missingActiveId, 1, "8".repeat(64), "ACTIVE");
            scenario.seedCode(missingDeprecatedId, 1, "7".repeat(64), "DEPRECATED");
            for (String missingId : List.of(missingActiveId, missingDeprecatedId)) {
                Stored missingBefore = scenario.stored(missingId, 1);

                // When the application starts without that code
                Boot missing = scenario.attempt(none(), unobserved());

                // Then startup fails naming the row, and the row is untouched
                assertThat(missing.failure())
                        .as("startup without the code of %s", missingId)
                        .isNotNull()
                        .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                            assertThat(failure.reason()).isEqualTo(Reason.CODE_UNAVAILABLE);
                            assertThat(failure.key()).isEqualTo(new DefinitionKey(null, missingId, 1));
                            assertThat(failure.getMessage()).contains(missingId);
                        });
                assertThat(scenario.stored(missingId, 1)).isEqualTo(missingBefore);
                missing.close();
                scenario.removeRows(missingId);
            }

            // Given a tenant that holds the id and version of a static document
            String heldText = documentText(heldId, 1, StaticHeldV1.class);
            scenario.seedDocument("tenant-holder", heldId, 1, "7".repeat(64), "ACTIVE", heldText);

            // When the application starts with the global static document
            Boot staticHeld =
                    scenario.attempt(packaged(heldText, "classpath-held", firstLoad.plusSeconds(180)), unobserved());

            // Then startup fails naming both owners and inserts no global row
            assertThat(staticHeld.failure())
                    .as("startup with a static document over a tenant-held id")
                    .isNotNull()
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.OWNER_CONFLICT);
                        assertThat(failure.key()).isEqualTo(new DefinitionKey(null, heldId, 1));
                        assertThat(failure.getMessage())
                                .contains("tenant-holder")
                                .containsIgnoringCase("global");
                    });
            assertThat(scenario.findStored(heldId, 1)).isEmpty();
            staticHeld.close();
            scenario.removeRows(heldId);

            // Given a tenant that holds version 1 of an id for which code registers global version 2
            scenario.seedDocument(
                    "tenant-holder",
                    codeHeldId,
                    1,
                    "6".repeat(64),
                    "ACTIVE",
                    documentText(codeHeldId, 1, CodeHeldV1.class));
            WorkflowDefinition<?, ?> heldCode = code(codeHeldId, 2, CodeHeldV2.class, "done");

            // When the application starts with that code
            Boot codeHeld = scenario.attempt(none(), unobserved(), List.of(heldCode), null, 10);

            // Then startup fails naming both owners and inserts no global row
            assertThat(codeHeld.failure())
                    .as("startup with code over a tenant-held id")
                    .isNotNull()
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.OWNER_CONFLICT);
                        assertThat(failure.key()).isEqualTo(new DefinitionKey(null, codeHeldId, 2));
                        assertThat(failure.getMessage())
                                .contains("tenant-holder")
                                .containsIgnoringCase("global");
                    });
            assertThat(scenario.findStored(codeHeldId, 2)).isEmpty();
        }
    }

    @Test
    @DisplayName("stale claims are reset before metadata reaches the caches, and deprecated versions are restored")
    void resetsStaleClaimsBeforeMetadataHandoff(Vertx vertx) {
        String docId = "mixed-bootstrap-stale-doc";
        String claimedId = "mixed-bootstrap-stale-claimed-doc";
        String codeId = "mixed-bootstrap-stale-code";
        String nearId = "mixed-bootstrap-stale-near-doc";
        String freshId = "mixed-bootstrap-stale-fresh-doc";
        String staleTenant = "tenant-stale";
        DefinitionKey v1 = new DefinitionKey(null, docId, 1);
        DefinitionKey v2 = new DefinitionKey(null, docId, 2);
        DefinitionKey v3 = new DefinitionKey(null, docId, 3);
        DefinitionKey claimedKey = new DefinitionKey(null, claimedId, 1);
        DefinitionKey tenantClaimKey = new DefinitionKey(staleTenant, codeId, 1);
        DefinitionKey nearKey = new DefinitionKey(null, nearId, 1);
        WorkflowDefinition<?, ?> staleCode = code(codeId, 1, StaleCodeV1.class, "done");
        String codeHash = planHashOf(staleCode);
        Instant loadedAt = Instant.parse("2026-10-18T12:00:00Z");

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(docId, claimedId, codeId, nearId, freshId);

            // Given versions 1 and 2 of one document id, loaded and activated, with a pinned instance on version 1
            Boot first = scenario.node(none());
            WorkflowDefinitionService service = first.app().definitionService();
            CompiledDefinitionRef loadedV1 = await(service.load(
                    documentBytes(docId, 1, StaleDocV1.class),
                    DocumentFormat.YAML,
                    source("stale-v1", loadedAt),
                    null));
            await(service.activate(v1));
            WorkflowInstanceId pinned = await(first.app()
                    .workflowOperations()
                    .start(new StartCommand(docId, new PlaceOrderDoc("stale-order"), "stale-pin", null, null, 1L)));
            CompiledDefinitionRef loadedV2 = await(service.load(
                    documentBytes(docId, 2, StaleDocV2.class),
                    DocumentFormat.YAML,
                    source("stale-v2", loadedAt),
                    null));
            await(service.activate(v2));

            // And version 1 deprecated, an untouched candidate version 3, and a candidate of another global id
            await(service.deprecate(v1));
            CompiledDefinitionRef loadedV3 = await(service.load(
                    documentBytes(docId, 3, StaleDocV3.class),
                    DocumentFormat.YAML,
                    source("stale-v3", loadedAt),
                    null));
            await(service.load(
                    documentBytes(claimedId, 1, StaleClaimedV1.class),
                    DocumentFormat.YAML,
                    source("stale-claimed", loadedAt),
                    null));
            await(service.load(
                    documentBytes(nearId, 1, StaleNearV1.class),
                    DocumentFormat.YAML,
                    source("stale-near", loadedAt),
                    null));
            long bound = first.app().workflowDefinitionsConfig().maxStalenessSeconds();
            long staleAgeSeconds = 2 * bound + 60;
            // Ten seconds short of twice the bound leaves the margin that the boot needs on the database clock
            long nearlyStaleAgeSeconds = 2 * bound - 10;
            first.close();

            // And that other id left claimed past twice the bound, next to a stale tenant claim on the id of a
            // code definition
            scenario.claimWithoutCompleting(claimedKey);
            scenario.ageStatus(claimedKey, staleAgeSeconds);
            scenario.seedDocument(
                    staleTenant, codeId, 1, "5".repeat(64), "ACTIVATING", documentText(codeId, 1, StaleCodeV1.class));
            scenario.ageStatus(tenantClaimKey, staleAgeSeconds);

            // And a global claim just younger than twice the bound, and a tenant claim made just now
            scenario.claimWithoutCompleting(nearKey);
            scenario.ageStatus(nearKey, nearlyStaleAgeSeconds);
            scenario.seedDocument("tenant-fresh", freshId, 1, "3".repeat(64), "ACTIVATING", "workflow: {}\n");
            Stored nearBefore = scenario.stored(nearId, 1);
            Stored freshBefore = scenario.stored("tenant-fresh", freshId, 1);
            assertThat(nearBefore.status()).isEqualTo("ACTIVATING");
            assertThat(nearBefore.activationToken()).isNotNull();
            assertThat(freshBefore.status()).isEqualTo("ACTIVATING");
            assertThat(freshBefore.activationToken()).isNotNull();
            assertThat(scenario.stored(v1.definitionId(), 1).status()).isEqualTo("DEPRECATED");
            assertThat(scenario.stored(v2.definitionId(), 2).status()).isEqualTo("ACTIVE");
            assertThat(scenario.stored(v3.definitionId(), 3).status()).isEqualTo("CANDIDATE");
            assertThat(scenario.stored(claimedId, 1).status()).isEqualTo("ACTIVATING");
            assertThat(scenario.stored(staleTenant, codeId, 1).status()).isEqualTo("ACTIVATING");

            // When the application restarts
            BootObservation observation = observed(codeId, 1, codeHash);
            Boot second = scenario.node(none(), observation, List.of(staleCode));

            // Then the stale claims are back to candidates with no token
            Stored claimedAfter = scenario.stored(claimedId, 1);
            assertThat(claimedAfter.status()).isEqualTo("CANDIDATE");
            assertThat(claimedAfter.activationToken()).isNull();
            Stored tenantAfter = scenario.stored(staleTenant, codeId, 1);
            assertThat(tenantAfter.status()).isEqualTo("CANDIDATE");
            assertThat(tenantAfter.activationToken()).isNull();

            // And the claim just younger than twice the bound and the fresh claim are left exactly as they were,
            // with their tokens and change times
            assertThat(scenario.stored(nearId, 1)).isEqualTo(nearBefore);
            assertThat(scenario.stored("tenant-fresh", freshId, 1)).isEqualTo(freshBefore);

            // And the stale tenant claim did not keep the code owner out of its id
            Stored codeRow = scenario.stored(codeId, 1);
            assertThat(codeRow.kind()).isEqualTo("CODE");
            assertThat(codeRow.planHash()).isEqualTo(codeHash);
            assertThat(codeRow.status()).isEqualTo("ACTIVE");

            // And the untouched candidate stays a candidate and the active version stays active
            assertThat(scenario.stored(docId, 3).status()).isEqualTo("CANDIDATE");
            assertThat(scenario.stored(docId, 2).status()).isEqualTo("ACTIVE");

            // And the caches hold the persisted statuses, including the rows that were just reset
            assertThat(second.app().workflowDefinitionStore().currentStatus(claimedId, 1))
                    .isEqualTo(ActivationStatus.CANDIDATE);
            assertThat(second.app().workflowDefinitionStore().currentStatus(docId, 3))
                    .isEqualTo(ActivationStatus.CANDIDATE);
            assertThat(second.app().workflowDefinitionStore().currentStatus(docId, 2))
                    .isEqualTo(ActivationStatus.ACTIVE);
            assertThat(second.app().workflowDefinitionStore().currentStatus(docId, 1))
                    .isEqualTo(ActivationStatus.DEPRECATED);

            // And before traffic the deprecated version is marked, resolves pinned and current resolution skips it
            assertThat(second.app().workflowRegistry().isDeprecated(docId, 1)).isTrue();
            assertThat(second.app().workflowRegistry().isDeprecated(docId, 2)).isFalse();
            assertThat(second.app()
                            .workflowRegistry()
                            .resolvePinned(docId, 1)
                            .plan()
                            .planHash())
                    .isEqualTo(loadedV1.planHash());
            assertThat(second.app()
                            .workflowRegistry()
                            .resolveCurrent(docId)
                            .plan()
                            .definitionVersion())
                    .isEqualTo(2);
            assertThat(second.app()
                            .workflowRegistry()
                            .resolvePinned(docId, 2)
                            .plan()
                            .planHash())
                    .isEqualTo(loadedV2.planHash());
            assertThat(loadedV3.planHash()).isNotBlank();

            // And an explicit new start of the deprecated version fails typed while the pinned instance resumes
            Throwable newStart = failureOf(second.app()
                    .workflowOperations()
                    .start(new StartCommand(docId, new PlaceOrderDoc("stale-new"), "stale-new", null, null, 1L)));
            assertThat(newStart).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.DEPRECATED);
                assertThat(failure.key()).isEqualTo(v1);
            });
            WorkflowView before = await(second.app().workflowOperations().query(pinned));
            assertThat(before.instance().currentStepId()).isEqualTo("wait-inventory");
            await(second.app()
                    .workflowOperations()
                    .signal(pinned, "inventory.reserved", new InventoryReservedDoc("reservation-1"), "stale-signal"));
            WorkflowView after = await(second.app().workflowOperations().query(pinned));
            assertThat(after.instance().definitionVersion()).isEqualTo(1);
            assertThat(after.instance().planHash()).isEqualTo(loadedV1.planHash());
            assertThat(after.instance().currentStepId()).isNotEqualTo("wait-inventory");
            assertThat(after.instance().version())
                    .isGreaterThan(before.instance().version());
            assertThat(scenario.instanceCount(docId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a tenant cannot activate an id that code already holds globally")
    void refusesTenantActivationAgainstGlobalCode(Vertx vertx) {
        String id = "mixed-bootstrap-tenant-vs-code";
        String tenant = "tenant-conflict";
        DefinitionKey tenantKey = new DefinitionKey(tenant, id, 1);
        WorkflowDefinition<?, ?> codeV1 = code(id, 1, TenantVsCodeV1.class, "done");
        String codeHash = planHashOf(codeV1);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given code that startup recorded as a global row, and a tenant candidate of the same id and version
            Boot node = scenario.node(none(), observed(id, 1, codeHash), List.of(codeV1));
            assertThat(scenario.findStored(id, 1))
                    .as("startup must record the code definition")
                    .isPresent();
            Stored globalBefore = scenario.stored(id, 1);
            assertThat(globalBefore.kind()).isEqualTo("CODE");
            await(node.app()
                    .definitionService()
                    .load(
                            documentBytes(id, 1, TenantVsCodeV1.class),
                            DocumentFormat.YAML,
                            source("tenant-candidate", Instant.parse("2026-10-18T12:00:00Z")),
                            tenant));
            Stored candidateBefore = scenario.stored(tenant, id, 1);
            assertThat(candidateBefore.status()).isEqualTo("CANDIDATE");
            RuntimeWorkflow runtimeBefore = node.app().workflowRegistry().resolvePinned(id, 1);
            WorkflowContractMetadata mappingBefore =
                    node.app().workflowRegistry().contractMetadata(TenantVsCodeV1.class);

            // When the tenant activates its candidate through the real lifecycle
            Throwable refusal = failureOf(node.app().definitionService().activate(tenantKey));

            // Then it fails as an ownership conflict naming both owners
            assertThat(refusal)
                    .isNotNull()
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.OWNER_CONFLICT);
                        assertThat(failure.key()).isEqualTo(tenantKey);
                        assertThat(failure.getMessage()).contains(tenant).containsIgnoringCase("global");
                    });

            // And the executable registry and both rows are unchanged
            assertThat(node.app().workflowRegistry().resolvePinned(id, 1)).isSameAs(runtimeBefore);
            assertThat(node.app().workflowRegistry().allRegistered())
                    .filteredOn(runtime -> runtime.plan().definitionId().equals(id))
                    .containsExactly(runtimeBefore);
            assertThat(node.app().workflowRegistry().contractMetadata(TenantVsCodeV1.class))
                    .isEqualTo(mappingBefore);
            assertThat(scenario.stored(id, 1)).isEqualTo(globalBefore);
            assertThat(scenario.stored(tenant, id, 1))
                    .usingRecursiveComparison()
                    .ignoringFields("changedAt")
                    .isEqualTo(candidateBefore);
        }
    }

    @Test
    @DisplayName("an old-code node is refused new pins, migrations and retries after a rolling same-version rehash")
    void rejectsOldHashPinsAfterRollingRehash(Vertx vertx) {
        String id = "mixed-bootstrap-rolling-rehash";
        DefinitionKey v2Key = new DefinitionKey(null, id, 2);
        WorkflowDefinition<?, ?> unchangedV1 = code(id, 1, RollingV1.class, "done");
        WorkflowDefinition<?, ?> oldV2 = code(id, 2, RollingV2.class, "done");
        WorkflowDefinition<?, ?> newV2 = code(id, 2, RollingV2.class, "finished");
        String hashV1 = planHashOf(unchangedV1);
        String oldHashV2 = planHashOf(oldV2);
        String newHashV2 = planHashOf(newV2);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given committed code rows of the old code, a live instance pinned to version 1 and a failed
            // instance pinned to version 2 (a finished pin does not count as live)
            assertThat(newHashV2).isNotEqualTo(oldHashV2);
            scenario.seedCode(id, 1, hashV1, "SUPERSEDED");
            scenario.seedCode(id, 2, oldHashV2, "ACTIVE");
            UUID live = scenario.insertInstance(id, 1, hashV1, "RUNNING");
            UUID failed = scenario.insertInstance(id, 2, oldHashV2, "FAILED");

            // And the old-code node, which keeps the old compiled hash of version 2
            Boot oldNode = scenario.node(none(), unobserved(), List.of(unchangedV1, oldV2));
            assertThat(oldNode.app()
                            .workflowRegistry()
                            .resolvePinned(id, 2)
                            .plan()
                            .planHash())
                    .isEqualTo(oldHashV2);
            long instancesBefore = scenario.instanceCount(id);

            // When the new code starts, with no live pin on version 2
            Boot newNode = scenario.node(none(), observed(id, 2, newHashV2), List.of(unchangedV1, newV2));

            // Then the row of version 2 carries the new hash and version 1 is untouched
            assertThat(scenario.stored(id, 2).planHash()).isEqualTo(newHashV2);
            assertThat(scenario.stored(id, 1).planHash()).isEqualTo(hashV1);
            assertThat(newNode.app()
                            .workflowRegistry()
                            .resolvePinned(id, 2)
                            .plan()
                            .planHash())
                    .isEqualTo(newHashV2);
            Stored committed = scenario.stored(id, 2);

            // When the old-code node starts a new instance
            OpenTransaction startTx = OpenTransaction.begin(scenario);
            Throwable startRefusal = failureOf(oldNode.app()
                    .transactionalOperations()
                    .start(
                            new StartCommand(id, new CodeStart("rolling"), "rolling-start", null, null, 2L),
                            startTx.connection()));
            startTx.rollback();

            // Then the start is refused as drift naming version 2, and no instance or dedup row remains
            assertThat(startRefusal)
                    .as("start on the old-code node")
                    .isNotNull()
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
                        assertThat(failure.key()).isEqualTo(v2Key);
                    });
            assertThat(scenario.instanceCount(id)).isEqualTo(instancesBefore);
            assertThat(scenario.dedupCount(id)).isZero();

            // When the old-code node moves the live version 1 instance onto version 2 with its old hash
            PgWorkflowInstanceRepository instances =
                    new PgWorkflowInstanceRepository(scenario.admin(), new PgDbExceptionMapper());
            OpenTransaction migration = OpenTransaction.begin(scenario);
            Throwable migrateRefusal = failureOf(instances.migratePinAndState(
                    instanceRow(live, id, 2, oldHashV2, 1, WorkflowStatus.RUNNING), 0, migration.connection()));
            migration.rollback();

            // Then the migration is refused the same way and the pin stays on version 1
            assertThat(migrateRefusal)
                    .as("migration from the old-code node")
                    .isNotNull()
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
                        assertThat(failure.key()).isEqualTo(v2Key);
                    });

            // When the old-code node retries the failed instance that carries the old hash
            OpenTransaction retry = OpenTransaction.begin(scenario);
            Throwable retryRefusal = failureOf(instances.updateOptimistic(
                    instanceRow(failed, id, 2, oldHashV2, 1, WorkflowStatus.RUNNING), 0, retry.connection()));
            retry.rollback();

            // Then the retry is refused the same way and the instance stays failed
            assertThat(retryRefusal)
                    .as("retry from the old-code node")
                    .isNotNull()
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
                        assertThat(failure.key()).isEqualTo(v2Key);
                    });
            assertThat(scenario.instanceStatus(failed)).isEqualTo("FAILED");
            assertThat(scenario.instanceStatus(live)).isEqualTo("RUNNING");

            // And no instance carries the old hash as a live pin, and the committed row is as the new code left it
            assertThat(scenario.livePinCount(id, 2, oldHashV2)).isZero();
            assertThat(scenario.stored(id, 2)).isEqualTo(committed);
        }
    }

    @Test
    @DisplayName("startup reports a retryable lock timeout from code reconciliation and from the stale claim reset")
    void reportsRetryableStartupLockTimeout(Vertx vertx) {
        String codeId = "mixed-bootstrap-timeout-code";
        String resetId = "mixed-bootstrap-timeout-reset";
        String resetTenant = "tenant-reset";
        DefinitionKey resetKey = new DefinitionKey(resetTenant, resetId, 1);
        WorkflowDefinition<?, ?> timeoutCode = code(codeId, 1, TimeoutCodeV1.class, "done");
        String codeHash = planHashOf(timeoutCode);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(codeId, resetId);

            // Given a shared admission holder on the id of a code definition
            AdmissionHolder codeHolder = AdmissionHolder.hold(scenario, codeId);
            WorkflowDefinitionSqlObserver codeObserver = new WorkflowDefinitionSqlObserver();
            codeObserver.shortenEveryDefinitionLockTimeout(1_000);

            // When startup reconciles the code definition on a single-connection pool
            Boot upsertTimeout =
                    scenario.attempt(none(), observed(codeId, 1, codeHash), List.of(timeoutCode), codeObserver, 1);

            // Then startup fails with the retryable lock timeout naming the id
            WorkflowPersistenceException upsertMapped =
                    causeOf(upsertTimeout.failure(), WorkflowPersistenceException.class);
            assertThat(upsertMapped)
                    .as("startup failure chain: %s", upsertTimeout.failure())
                    .isNotNull();
            assertThat(upsertMapped.retryable()).isTrue();
            assertThat(upsertMapped.getMessage()).contains(codeId);
            assertThat(causeOf(upsertMapped.getCause(), PessimisticLockingFailureException.class)
                            .sqlState())
                    .isEqualTo("55P03");
            assertThat(codeObserver.shortenedDefinitionLockTimeouts()).isGreaterThanOrEqualTo(1);
            assertThat(codeObserver.statements())
                    .anySatisfy(sql -> assertThat(normalized(sql)).isEqualTo("set local lock_timeout = '30000ms'"));

            // And nothing was written and the only connection carries no LOCAL setting or aborted transaction
            assertThat(scenario.findStored(codeId, 1)).isEmpty();
            Row reused = await(upsertTimeout
                            .app()
                            .pool()
                            .query("SELECT current_setting('lock_timeout') AS lock_timeout")
                            .execute())
                    .iterator()
                    .next();
            assertThat(reused.getString("lock_timeout")).isEqualTo("0");
            upsertTimeout.close();

            // When the holder releases and the application starts again
            codeHolder.release();
            Boot started = scenario.node(none(), observed(codeId, 1, codeHash), List.of(timeoutCode));

            // Then startup succeeds and the code row exists
            Stored codeRow = scenario.stored(codeId, 1);
            assertThat(codeRow.kind()).isEqualTo("CODE");
            assertThat(codeRow.planHash()).isEqualTo(codeHash);
            long staleAgeSeconds = 2 * started.app().workflowDefinitionsConfig().maxStalenessSeconds() + 60;
            started.close();

            // Given a tenant claim on another id left past twice the bound, and a shared admission holder on that id
            scenario.seedDocument(resetTenant, resetId, 1, "4".repeat(64), "ACTIVATING", "workflow: {}\n");
            scenario.ageStatus(resetKey, staleAgeSeconds);
            Stored claimBefore = scenario.stored(resetTenant, resetId, 1);
            AdmissionHolder resetHolder = AdmissionHolder.hold(scenario, resetId);
            WorkflowDefinitionSqlObserver resetObserver = new WorkflowDefinitionSqlObserver();
            resetObserver.shortenEveryDefinitionLockTimeout(1_000);

            // When startup resets the stale claim on a single-connection pool
            Boot resetTimeout =
                    scenario.attempt(none(), observed(codeId, 1, codeHash), List.of(timeoutCode), resetObserver, 1);

            // Then startup fails with the retryable lock timeout naming the id of the claim
            WorkflowPersistenceException resetMapped =
                    causeOf(resetTimeout.failure(), WorkflowPersistenceException.class);
            assertThat(resetMapped)
                    .as("startup failure chain: %s", resetTimeout.failure())
                    .isNotNull();
            assertThat(resetMapped.retryable()).isTrue();
            assertThat(resetMapped.getMessage()).contains(resetId);
            assertThat(causeOf(resetMapped.getCause(), PessimisticLockingFailureException.class)
                            .sqlState())
                    .isEqualTo("55P03");

            // And the claim is unchanged and the only connection is reusable
            assertThat(scenario.stored(resetTenant, resetId, 1)).isEqualTo(claimBefore);
            Row reusedAfterReset = await(resetTimeout
                            .app()
                            .pool()
                            .query("SELECT current_setting('lock_timeout') AS lock_timeout")
                            .execute())
                    .iterator()
                    .next();
            assertThat(reusedAfterReset.getString("lock_timeout")).isEqualTo("0");
            resetTimeout.close();

            // When the holder releases and the application starts again
            resetHolder.release();
            scenario.node(none(), observed(codeId, 1, codeHash), List.of(timeoutCode));

            // Then startup succeeds and the stale claim is a candidate again
            Stored claimAfter = scenario.stored(resetTenant, resetId, 1);
            assertThat(claimAfter.status()).isEqualTo("CANDIDATE");
            assertThat(claimAfter.activationToken()).isNull();
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(SharedIdOrder.class)
    @DisplayName("every live tenant document is restored before traffic, whichever order the owners are read in")
    void restoresTenantDocumentBeforeTraffic(SharedIdOrder order, Vertx vertx) {
        String activeId = "mixed-bootstrap-tenant-active";
        String supersededId = "mixed-bootstrap-tenant-superseded";
        String deprecatedId = "mixed-bootstrap-tenant-deprecated";
        String sharedId = "mixed-bootstrap-tenant-shared";
        String candidateId = "mixed-bootstrap-tenant-candidate";
        Instant loadedAt = Instant.parse("2026-10-18T12:00:00Z");

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(activeId, supersededId, deprecatedId, sharedId, candidateId);

            // Given durable tenant documents in each live status, committed through the real service
            Boot first = scenario.node(none());
            WorkflowDefinitionService service = first.app().definitionService();
            CompiledDefinitionRef active = publish(service, "tenant-active", activeId, 1, TenantActiveV1.class);
            CompiledDefinitionRef supersededV1 =
                    publish(service, "tenant-superseded", supersededId, 1, TenantSupersededV1.class);
            CompiledDefinitionRef supersededV2 =
                    publish(service, "tenant-superseded", supersededId, 2, TenantSupersededV2.class);
            CompiledDefinitionRef deprecatedV1 =
                    publish(service, "tenant-deprecated", deprecatedId, 1, TenantDeprecatedV1.class);
            CompiledDefinitionRef deprecatedV2 =
                    publish(service, "tenant-deprecated", deprecatedId, 2, TenantDeprecatedV2.class);
            await(service.deprecate(new DefinitionKey("tenant-deprecated", deprecatedId, 1)));
            await(service.load(
                    documentBytes(candidateId, 1, TenantCandidateV1.class),
                    DocumentFormat.YAML,
                    source("tenant-candidate", loadedAt),
                    "tenant-candidate"));

            // And one id retained for a retired owner and held by another owner at the identical version and hash
            DefinitionKey retiredKey = new DefinitionKey(order.retiredTenant, sharedId, 1);
            DefinitionKey liveKey = new DefinitionKey(order.liveTenant, sharedId, 1);
            CompiledDefinitionRef retiredLoaded = await(service.load(
                    documentBytes(sharedId, 1, TenantSharedV1.class),
                    DocumentFormat.YAML,
                    source("shared-retired", loadedAt),
                    order.retiredTenant));
            CompiledDefinitionRef liveLoaded = await(service.load(
                    documentBytes(sharedId, 1, TenantSharedV1.class),
                    DocumentFormat.YAML,
                    source("shared-live", loadedAt),
                    order.liveTenant));
            await(service.activate(liveKey));
            await(service.retire(retiredKey));
            assertThat(retiredLoaded.planHash()).isEqualTo(liveLoaded.planHash());
            first.close();
            assertThat(scenario.stored("tenant-active", activeId, 1).status()).isEqualTo("ACTIVE");
            assertThat(scenario.stored("tenant-superseded", supersededId, 1).status())
                    .isEqualTo("SUPERSEDED");
            assertThat(scenario.stored("tenant-superseded", supersededId, 2).status())
                    .isEqualTo("ACTIVE");
            assertThat(scenario.stored("tenant-deprecated", deprecatedId, 1).status())
                    .isEqualTo("DEPRECATED");
            assertThat(scenario.stored("tenant-deprecated", deprecatedId, 2).status())
                    .isEqualTo("ACTIVE");
            assertThat(scenario.stored(order.liveTenant, sharedId, 1).status()).isEqualTo("ACTIVE");
            assertThat(scenario.stored(order.retiredTenant, sharedId, 1).status())
                    .isEqualTo("RETIRED");
            assertThat(scenario.stored("tenant-candidate", candidateId, 1).status())
                    .isEqualTo("CANDIDATE");

            // When a fresh component, which has published none of them, starts and the first document is checked
            // before deployment
            BootObservation observation = observed(activeId, 1, active.planHash());
            Boot second = scenario.attempt(none(), observation);

            // Then startup completes, with the restoration finished before the bootstrap deployment
            assertThat(second.failure())
                    .as("startup must restore every live tenant document before traffic")
                    .isNull();
            assertThat(observation.events()).containsExactly("bootstrap-complete", "bootstrap-verticle-deployed");
            WorkflowRegistry registry = second.app().workflowRegistry();

            // And every live version resolves pinned with its own hash and contract mapping
            assertThat(registry.resolvePinned(activeId, 1).plan().planHash()).isEqualTo(active.planHash());
            assertThat(registry.resolvePinned(supersededId, 1).plan().planHash())
                    .isEqualTo(supersededV1.planHash());
            assertThat(registry.resolvePinned(supersededId, 2).plan().planHash())
                    .isEqualTo(supersededV2.planHash());
            assertThat(registry.resolvePinned(deprecatedId, 1).plan().planHash())
                    .isEqualTo(deprecatedV1.planHash());
            assertThat(registry.resolvePinned(deprecatedId, 2).plan().planHash())
                    .isEqualTo(deprecatedV2.planHash());
            assertThat(registry.resolvePinned(sharedId, 1).plan().planHash()).isEqualTo(liveLoaded.planHash());
            for (Class<?> contract : List.of(
                    TenantActiveV1.class,
                    TenantSupersededV1.class,
                    TenantSupersededV2.class,
                    TenantDeprecatedV1.class,
                    TenantDeprecatedV2.class,
                    TenantSharedV1.class)) {
                assertThat(registry.contractMetadata(contract)).isNotNull();
            }

            // And current membership and the deprecated mark are the committed ones
            assertThat(registry.resolveCurrent(supersededId).plan().definitionVersion())
                    .isEqualTo(2);
            assertThat(registry.resolveCurrent(deprecatedId).plan().definitionVersion())
                    .isEqualTo(2);
            assertThat(registry.isDeprecated(deprecatedId, 1)).isTrue();
            assertThat(registry.isDeprecated(deprecatedId, 2)).isFalse();
            assertThat(registry.isDeprecated(supersededId, 1)).isFalse();

            // And the retired owner's row neither removed the other owner's runtime nor its contract mapping
            assertThat(registry.allRegistered())
                    .filteredOn(runtime -> runtime.plan().definitionId().equals(sharedId))
                    .hasSize(1);
            assertThat(scenario.stored(order.retiredTenant, sharedId, 1).status())
                    .isEqualTo("RETIRED");
            assertThat(scenario.stored(order.liveTenant, sharedId, 1).status()).isEqualTo("ACTIVE");

            // And the candidate stays metadata only
            assertThatThrownBy(() -> registry.resolvePinned(candidateId, 1))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);
            assertThat(scenario.stored("tenant-candidate", candidateId, 1).status())
                    .isEqualTo("CANDIDATE");
            assertThat(scenario.stored("tenant-deprecated", deprecatedId, 1).status())
                    .isEqualTo("DEPRECATED");
            assertThat(scenario.stored("tenant-superseded", supersededId, 1).status())
                    .isEqualTo("SUPERSEDED");
        }
    }

    @Test
    @DisplayName(
            "a packaged document deprecated at runtime is deprecated again after a restart and still serves its pins")
    void restoresDeprecatedPackagedDocumentAfterRestart(Vertx vertx) {
        String id = "mixed-bootstrap-packaged-deprecated";
        DefinitionKey v1 = new DefinitionKey(null, id, 1);
        Instant loadedAt = Instant.parse("2026-10-18T12:00:00Z");
        String textV1 = documentText(id, 1, PackagedDeprecatedV1.class);
        String textV2 = documentText(id, 2, PackagedDeprecatedV2.class);
        WorkflowDefinitionSource packagedBoth = () -> List.of(
                new DefinitionResource(
                        textV1.getBytes(StandardCharsets.UTF_8), DocumentFormat.YAML, source("packaged-v1", loadedAt)),
                new DefinitionResource(
                        textV2.getBytes(StandardCharsets.UTF_8), DocumentFormat.YAML, source("packaged-v2", loadedAt)));

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given two packaged versions registered at startup, version 2 active above version 1
            Boot first = scenario.node(packagedBoth, unobserved());
            assertThat(scenario.stored(id, 1).status()).isEqualTo("SUPERSEDED");
            assertThat(scenario.stored(id, 2).status()).isEqualTo("ACTIVE");
            String hashV1 = scenario.stored(id, 1).planHash();
            String hashV2 = scenario.stored(id, 2).planHash();

            // And an instance pinned to version 1, waiting for its first signal
            WorkflowInstanceId pinned = await(first.app()
                    .workflowOperations()
                    .start(new StartCommand(id, new PlaceOrderDoc("packaged-order"), "packaged-pin", null, null, 1L)));

            // And version 1 deprecated through the service
            await(first.app().definitionService().deprecate(v1));
            assertThat(scenario.stored(id, 1).status()).isEqualTo("DEPRECATED");
            first.close();

            // When the component restarts with the same packaged documents
            Boot second = scenario.node(packagedBoth, observed(id, 2, hashV2));
            WorkflowRegistry registry = second.app().workflowRegistry();

            // Then the durable rows agree
            assertThat(scenario.stored(id, 1).status()).isEqualTo("DEPRECATED");
            assertThat(scenario.stored(id, 2).status()).isEqualTo("ACTIVE");

            // And the deprecation mark is applied again, so current resolution serves version 2
            assertThat(registry.isDeprecated(id, 1)).isTrue();
            assertThat(registry.isDeprecated(id, 2)).isFalse();
            assertThat(registry.resolveCurrent(id).plan().definitionVersion()).isEqualTo(2);
            assertThat(second.app().workflowDefinitionStore().currentStatus(id, 1))
                    .isEqualTo(ActivationStatus.DEPRECATED);

            // And version 1 is still served to the instances pinned to it, with each version registered once
            assertThat(registry.resolvePinned(id, 1).plan().planHash()).isEqualTo(hashV1);
            assertThat(registry.allRegistered())
                    .filteredOn(runtime -> runtime.plan().definitionId().equals(id))
                    .extracting(runtime -> runtime.plan().definitionVersion())
                    .containsExactlyInAnyOrder(1L, 2L);

            // And an explicit new start of the deprecated version fails typed
            Throwable newStart = failureOf(second.app()
                    .workflowOperations()
                    .start(new StartCommand(id, new PlaceOrderDoc("packaged-new"), "packaged-new", null, null, 1L)));
            assertThat(newStart).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.DEPRECATED);
                assertThat(failure.key()).isEqualTo(v1);
            });

            // And the pinned instance resumes on version 1
            WorkflowView before = await(second.app().workflowOperations().query(pinned));
            assertThat(before.instance().currentStepId()).isEqualTo("wait-inventory");
            await(second.app()
                    .workflowOperations()
                    .signal(
                            pinned,
                            "inventory.reserved",
                            new InventoryReservedDoc("reservation-1"),
                            "packaged-signal"));
            WorkflowView after = await(second.app().workflowOperations().query(pinned));
            assertThat(after.instance().definitionVersion()).isEqualTo(1);
            assertThat(after.instance().planHash()).isEqualTo(hashV1);
            assertThat(after.instance().currentStepId()).isNotEqualTo("wait-inventory");
            assertThat(scenario.instanceCount(id)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a code rehash decides on pins at read committed, whatever the server default isolation is")
    void rehashesCodeOnlyAtReadCommittedEvenWithRepeatableReadServerDefault(Vertx vertx) {
        String id = "mixed-bootstrap-rehash-isolation";
        String storedHash = "a".repeat(64);
        WorkflowDefinition<?, ?> deployed = code(id, 1, RehashIsolationCodeV1.class, "done");
        String deployedHash = planHashOf(deployed);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given a database whose default isolation is REPEATABLE READ, restored when the scenario ends
            String database = await(scenario.admin()
                            .query("SELECT current_database() AS name")
                            .execute())
                    .iterator()
                    .next()
                    .getString("name");
            await(scenario.admin()
                    .query("ALTER DATABASE \"" + database + "\" SET default_transaction_isolation = 'repeatable read'")
                    .execute());
            scenario.onClose(() -> await(scenario.admin()
                    .query("ALTER DATABASE \"" + database + "\" RESET default_transaction_isolation")
                    .execute()));

            // And a code row whose hash differs from the deployed code, with no instance pinned to it
            assertThat(storedHash).isNotEqualTo(deployedHash);
            scenario.seedCode(id, 1, storedHash, "ACTIVE");

            // And a shared admission held by a caller transaction
            AdmissionHolder holder = AdmissionHolder.hold(scenario, id);

            // When a node with the changed code starts afterwards, and its reconciliation is proven queued
            PendingBoot pending = scenario.begin(none(), unobserved(), List.of(deployed), null, 10);
            scenario.awaitAdvisory(id, "ExclusiveLock", false, 1);
            Row isolation = await(pending.app()
                            .pool()
                            .query("SHOW default_transaction_isolation")
                            .execute())
                    .iterator()
                    .next();
            assertThat(isolation.getString(0)).isEqualTo("repeatable read");

            // And a RUNNING instance on that exact version commits while the reconciliation waits
            await(holder.connection().preparedQuery("""
                            INSERT INTO workflow_instances
                                (id, definition_id, definition_version, plan_hash, version, status,
                                 current_step_id, state_json)
                            VALUES ($1, $2, 1, $3, 0, 'RUNNING', 'start', '{}'::jsonb)
                            """).execute(Tuple.of(UUID.randomUUID(), id, storedHash)));
            holder.commit();
            Boot result = pending.await();

            // Then startup fails as drift naming both hashes, because the pin committed during the wait is seen
            assertThat(result.failure())
                    .as("startup whose code hash changed while a pin committed during the wait")
                    .isNotNull()
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
                        assertThat(failure.key()).isEqualTo(new DefinitionKey(null, id, 1));
                        assertThat(failure.getMessage())
                                .contains(id)
                                .contains(storedHash)
                                .contains(deployedHash);
                    });

            // And the stored hash stays as it was
            assertThat(scenario.stored(id, 1).planHash()).isEqualTo(storedHash);
            assertThat(scenario.livePinCount(id, 1, storedHash)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a retired code row whose code was removed does not stop startup and registers nothing")
    void startsWithRetiredCodeRowAndRemovedCode(Vertx vertx) {
        String id = "mixed-bootstrap-retired-removed";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given a retired code row that no deployed code provides any more
            scenario.seedCode(id, 1, "2".repeat(64), "RETIRED");
            Stored before = scenario.stored(id, 1);

            // When the application starts without that code
            Boot started = scenario.attempt(none(), unobserved());

            // Then startup succeeds, the row stays retired and nothing is registered for it
            assertThat(started.failure()).as("startup with a retired code row").isNull();
            assertThat(scenario.stored(id, 1)).isEqualTo(before);
            assertThat(started.app().workflowRegistry().allRegistered())
                    .noneMatch(runtime -> runtime.plan().definitionId().equals(id));
        }
    }

    @Test
    @DisplayName("a retired code row whose code is still deployed is unregistered, next to a live version of its id")
    void unregistersRetiredCodeRowWhoseCodeIsStillDeployed(Vertx vertx) {
        String id = "mixed-bootstrap-retired-code";
        WorkflowDefinition<?, ?> retiredCode = code(id, 1, RetiredCodeV1.class, "done");
        WorkflowDefinition<?, ?> liveCode = code(id, 2, RetiredCodeV2.class, "done");
        String retiredHash = planHashOf(retiredCode);
        String liveHash = planHashOf(liveCode);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given a retired code row and a live one of the same id, both still provided by the deployed code
            scenario.seedCode(id, 1, retiredHash, "RETIRED");
            scenario.seedCode(id, 2, liveHash, "ACTIVE");

            // When the application starts
            Boot started = scenario.node(none(), observed(id, 2, liveHash), List.of(retiredCode, liveCode));
            WorkflowRegistry registry = started.app().workflowRegistry();

            // Then the retired version is no longer registered and its contract mapping is gone
            assertThatThrownBy(() -> registry.resolvePinned(id, 1))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);
            assertThatThrownBy(() -> registry.contractMetadata(RetiredCodeV1.class))
                    .isInstanceOf(WorkflowProxyContractException.class);
            assertThat(scenario.stored(id, 1).status()).isEqualTo("RETIRED");

            // And the live version keeps its runtime and its contract mapping
            assertThat(registry.allRegistered())
                    .filteredOn(runtime -> runtime.plan().definitionId().equals(id))
                    .extracting(runtime -> runtime.plan().definitionVersion())
                    .containsExactly(2L);
            assertThat(registry.resolvePinned(id, 2).plan().planHash()).isEqualTo(liveHash);
            assertThat(registry.resolveCurrent(id).plan().definitionVersion()).isEqualTo(2);
            WorkflowContractMetadata liveMapping = registry.contractMetadata(RetiredCodeV2.class);
            assertThat(liveMapping.definitionId()).isEqualTo(id);
            assertThat(liveMapping.definitionVersion()).isEqualTo(2);
            assertThat(scenario.stored(id, 2).status()).isEqualTo("ACTIVE");
        }
    }

    @Test
    @DisplayName("startup names a code definition whose id breaks the key rules instead of a bare argument error")
    void namesTheDefinitionWhenCodeViolatesKeyRules(Vertx vertx) {
        String id = "mixed-bootstrap-key-rules\u0007id";
        WorkflowDefinition<?, ?> breaking = code(id, 7, KeyRulesCodeV7.class, "done");

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            // Given a code definition whose id holds a control character
            // When the application starts with it
            Boot refused = scenario.attempt(none(), unobserved(), List.of(breaking), null, 10);

            // Then startup fails with a definition error whose message names the escaped id and the version
            assertThat(refused.failure())
                    .as("startup with a code definition that breaks the key rules")
                    .isNotNull()
                    .isInstanceOfSatisfying(WorkflowDefinitionException.class, failure -> {
                        assertThat(failure.getMessage())
                                .contains("mixed-bootstrap-key-rules\\u0007id")
                                .doesNotContain(id)
                                .containsPattern("\\b7\\b");
                        assertThat(failure.getCause()).isInstanceOf(IllegalArgumentException.class);
                    });
        }
    }

    private static WorkflowDefinitionSource none() {
        return List::of;
    }

    private static BootObservation observed(String definitionId, long version, String planHash) {
        return new BootObservation(definitionId, version, planHash, true);
    }

    private static BootObservation unobserved() {
        return new BootObservation("mixed-bootstrap-unused", 1, "unused", false);
    }

    private static byte[] documentBytes(String definitionId, long version, Class<?> contract) {
        return documentText(definitionId, version, contract).getBytes(StandardCharsets.UTF_8);
    }

    /** Loads a tenant document through the service and activates it. */
    private static CompiledDefinitionRef publish(
            WorkflowDefinitionService service, String tenant, String definitionId, long version, Class<?> contract) {
        CompiledDefinitionRef loaded = await(service.load(
                documentBytes(definitionId, version, contract),
                DocumentFormat.YAML,
                source("published-" + tenant + "-" + version, Instant.parse("2026-10-18T12:00:00Z")),
                tenant));
        await(service.activate(new DefinitionKey(tenant, definitionId, version)));
        return loaded;
    }

    private static WorkflowDefinition<?, ?> code(String definitionId, long version, Class<?> contract, String endStep) {
        return new CodeWorkflow(definitionId, version, contract, endStep);
    }

    /** The plan hash that registering the definition produces, derived as the document loader derives it. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String planHashOf(WorkflowDefinition<?, ?> definition) {
        WorkflowBuilder builder = new WorkflowBuilder();
        definition.define(builder);
        return builder.build(
                        definition.definitionId(),
                        definition.definitionVersion(),
                        definition.stateType().getName())
                .planHash();
    }

    private static <T extends Throwable> T causeOf(Throwable failure, Class<T> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return type.cast(current);
        }
        return null;
    }

    private static String normalized(String sql) {
        return sql.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static long lockKey(String definitionId) {
        try {
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                            .digest(("vertique.workflow.definition:" + definitionId).getBytes(StandardCharsets.UTF_8)))
                    .getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static WorkflowInstance instanceRow(
            UUID id,
            String definitionId,
            long definitionVersion,
            String planHash,
            long optimisticVersion,
            WorkflowStatus status) {
        Instant now = Instant.now();
        return new WorkflowInstance(
                new WorkflowInstanceId(id),
                definitionId,
                definitionVersion,
                planHash,
                optimisticVersion,
                status,
                null,
                null,
                "start",
                null,
                null,
                null,
                "{}",
                null,
                null,
                now,
                now,
                null);
    }

    /** Which owner of the shared id holds the retired row, so the store lists the retired row before or after. */
    enum SharedIdOrder {
        RETIRED_OWNER_LISTED_FIRST("tenant-shared-a", "tenant-shared-b"),
        RETIRED_OWNER_LISTED_LAST("tenant-shared-b", "tenant-shared-a");

        private final String retiredTenant;
        private final String liveTenant;

        SharedIdOrder(String retiredTenant, String liveTenant) {
            this.retiredTenant = retiredTenant;
            this.liveTenant = liveTenant;
        }
    }

    private record CodeState(String value) {}

    private record CodeStart(String value) {}

    /** A code-first definition whose only step is its end step, so the end step's id decides the plan hash. */
    private static final class CodeWorkflow implements WorkflowDefinition<CodeState, Object> {

        private final String definitionId;
        private final long version;
        private final Class<?> contract;
        private final String endStep;

        private CodeWorkflow(String definitionId, long version, Class<?> contract, String endStep) {
            this.definitionId = definitionId;
            this.version = version;
            this.contract = contract;
            this.endStep = endStep;
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
                    .initialStep(endStep)
                    .complete(endStep);
        }
    }

    /** Collects the warnings logged while it is open. */
    private static final class LogCapture implements AutoCloseable {

        private final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private LogCapture() {
            appender.start();
            logger.addAppender(appender);
        }

        List<String> warnings() {
            return new ArrayList<>(appender.list)
                    .stream()
                            .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                            .map(ILoggingEvent::getFormattedMessage)
                            .toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** A transaction on its own connection that holds the shared admission lock of one definition id. */
    private static final class AdmissionHolder {

        private final SqlConnection connection;
        private final Transaction transaction;
        private boolean released;

        private AdmissionHolder(SqlConnection connection, Transaction transaction) {
            this.connection = connection;
            this.transaction = transaction;
        }

        static AdmissionHolder hold(DefinitionScenario scenario, String definitionId) {
            SqlConnection connection = await(scenario.admin().getConnection());
            Transaction transaction;
            try {
                transaction = await(connection.begin());
                await(connection
                        .preparedQuery("SELECT pg_advisory_xact_lock_shared($1)")
                        .execute(Tuple.of(lockKey(definitionId))));
            } catch (RuntimeException | Error failure) {
                await(connection.close());
                throw failure;
            }
            AdmissionHolder holder = new AdmissionHolder(connection, transaction);
            scenario.onClose(holder::release);
            return holder;
        }

        SqlConnection connection() {
            return connection;
        }

        void commit() {
            if (released) return;
            released = true;
            try {
                await(transaction.commit());
            } finally {
                await(connection.close());
            }
        }

        void release() {
            if (released) return;
            released = true;
            try {
                await(transaction.rollback().recover(ignored -> Future.succeededFuture()));
            } finally {
                await(connection.close());
            }
        }
    }

    /** A caller-owned transaction on its own connection, rolled back idempotently. */
    private static final class OpenTransaction {

        private final SqlConnection connection;
        private final Transaction transaction;
        private boolean done;

        private OpenTransaction(SqlConnection connection, Transaction transaction) {
            this.connection = connection;
            this.transaction = transaction;
        }

        static OpenTransaction begin(DefinitionScenario scenario) {
            SqlConnection connection = await(scenario.admin().getConnection());
            Transaction transaction;
            try {
                transaction = await(connection.begin());
            } catch (RuntimeException | Error failure) {
                await(connection.close());
                throw failure;
            }
            OpenTransaction open = new OpenTransaction(connection, transaction);
            scenario.onClose(open::rollback);
            return open;
        }

        SqlConnection connection() {
            return connection;
        }

        void rollback() {
            if (done) return;
            done = true;
            try {
                await(transaction.rollback().recover(ignored -> Future.succeededFuture()));
            } finally {
                await(connection.close());
            }
        }
    }

    private static WorkflowDefinitionSource packaged(String text, String label, Instant loadedAt) {
        return () -> List.of(new DefinitionResource(
                text.getBytes(StandardCharsets.UTF_8), DocumentFormat.YAML, source(label, loadedAt)));
    }
}
