// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.db.exception.OptimisticLockingFailureException;
import dev.vertique.db.exception.PessimisticLockingFailureException;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.CompiledDefinitionRef;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowPersistenceException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.query.PgWorkflowInstanceQueryService;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Verifies that retiring a managed definition is refused while it is cooling, pinned by live instances
 * or still executable, and that a successful retirement removes only the registration published for the
 * retired row.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DefinitionRetirementIT {

    @WorkflowContract
    public interface PinnedCaseV7 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface CountCase extends OrderDocumentSignals {}

    @WorkflowContract
    public interface CandidateCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface OwnerCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface StaticCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface RestoreCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface ReplacedCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface UnknownCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface DefaultIsolationCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface CandidatePinsCaseV1 extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_retirement_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    void refusesPinnedAndCoolingRetirement(Vertx vertx) {
        String id = "retirement-it-pinned-doc";
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, 7);

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(id);
            Node node = fixture.node();
            AppComponent app = node.app();
            long coolingSeconds = app.workflowDefinitionsConfig().maxStalenessSeconds();

            // Given version 7 loaded, activated and then deprecated through the service
            CompiledDefinitionRef loaded = await(app.definitionService()
                    .load(document(id, 7, PinnedCaseV7.class), DocumentFormat.YAML, source("pinned"), null));
            await(app.definitionService().activate(key));
            await(app.definitionService().deprecate(key));
            RuntimeWorkflow runtimeBefore =
                    app.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, 7));

            Stored deprecated = fixture.stored(id, 7);
            assertThat(deprecated.status()).isEqualTo("DEPRECATED");

            // When retirement is requested right after deprecation, while no instance is pinned
            Throwable justDeprecated = failureOf(app.definitionService().retire(key));

            // Then it fails typed as cooling for that key, and the row stays DEPRECATED and registered
            assertThat(justDeprecated).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.COOLING);
                assertThat(failure.key()).isEqualTo(key);
                assertThat(failure.getMessage()).contains(id);
            });
            assertThat(fixture.stored(id, 7)).isEqualTo(deprecated);
            assertThat(app.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, 7)))
                    .isSameAs(runtimeBefore);
            assertThat(app.workflowRegistry().isDeprecated(new DefinitionKey(WorkflowScopes.DEFAULT, id, 7)))
                    .isTrue();

            // When the deprecation is aged to just under the cooling window, by the database clock
            fixture.ageStatus(key, coolingSeconds - 10);
            Stored almostCooled = fixture.stored(id, 7);
            Throwable stillCooling = failureOf(app.definitionService().retire(key));

            // Then retirement is still refused as cooling and nothing changes
            assertThat(stillCooling).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.COOLING);
                assertThat(failure.key()).isEqualTo(key);
            });
            assertThat(fixture.stored(id, 7)).isEqualTo(almostCooled);

            // Given four live pins (running, waiting, compensating and an archived waiting row) and a completed one
            UUID running = fixture.insertInstance(id, 7, loaded.planHash(), "RUNNING", false);
            UUID waiting = fixture.insertInstance(id, 7, loaded.planHash(), "WAITING", false);
            UUID compensating = fixture.insertInstance(id, 7, loaded.planHash(), "COMPENSATING", false);
            UUID archivedWaiting = fixture.insertInstance(id, 7, loaded.planHash(), "WAITING", true);
            fixture.insertInstance(id, 7, loaded.planHash(), "COMPLETED", false);

            // When retirement is requested while the row is both still cooling and pinned by the four live rows
            Throwable coolingAndPinned = failureOf(app.definitionService().retire(key));

            // Then the cooling check wins over the pin count, and nothing changes
            assertThat(coolingAndPinned).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.COOLING);
                assertThat(failure.key()).isEqualTo(key);
            });
            assertThat(fixture.stored(id, 7)).isEqualTo(almostCooled);

            // When the deprecation is aged beyond the cooling window while the four pins remain
            fixture.ageStatus(key, coolingSeconds + 60);
            Stored cooled = fixture.stored(id, 7);
            Throwable allPinned = failureOf(app.definitionService().retire(key));

            // Then retirement fails typed with the key and all four live pins, archived one included
            assertThat(allPinned).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.PINNED_INSTANCES);
                assertThat(failure.key()).isEqualTo(key);
                assertThat(failure.getMessage()).contains(id).containsPattern("\\b4\\b");
            });
            assertThat(fixture.stored(id, 7)).isEqualTo(cooled);
            assertThat(app.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, 7)))
                    .isSameAs(runtimeBefore);

            // When the three unarchived pins finish
            fixture.setInstanceStatus(running, "COMPLETED");
            fixture.setInstanceStatus(waiting, "CANCELLED");
            fixture.setInstanceStatus(compensating, "COMPENSATED");
            Throwable archivedPin = failureOf(app.definitionService().retire(key));

            // Then the archived waiting row alone still pins the version
            assertThat(archivedPin).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.PINNED_INSTANCES);
                assertThat(failure.key()).isEqualTo(key);
                assertThat(failure.getMessage()).containsPattern("\\b1\\b");
            });
            assertThat(fixture.stored(id, 7)).isEqualTo(cooled);

            // When the archived pin finishes as well, so no live pin remains and the row is cooled
            fixture.setInstanceStatus(archivedWaiting, "EXPIRED");
            await(app.definitionService().retire(key));

            // Then the row is RETIRED with its key, hash and source intact
            Stored retired = fixture.stored(id, 7);
            assertThat(retired.status()).isEqualTo("RETIRED");
            assertThat(retired)
                    .usingRecursiveComparison()
                    .ignoringFields("status", "changedAt")
                    .isEqualTo(cooled);

            // And the runtime and its registered definition are removed from the local registry
            assertThatThrownBy(() -> app.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, 7)))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);
            assertThat(app.workflowRegistry().registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, 7)))
                    .isEmpty();
            assertThat(app.workflowRegistry().allRegistered())
                    .noneMatch(runtime -> runtime.plan().definitionId().equals(id));
        }
    }

    @Test
    void rejectsActiveRetirementAndCountsExactVersion(Vertx vertx) {
        String id = "retirement-it-count-doc";
        String otherId = "retirement-it-count-other-doc";
        DefinitionKey deprecatedKey = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
        DefinitionKey activeKey = new DefinitionKey(WorkflowScopes.DEFAULT, id, 2);
        DefinitionKey nextKey = new DefinitionKey(WorkflowScopes.DEFAULT, id, 3);

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(id, otherId);
            Node node = fixture.node();
            AppComponent app = node.app();
            WorkflowDefinitionRegistryStore store = app.definitionRegistryStore();
            PgWorkflowInstanceQueryService instances = new PgWorkflowInstanceQueryService(
                    new PgWorkflowInstanceRepository(fixture.admin(), new PgDbExceptionMapper()));

            // Given version 1 deprecated and version 2 ACTIVE, committed through the service
            CompiledDefinitionRef loaded1 = await(app.definitionService()
                    .load(document(id, 1, CountCase.class), DocumentFormat.YAML, source("count-v1"), null));
            await(app.definitionService().activate(deprecatedKey));
            CompiledDefinitionRef loaded2 = await(app.definitionService()
                    .load(document(id, 2, CountCase.class), DocumentFormat.YAML, source("count-v2"), null));
            await(app.definitionService().activate(activeKey));
            await(app.definitionService().deprecate(deprecatedKey));
            assertThat(fixture.stored(id, 1).status()).isEqualTo("DEPRECATED");
            assertThat(fixture.stored(id, 2).status()).isEqualTo("ACTIVE");

            // And version 1 has four live pins (one archived) and five kinds of finished instance, one archived
            fixture.insertInstance(id, 1, loaded1.planHash(), "RUNNING", false);
            fixture.insertInstance(id, 1, loaded1.planHash(), "WAITING", false);
            fixture.insertInstance(id, 1, loaded1.planHash(), "COMPENSATING", false);
            fixture.insertInstance(id, 1, loaded1.planHash(), "RUNNING", true);
            fixture.insertInstance(id, 1, loaded1.planHash(), "COMPLETED", false);
            fixture.insertInstance(id, 1, loaded1.planHash(), "FAILED", false);
            fixture.insertInstance(id, 1, loaded1.planHash(), "COMPENSATED", false);
            fixture.insertInstance(id, 1, loaded1.planHash(), "CANCELLED", false);
            fixture.insertInstance(id, 1, loaded1.planHash(), "EXPIRED", true);

            // And version 2 has two live pins and a finished instance, and another id has a live version 1 instance
            fixture.insertInstance(id, 2, loaded2.planHash(), "RUNNING", false);
            fixture.insertInstance(id, 2, loaded2.planHash(), "WAITING", false);
            fixture.insertInstance(id, 2, loaded2.planHash(), "COMPLETED", false);
            fixture.insertInstance(otherId, 1, loaded1.planHash(), "RUNNING", false);

            // When the live instances are counted per exact definition version
            long deprecatedCount = await(instances.countNonTerminal(id, 1));
            long activeCount = await(instances.countNonTerminal(id, 2));
            long unusedVersionCount = await(instances.countNonTerminal(id, 3));
            long otherIdCount = await(instances.countNonTerminal(otherId, 1));

            // Then each count covers exactly that version's running, waiting and compensating instances
            assertThat(deprecatedCount).isEqualTo(4);
            assertThat(activeCount).isEqualTo(2);
            assertThat(unusedVersionCount).isZero();
            assertThat(otherIdCount).isEqualTo(1);

            // When the ACTIVE version 2 is retired through the service and directly through the store
            Stored activeBefore = fixture.stored(id, 2);
            Throwable serviceRefusal = failureOf(app.definitionService().retire(activeKey));
            Throwable storeRefusal = failureOf(store.retire(activeKey));

            // Then both are refused as an invalid transition and the row is unchanged
            assertThat(serviceRefusal).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(failure.key()).isEqualTo(activeKey);
            });
            assertThat(storeRefusal).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(failure.key()).isEqualTo(activeKey);
            });
            assertThat(fixture.stored(id, 2)).isEqualTo(activeBefore);
            assertThat(app.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, 2)))
                    .isNotNull();

            // When version 3 is activated, version 2 becomes SUPERSEDED, and both are retired
            await(app.definitionService()
                    .load(document(id, 3, CountCase.class), DocumentFormat.YAML, source("count-v3"), null));
            await(app.definitionService().activate(nextKey));
            Stored supersededBefore = fixture.stored(id, 2);
            Stored nextBefore = fixture.stored(id, 3);
            assertThat(supersededBefore.status()).isEqualTo("SUPERSEDED");
            assertThat(nextBefore.status()).isEqualTo("ACTIVE");
            Throwable supersededRefusal = failureOf(app.definitionService().retire(activeKey));
            Throwable nextRefusal = failureOf(app.definitionService().retire(nextKey));

            // Then neither the SUPERSEDED nor the ACTIVE row can be retired and neither row changes
            assertThat(supersededRefusal)
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                        assertThat(failure.key()).isEqualTo(activeKey);
                    });
            assertThat(nextRefusal).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(failure.key()).isEqualTo(nextKey);
            });
            assertThat(fixture.stored(id, 2)).isEqualTo(supersededBefore);
            assertThat(fixture.stored(id, 3)).isEqualTo(nextBefore);

            // And the refused retirements changed none of the counts
            assertThat(await(instances.countNonTerminal(id, 1))).isEqualTo(4);
            assertThat(await(instances.countNonTerminal(id, 2))).isEqualTo(2);
        }
    }

    /** The managed retirement scenarios, one per way a runtime comes to be registered or replaced. */
    enum Retirement {
        UNRELATED_CANDIDATE("unrelated candidate", "retirement-it-candidate-doc", 1, CandidateCaseV1.class),
        LEGITIMATE_DYNAMIC_OWNER("legitimate dynamic owner", "retirement-it-owner-doc", 1, OwnerCaseV1.class),
        STATIC_DOCUMENT_ORIGIN("static document origin", "retirement-it-static-doc", 1, StaticCaseV1.class),
        CONTRIBUTED_CODE_ORIGIN(
                "contributed code origin", "definition-policy-code", 2, fixtureContract("RegistryProbeContract")),
        FRESH_GLOBAL_DOCUMENT_RESTORE(
                "fresh global document restore", "retirement-it-restore-doc", 1, RestoreCaseV1.class),
        COMPLETED_REPLACEMENT_BEFORE_OPERATION(
                "completed replacement before operation", "retirement-it-replaced-doc", 1, ReplacedCaseV1.class),
        DIRECT_UNKNOWN_PUBLICATION("direct unknown publication", "retirement-it-unknown-doc", 1, UnknownCaseV1.class);

        private final String label;
        private final String definitionId;
        private final long version;
        private final Class<?> contract;

        Retirement(String label, String definitionId, long version, Class<?> contract) {
            this.label = label;
            this.definitionId = definitionId;
            this.version = version;
            this.contract = contract;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Retirement.class)
    void retiresOnlyItsAttributedRegistration(Retirement retirement, Vertx vertx) {
        String id = retirement.definitionId;
        long version = retirement.version;
        Class<?> contract = retirement.contract;

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(id);

            switch (retirement) {
                case UNRELATED_CANDIDATE -> {
                    DefinitionKey candidateKey = new DefinitionKey("tenant-a", id, version);
                    DefinitionKey registeredKey = new DefinitionKey("tenant-b", id, version);
                    AppComponent app = fixture.node().app();

                    // Given tenant-a's candidate and tenant-b's document with the same id, version and source hash
                    String candidateHash = seedCandidate(app, "tenant-a", document(id, version, contract), "candidate");
                    String registeredHash =
                            seedCandidate(app, "tenant-b", document(id, version, contract), "registered");
                    assertThat(candidateHash).isEqualTo(registeredHash);

                    // And only tenant-b's document is activated, so it alone owns the registered runtime
                    await(app.definitionService().activate(registeredKey));
                    Stored candidateBefore = fixture.stored("tenant-a", id, version);
                    Stored registeredBefore = fixture.stored("tenant-b", id, version);
                    assertThat(candidateBefore.status()).isEqualTo("CANDIDATE");
                    assertThat(registeredBefore.status()).isEqualTo("ACTIVE");
                    assertThat(candidateBefore.planHash()).isEqualTo(registeredBefore.planHash());
                    RuntimeWorkflow runtimeBefore = app.workflowRegistry().resolve(registeredKey);
                    var definitionBefore = app.workflowRegistry().registeredDefinition(registeredKey);
                    assertThat(app.workflowRegistry().find(candidateKey)).isEmpty();
                    assertThat(fixture.instanceCount(id)).isZero();

                    // When tenant-a's unregistered candidate is retired through the service
                    await(app.definitionService().retire(candidateKey));

                    // Then the candidate row is RETIRED and tenant-b's row is exactly as it was
                    Stored candidateAfter = fixture.stored("tenant-a", id, version);
                    assertThat(candidateAfter.status()).isEqualTo("RETIRED");
                    assertThat(candidateAfter)
                            .usingRecursiveComparison()
                            .ignoringFields("status", "changedAt")
                            .isEqualTo(candidateBefore);
                    assertThat(fixture.stored("tenant-b", id, version)).isEqualTo(registeredBefore);

                    // And tenant-b's captured runtime, pinned resolution and registered definition are
                    // untouched
                    assertThat(app.workflowRegistry().resolve(registeredKey)).isSameAs(runtimeBefore);
                    assertThat(app.workflowRegistry().find(candidateKey)).isEmpty();
                    assertThat(app.workflowRegistry().registeredDefinition(registeredKey))
                            .isEqualTo(definitionBefore);
                    assertThat(app.workflowRegistry().isDeprecated(registeredKey))
                            .isFalse();
                    assertThat(app.workflowRegistry().allRegistered())
                            .filteredOn(runtime -> runtime.plan().definitionId().equals(id))
                            .containsExactly(runtimeBefore);
                }
                case LEGITIMATE_DYNAMIC_OWNER -> {
                    DefinitionKey candidateKey = new DefinitionKey("tenant-a", id, version);
                    DefinitionKey registeredKey = new DefinitionKey("tenant-b", id, version);
                    AppComponent app = fixture.node().app();
                    long coolingSeconds = app.workflowDefinitionsConfig().maxStalenessSeconds();

                    // Given tenant-a's candidate and tenant-b's document with the same id, version and source hash
                    String candidateHash = seedCandidate(app, "tenant-a", document(id, version, contract), "candidate");
                    String registeredHash =
                            seedCandidate(app, "tenant-b", document(id, version, contract), "registered");
                    assertThat(candidateHash).isEqualTo(registeredHash);

                    // And only tenant-b's document is activated, so it alone owns the registered runtime
                    await(app.definitionService().activate(registeredKey));
                    Stored candidateBefore = fixture.stored("tenant-a", id, version);
                    RuntimeWorkflow runtimeBefore = app.workflowRegistry().resolve(registeredKey);
                    assertThat(candidateBefore.status()).isEqualTo("CANDIDATE");

                    // When tenant-b's registered row is deprecated, cooled by the database clock and retired
                    await(app.definitionService().deprecate(registeredKey));
                    fixture.ageStatus(registeredKey, coolingSeconds + 60);
                    Stored cooledBefore = fixture.stored("tenant-b", id, version);
                    assertThat(cooledBefore.status()).isEqualTo("DEPRECATED");
                    await(app.definitionService().retire(registeredKey));

                    // Then the owner's row is RETIRED and the unrelated candidate row is exactly as it was
                    Stored registeredAfter = fixture.stored("tenant-b", id, version);
                    assertThat(registeredAfter.status()).isEqualTo("RETIRED");
                    assertThat(registeredAfter)
                            .usingRecursiveComparison()
                            .ignoringFields("status", "changedAt")
                            .isEqualTo(cooledBefore);
                    assertThat(fixture.stored("tenant-a", id, version)).isEqualTo(candidateBefore);

                    // And the owner's runtime, pinned resolution and registered definition are gone
                    assertThatThrownBy(() -> app.workflowRegistry().resolve(registeredKey))
                            .isInstanceOf(WorkflowVersionPinUnavailableException.class);
                    assertThat(app.workflowRegistry().registeredDefinition(registeredKey))
                            .isEmpty();
                    assertThat(app.workflowRegistry().allRegistered())
                            .noneMatch(runtime -> runtime.plan().definitionId().equals(id));

                    // When tenant-a's candidate, with the same id, version and contract, is then activated
                    await(app.definitionService().activate(candidateKey));

                    // Then it is registered as a new runtime, which a surviving registration would have prevented
                    assertThat(fixture.stored("tenant-a", id, version).status()).isEqualTo("ACTIVE");
                    RuntimeWorkflow replacement = app.workflowRegistry().resolve(candidateKey);
                    assertThat(replacement).isNotSameAs(runtimeBefore);
                    assertThat(replacement.plan().planHash()).isEqualTo(candidateHash);
                    assertThat(app.workflowRegistry().registeredDefinition(candidateKey))
                            .isPresent();
                    assertThat(app.workflowRegistry().isDeprecated(candidateKey))
                            .isFalse();
                }
                case STATIC_DOCUMENT_ORIGIN -> {
                    DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, version);
                    WorkflowDefinitionSource staticSource = () -> List.of(new DefinitionResource(
                            document(id, version, contract), DocumentFormat.YAML, source("static-origin")));

                    // Given a packaged static document that the definition bootstrap registered globally at startup
                    AppComponent app = fixture.node(staticSource, false).app();
                    long coolingSeconds = app.workflowDefinitionsConfig().maxStalenessSeconds();
                    RuntimeWorkflow runtimeBefore =
                            app.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, version));
                    assertThat(fixture.stored(id, version).status()).isEqualTo("ACTIVE");
                    assertThat(app.workflowRegistry()
                                    .registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, version)))
                            .isPresent();

                    // When its row is deprecated through the service, cooled by the database clock and retired
                    await(app.definitionService().deprecate(key));
                    fixture.ageStatus(key, coolingSeconds + 60);
                    Stored cooledBefore = fixture.stored(id, version);
                    assertThat(cooledBefore.status()).isEqualTo("DEPRECATED");
                    await(app.definitionService().retire(key));

                    // Then the row is RETIRED with its hash and source intact
                    Stored retired = fixture.stored(id, version);
                    assertThat(retired.status()).isEqualTo("RETIRED");
                    assertThat(retired)
                            .usingRecursiveComparison()
                            .ignoringFields("status", "changedAt")
                            .isEqualTo(cooledBefore);

                    // And the startup-registered runtime and its registered definition are removed
                    assertThatThrownBy(() -> app.workflowRegistry()
                                    .resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, version)))
                            .isInstanceOf(WorkflowVersionPinUnavailableException.class);
                    assertThat(app.workflowRegistry()
                                    .registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, version)))
                            .isEmpty();
                    assertThat(app.workflowRegistry().allRegistered())
                            .doesNotContain(runtimeBefore)
                            .noneMatch(runtime -> runtime.plan().definitionId().equals(id));
                }
                case CONTRIBUTED_CODE_ORIGIN -> {
                    DefinitionKey codeKey = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
                    DefinitionKey documentKey = new DefinitionKey(WorkflowScopes.DEFAULT, id, version);
                    Class<?> codeContract = fixtureContract("RegistryProbeContract");

                    // Given a code-first definition that an application contributor registered at startup, whose
                    // global CODE row startup recorded
                    AppComponent app = fixture.node(List::of, true).app();
                    long coolingSeconds = app.workflowDefinitionsConfig().maxStalenessSeconds();
                    RuntimeWorkflow contributed =
                            app.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1));
                    var codeDefinition = app.workflowRegistry()
                            .registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1));
                    Stored codeRecorded = fixture.stored(id, 1);
                    assertThat(codeRecorded.sourceType()).isEqualTo("code");
                    assertThat(codeRecorded.status()).isIn("ACTIVE", "SUPERSEDED");

                    // And a document of version 2 under the same id, published by the managed lifecycle
                    await(app.definitionService()
                            .load(document(id, version, contract), DocumentFormat.YAML, source("code-sibling"), null));
                    await(app.definitionService().activate(documentKey));
                    RuntimeWorkflow documentRuntime =
                            app.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, version));
                    var documentDefinition = app.workflowRegistry()
                            .registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, version));
                    Stored documentBefore = fixture.stored(id, version);
                    assertThat(documentBefore.status()).isEqualTo("ACTIVE");

                    // When the CODE row is deprecated through the service, cooled by the database clock and retired
                    await(app.definitionService().deprecate(codeKey));
                    fixture.ageStatus(codeKey, coolingSeconds + 60);
                    Stored cooledBefore = fixture.stored(id, 1);
                    assertThat(cooledBefore.status()).isEqualTo("DEPRECATED");
                    await(app.definitionService().retire(codeKey));

                    // Then the CODE row is RETIRED with its hash and provenance intact
                    Stored retired = fixture.stored(id, 1);
                    assertThat(retired.status()).isEqualTo("RETIRED");
                    assertThat(retired)
                            .usingRecursiveComparison()
                            .ignoringFields("status", "changedAt")
                            .isEqualTo(cooledBefore);

                    // And the contributed runtime and its registered definition are removed
                    assertThatThrownBy(() ->
                                    app.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1)))
                            .isInstanceOf(WorkflowVersionPinUnavailableException.class);
                    assertThat(app.workflowRegistry()
                                    .registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1)))
                            .isEmpty();
                    assertThat(app.workflowRegistry().allRegistered()).doesNotContain(contributed);

                    // And the document of the same id, which the removal must not take with it, is untouched
                    assertThat(app.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, version)))
                            .isSameAs(documentRuntime);
                    assertThat(app.workflowRegistry().resolveCurrent(id)).isSameAs(documentRuntime);
                    assertThat(app.workflowRegistry()
                                    .registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, version)))
                            .isEqualTo(documentDefinition);
                    assertThat(codeDefinition).hasValueSatisfying(definition -> assertThat(definition.contract())
                            .isEqualTo(codeContract));
                    assertThat(fixture.stored(id, version)).isEqualTo(documentBefore);
                }
                case FRESH_GLOBAL_DOCUMENT_RESTORE -> {
                    DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, version);

                    // Given a global document activated by one application graph, which is then shut down
                    Node first = fixture.node();
                    CompiledDefinitionRef loaded = await(first.app()
                            .definitionService()
                            .load(document(id, version, contract), DocumentFormat.YAML, source("restore"), null));
                    await(first.app().definitionService().activate(key));
                    first.close();

                    // And a fresh graph on the same database restores it at startup
                    AppComponent app = fixture.node().app();
                    long coolingSeconds = app.workflowDefinitionsConfig().maxStalenessSeconds();
                    RuntimeWorkflow restored =
                            app.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, version));
                    assertThat(restored.plan().planHash()).isEqualTo(loaded.planHash());
                    assertThat(fixture.stored(id, version).status()).isEqualTo("ACTIVE");

                    // When the restored row is deprecated through the service, cooled by the database clock and retired
                    await(app.definitionService().deprecate(key));
                    fixture.ageStatus(key, coolingSeconds + 60);
                    Stored cooledBefore = fixture.stored(id, version);
                    assertThat(cooledBefore.status()).isEqualTo("DEPRECATED");
                    await(app.definitionService().retire(key));

                    // Then the row is RETIRED with its hash and source intact
                    Stored retired = fixture.stored(id, version);
                    assertThat(retired.status()).isEqualTo("RETIRED");
                    assertThat(retired)
                            .usingRecursiveComparison()
                            .ignoringFields("status", "changedAt")
                            .isEqualTo(cooledBefore);

                    // And the restored runtime and its registered definition are removed
                    assertThatThrownBy(() -> app.workflowRegistry()
                                    .resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, version)))
                            .isInstanceOf(WorkflowVersionPinUnavailableException.class);
                    assertThat(app.workflowRegistry()
                                    .registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, version)))
                            .isEmpty();
                    assertThat(app.workflowRegistry().allRegistered()).doesNotContain(restored);
                }
                case COMPLETED_REPLACEMENT_BEFORE_OPERATION -> {
                    DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, version);
                    byte[] bytes = document(id, version, contract);
                    AppComponent app = fixture.node().app();
                    long coolingSeconds = app.workflowDefinitionsConfig().maxStalenessSeconds();

                    // Given a managed publication that was deprecated and cooled by the database clock
                    CompiledDefinitionRef loaded =
                            await(app.definitionService().load(bytes, DocumentFormat.YAML, source("replaced"), null));
                    await(app.definitionService().activate(key));
                    await(app.definitionService().deprecate(key));
                    fixture.ageStatus(key, coolingSeconds + 60);
                    Stored cooledBefore = fixture.stored(id, version);
                    assertThat(cooledBefore.status()).isEqualTo("DEPRECATED");
                    RuntimeWorkflow managedRuntime =
                            app.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, version));

                    // And a completed direct replacement: public unregister, then register of the same id and version
                    app.workflowRegistry().unregister(new DefinitionKey(WorkflowScopes.DEFAULT, id, version));
                    app.workflowRegistry()
                            .register(app.definitionPipeline()
                                    .load(new DefinitionResource(bytes, DocumentFormat.YAML, source("replaced")))
                                    .definition());
                    RuntimeWorkflow replacement =
                            app.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, version));
                    assertThat(replacement).isNotSameAs(managedRuntime);
                    assertThat(replacement.plan().planHash()).isEqualTo(loaded.planHash());

                    // When a managed retirement of that key is requested
                    Throwable refusal = failureOf(app.definitionService().retire(key));

                    // Then it fails asynchronously as an invalid transition naming the key
                    assertThat(refusal).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                        assertThat(failure.key()).isEqualTo(key);
                    });

                    // And no durable transition happened, so the status and its change time are unchanged
                    assertThat(fixture.stored(id, version)).isEqualTo(cooledBefore);

                    // And the replacement stays registered with its definition
                    assertThat(app.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, version)))
                            .isSameAs(replacement);
                    assertThat(app.workflowRegistry()
                                    .registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, version)))
                            .isPresent();
                }
                case DIRECT_UNKNOWN_PUBLICATION -> {
                    DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, version);
                    byte[] bytes = document(id, version, contract);
                    AppComponent app = fixture.node().app();
                    long coolingSeconds = app.workflowDefinitionsConfig().maxStalenessSeconds();

                    // Given a stored global row that is deprecated and cooled, without any managed publication here
                    CompiledDefinitionRef loaded =
                            await(app.definitionService().load(bytes, DocumentFormat.YAML, source("unknown"), null));
                    fixture.forceStatus(key, DefinitionStatus.DEPRECATED);
                    fixture.ageStatus(key, coolingSeconds + 60);
                    Stored cooledBefore = fixture.stored(id, version);
                    assertThat(cooledBefore.status()).isEqualTo("DEPRECATED");

                    // And a runtime with the same id, version and hash registered directly through the public registry
                    app.workflowRegistry()
                            .register(app.definitionPipeline()
                                    .load(new DefinitionResource(bytes, DocumentFormat.YAML, source("unknown")))
                                    .definition());
                    RuntimeWorkflow unknown =
                            app.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, version));
                    assertThat(unknown.plan().planHash()).isEqualTo(loaded.planHash());

                    // When a managed retirement of that key is requested
                    Throwable refusal = failureOf(app.definitionService().retire(key));

                    // Then it fails asynchronously as an invalid transition naming the key
                    assertThat(refusal).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                        assertThat(failure.key()).isEqualTo(key);
                    });

                    // And the row is unchanged, including its status change time
                    assertThat(fixture.stored(id, version)).isEqualTo(cooledBefore);

                    // And the unknown runtime stays registered with its definition
                    assertThat(app.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, version)))
                            .isSameAs(unknown);
                    assertThat(app.workflowRegistry()
                                    .registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, version)))
                            .isPresent();
                }
            }
        }
    }

    @WorkflowContract
    public interface StalePinCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface StaleGoneCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface RetryFirstCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface RetrySnapshotCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface RetryRollbackCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface MigrateAdmitCaseV2 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface SnapshotStartCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface SnapshotMigrateCaseV2 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface SnapshotEligibleCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface ConcurrentCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface ResurrectRaceCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface AutocommitCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface TimeoutAdvisoryCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface TimeoutRowCaseV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface TimeoutStaticCaseV1 extends OrderDocumentSignals {}

    @Test
    void staleNodeCannotCommitStartAfterRetirement(Vertx vertx) {
        String pinId = "retirement-it-stale-pin-doc";
        String goneId = "retirement-it-stale-gone-doc";
        DefinitionKey pinKey = new DefinitionKey(WorkflowScopes.DEFAULT, pinId, 1);
        DefinitionKey goneKey = new DefinitionKey(WorkflowScopes.DEFAULT, goneId, 1);

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(pinId, goneId);
            AppComponent nodeA = fixture.node().app();
            long coolingSeconds = nodeA.workflowDefinitionsConfig().maxStalenessSeconds();

            // Given two ACTIVE definitions, committed through node A before node B starts
            await(nodeA.definitionService()
                    .load(document(pinId, 1, StalePinCaseV1.class), DocumentFormat.YAML, source("stale-pin"), null));
            await(nodeA.definitionService().activate(pinKey));
            await(nodeA.definitionService()
                    .load(document(goneId, 1, StaleGoneCaseV1.class), DocumentFormat.YAML, source("stale-gone"), null));
            await(nodeA.definitionService().activate(goneKey));

            // And node B, whose registry resolves both versions and receives no later change: it has applied
            // nothing since it started, as when its propagation is paused
            AppComponent nodeB = fixture.node().app();
            TransactionalWorkflowOperations<SqlClient> staleOps = nodeB.transactionalOperations();
            assertThat(nodeB.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, pinId, 1)))
                    .isNotNull();
            assertThat(nodeB.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, goneId, 1)))
                    .isNotNull();

            // When B admits a start while the version is ACTIVE and leaves its transaction uncommitted
            Tx admitted = fixture.begin(null);
            await(staleOps.start(
                    startCommand(pinId, StalePinCaseV1.class, "stale-pin-start", 1), admitted.connection()));
            fixture.awaitAdvisory(pinId, "ShareLock", true, 1);

            // And deprecation is requested, so it queues behind the admission
            Future<Void> deprecation = nodeA.definitionService().deprecate(pinKey);
            fixture.awaitAdvisory(pinId, "ExclusiveLock", false, 1);

            // Then the deprecation has not completed while the admitted start is open
            assertThat(deprecation.isComplete()).isFalse();
            assertThat(fixture.stored(pinId, 1).status()).isEqualTo("ACTIVE");

            // When the start commits, the deprecation finishes and cooling elapses
            admitted.commit();
            await(deprecation);
            fixture.ageStatus(pinKey, coolingSeconds + 60);
            Throwable pinned = failureOf(nodeA.definitionService().retire(pinKey));

            // Then retirement sees the committed pin and fails typed with its count, leaving the row DEPRECATED
            assertThat(pinned).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.PINNED_INSTANCES);
                assertThat(failure.key()).isEqualTo(pinKey);
                assertThat(failure.getMessage()).containsPattern("\\b1\\b");
            });
            assertThat(fixture.stored(pinId, 1).status()).isEqualTo("DEPRECATED");
            assertThat(fixture.instanceCount(pinId)).isEqualTo(1);

            // Given the other definition deprecated, cooled and retired by node A while B still resolves it
            await(nodeA.definitionService().deprecate(goneKey));
            fixture.ageStatus(goneKey, coolingSeconds + 60);
            await(nodeA.definitionService().retire(goneKey));
            Stored retired = fixture.stored(goneId, 1);
            assertThat(retired.status()).isEqualTo("RETIRED");
            assertThat(nodeB.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, goneId, 1)))
                    .isNotNull();
            long historyBefore = fixture.historyCount();

            // When the stale node B starts it in a caller transaction
            Tx stale = fixture.begin(null);
            Throwable refusal = failureOf(staleOps.start(
                    startCommand(goneId, StaleGoneCaseV1.class, "stale-gone-start", 1), stale.connection()));
            stale.rollback();

            // Then the start is refused with a typed lifecycle failure naming the retired version
            assertThat(refusal).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.key()).isEqualTo(goneKey);
            });

            // And no instance, dedup or history row remains, and the row is untouched
            assertThat(fixture.instanceCount(goneId)).isZero();
            assertThat(fixture.dedupCount(goneId)).isZero();
            assertThat(fixture.historyCount()).isEqualTo(historyBefore);
            assertThat(fixture.stored(goneId, 1)).isEqualTo(retired);
        }
    }

    @Test
    void fencesMigrationAndRetryInCallerTransaction(Vertx vertx) {
        String retryFirstId = "retirement-it-retry-first-doc";
        String retrySnapshotId = "retirement-it-retry-snapshot-doc";
        String retryRollbackId = "retirement-it-retry-rollback-doc";
        String migrateAdmitId = "retirement-it-migrate-admit-doc";
        String retryRetiredId = "retirement-it-retry-retired-doc";
        String migrateRetiredId = "retirement-it-migrate-retired-doc";
        String eligibleId = "retirement-it-retry-eligible-doc";
        DefinitionKey retryFirstKey = new DefinitionKey(WorkflowScopes.DEFAULT, retryFirstId, 1);
        DefinitionKey retrySnapshotKey = new DefinitionKey(WorkflowScopes.DEFAULT, retrySnapshotId, 1);
        DefinitionKey retryRollbackKey = new DefinitionKey(WorkflowScopes.DEFAULT, retryRollbackId, 1);
        DefinitionKey migrateAdmitKey = new DefinitionKey(WorkflowScopes.DEFAULT, migrateAdmitId, 2);
        String seededHash = "e".repeat(64);

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(
                    retryFirstId,
                    retrySnapshotId,
                    retryRollbackId,
                    migrateAdmitId,
                    retryRetiredId,
                    migrateRetiredId,
                    eligibleId);
            AppComponent app = fixture.node().app();
            long coolingSeconds = app.workflowDefinitionsConfig().maxStalenessSeconds();
            PgWorkflowInstanceRepository repository = fixture.instances();

            // Given a DEPRECATED, cooled version with a FAILED instance; a caller retry (FAILED to RUNNING) is
            // admitted in READ COMMITTED while retirement is requested
            CompiledDefinitionRef retryFirst = await(app.definitionService()
                    .load(
                            document(retryFirstId, 1, RetryFirstCaseV1.class),
                            DocumentFormat.YAML,
                            source("retry-first"),
                            null));
            await(app.definitionService().activate(retryFirstKey));
            await(app.definitionService().deprecate(retryFirstKey));
            fixture.ageStatus(retryFirstKey, coolingSeconds + 60);
            UUID retryFirstInstance = fixture.insertInstance(retryFirstId, 1, retryFirst.planHash(), "FAILED", false);
            Tx retrying = fixture.begin(null);
            Integer admittedRows = await(repository.updateOptimistic(
                    instanceRow(retryFirstInstance, retryFirstId, 1, retryFirst.planHash(), 1, WorkflowStatus.RUNNING),
                    0,
                    retrying.connection()));
            fixture.awaitAdvisory(retryFirstId, "ShareLock", true, 1);

            // When retirement queues behind that admission and the retry then commits
            Future<Void> retirement = app.definitionService().retire(retryFirstKey);
            fixture.awaitAdvisory(retryFirstId, "ExclusiveLock", false, 1);
            assertThat(retirement.isComplete()).isFalse();
            retrying.commit();
            Throwable pinnedByRetry = failureOf(retirement);

            // Then the resurrected instance is a counted pin: retirement fails and the row stays DEPRECATED
            assertThat(admittedRows).isEqualTo(1);
            assertThat(pinnedByRetry).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.PINNED_INSTANCES);
                assertThat(failure.key()).isEqualTo(retryFirstKey);
            });
            assertThat(fixture.instance(retryFirstInstance).status()).isEqualTo("RUNNING");
            assertThat(fixture.stored(retryFirstId, 1).status()).isEqualTo("DEPRECATED");

            // Given a retirement in flight, holding the exclusive id lock and having set the row RETIRED by SQL
            // (the in-flight store transaction is simulated, so its commit order is controlled)
            fixture.seedDefinition(retryRetiredId, 1, seededHash, "DEPRECATED");
            UUID retiredRetryInstance = fixture.insertInstance(retryRetiredId, 1, seededHash, "FAILED", false);
            Tx inFlightRetirement = fixture.begin(null);
            await(inFlightRetirement
                    .connection()
                    .preparedQuery("SELECT pg_advisory_xact_lock($1)")
                    .execute(Tuple.of(lockKey(retryRetiredId))));
            await(inFlightRetirement
                    .connection()
                    .preparedQuery("UPDATE workflow_definitions SET status = 'RETIRED' WHERE definition_id = $1")
                    .execute(Tuple.of(retryRetiredId)));

            // When a caller retry is attempted first and queues behind the retirement, which then commits
            Tx lateRetry = fixture.begin(null);
            Future<Integer> lateRetryRows = repository.updateOptimistic(
                    instanceRow(retiredRetryInstance, retryRetiredId, 1, seededHash, 1, WorkflowStatus.RUNNING),
                    0,
                    lateRetry.connection());
            fixture.awaitAdvisory(retryRetiredId, "ShareLock", false, 1);
            inFlightRetirement.commit();
            Throwable lateRetryFailure = failureOf(lateRetryRows);
            lateRetry.rollback();

            // Then the admission read after the lock wait sees RETIRED and refuses: the pin is not resurrected
            assertThat(lateRetryFailure).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(failure.key()).isEqualTo(new DefinitionKey(WorkflowScopes.DEFAULT, retryRetiredId, 1));
            });
            assertThat(fixture.instance(retiredRetryInstance).status()).isEqualTo("FAILED");
            assertThat(fixture.instance(retiredRetryInstance).version()).isZero();

            // Given a DEPRECATED, cooled version with a FAILED instance, and a REPEATABLE READ snapshot taken now
            CompiledDefinitionRef retrySnapshot = await(app.definitionService()
                    .load(
                            document(retrySnapshotId, 1, RetrySnapshotCaseV1.class),
                            DocumentFormat.YAML,
                            source("retry-snapshot"),
                            null));
            await(app.definitionService().activate(retrySnapshotKey));
            await(app.definitionService().deprecate(retrySnapshotKey));
            fixture.ageStatus(retrySnapshotKey, coolingSeconds + 60);
            UUID snapshotInstance =
                    fixture.insertInstance(retrySnapshotId, 1, retrySnapshot.planHash(), "FAILED", false);
            Tx snapshot = fixture.begin("REPEATABLE READ");
            fixture.snapshot(snapshot);

            // When the version is retired by another transaction, then the snapshot's retry is attempted
            await(app.definitionService().retire(retrySnapshotKey));
            Throwable staleSnapshot = failureOf(repository.updateOptimistic(
                    instanceRow(
                            snapshotInstance, retrySnapshotId, 1, retrySnapshot.planHash(), 1, WorkflowStatus.RUNNING),
                    0,
                    snapshot.connection()));
            snapshot.rollback();

            // Then the stale snapshot fails with a serialization failure and the instance stays FAILED
            assertThat(causeOf(staleSnapshot, OptimisticLockingFailureException.class))
                    .as("a stale stronger snapshot must fail serialization: %s", staleSnapshot)
                    .isNotNull();
            assertThat(fixture.instance(snapshotInstance).status()).isEqualTo("FAILED");
            assertThat(fixture.stored(retrySnapshotId, 1).status()).isEqualTo("RETIRED");

            // Given a retry admitted in a caller transaction that is then rolled back
            CompiledDefinitionRef retryRollback = await(app.definitionService()
                    .load(
                            document(retryRollbackId, 1, RetryRollbackCaseV1.class),
                            DocumentFormat.YAML,
                            source("retry-rollback"),
                            null));
            await(app.definitionService().activate(retryRollbackKey));
            await(app.definitionService().deprecate(retryRollbackKey));
            fixture.ageStatus(retryRollbackKey, coolingSeconds + 60);
            UUID rollbackInstance =
                    fixture.insertInstance(retryRollbackId, 1, retryRollback.planHash(), "FAILED", false);
            Tx rolledBack = fixture.begin(null);
            Integer rollbackRows = await(repository.updateOptimistic(
                    instanceRow(
                            rollbackInstance, retryRollbackId, 1, retryRollback.planHash(), 1, WorkflowStatus.RUNNING),
                    0,
                    rolledBack.connection()));
            fixture.awaitAdvisory(retryRollbackId, "ShareLock", true, 1);

            // When the transaction rolls back and retirement is requested
            rolledBack.rollback();
            await(app.definitionService().retire(retryRollbackKey));

            // Then no pin was committed, so the version retires
            assertThat(rollbackRows).isEqualTo(1);
            assertThat(fixture.instance(rollbackInstance).status()).isEqualTo("FAILED");
            assertThat(fixture.stored(retryRollbackId, 1).status()).isEqualTo("RETIRED");

            // Given an ACTIVE target version 2 and a RUNNING instance pinned to an unmanaged version 1
            CompiledDefinitionRef migrateAdmit = await(app.definitionService()
                    .load(
                            document(migrateAdmitId, 2, MigrateAdmitCaseV2.class),
                            DocumentFormat.YAML,
                            source("migrate-admit"),
                            null));
            await(app.definitionService().activate(migrateAdmitKey));
            UUID migrating = fixture.insertInstance(migrateAdmitId, 1, "old-hash", "RUNNING", false);
            Tx migration = fixture.begin(null);
            Integer migratedRows = await(repository.migratePinAndState(
                    instanceRow(migrating, migrateAdmitId, 2, migrateAdmit.planHash(), 1, WorkflowStatus.RUNNING),
                    0,
                    migration.connection()));
            fixture.awaitAdvisory(migrateAdmitId, "ShareLock", true, 1);

            // When deprecation queues behind that migration, the migration commits and retirement follows cooling
            Future<Void> deprecateTarget = app.definitionService().deprecate(migrateAdmitKey);
            fixture.awaitAdvisory(migrateAdmitId, "ExclusiveLock", false, 1);
            assertThat(deprecateTarget.isComplete()).isFalse();
            migration.commit();
            await(deprecateTarget);
            fixture.ageStatus(migrateAdmitKey, coolingSeconds + 60);
            Throwable pinnedByMigration = failureOf(app.definitionService().retire(migrateAdmitKey));

            // Then the migrated instance is a counted pin on version 2 and retirement fails
            assertThat(migratedRows).isEqualTo(1);
            assertThat(fixture.instance(migrating).definitionVersion()).isEqualTo(2);
            assertThat(pinnedByMigration)
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.PINNED_INSTANCES);
                        assertThat(failure.key()).isEqualTo(migrateAdmitKey);
                    });

            // Given a RETIRED target version 2 (seeded by SQL, as a retired row has no other source here)
            // and a RUNNING instance pinned to version 1
            fixture.seedDefinition(migrateRetiredId, 2, seededHash, "RETIRED");
            UUID sourcePin = fixture.insertInstance(migrateRetiredId, 1, "old-hash", "RUNNING", false);
            Instance sourceBefore = fixture.instance(sourcePin);
            long migrationHistoryBefore = fixture.historyCount();
            Tx lateMigration = fixture.begin(null);

            // When a migration onto the retired version is attempted
            Throwable lateMigrationFailure = failureOf(repository.migratePinAndState(
                    instanceRow(sourcePin, migrateRetiredId, 2, seededHash, 1, WorkflowStatus.RUNNING),
                    0,
                    lateMigration.connection()));
            lateMigration.rollback();

            // Then it is refused, and the source pin and history are unchanged
            assertThat(lateMigrationFailure)
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                        assertThat(failure.key())
                                .isEqualTo(new DefinitionKey(WorkflowScopes.DEFAULT, migrateRetiredId, 2));
                    });
            assertThat(fixture.instance(sourcePin)).isEqualTo(sourceBefore);
            assertThat(fixture.historyCount()).isEqualTo(migrationHistoryBefore);

            // Given an unchanged, eligible managed row visible to a REPEATABLE READ snapshot
            fixture.seedDefinition(eligibleId, 1, seededHash, "ACTIVE");
            UUID eligibleInstance = fixture.insertInstance(eligibleId, 1, seededHash, "FAILED", false);
            Tx eligible = fixture.begin("REPEATABLE READ");
            fixture.snapshot(eligible);

            // When the retry is attempted from that snapshot
            Integer eligibleRows = await(repository.updateOptimistic(
                    instanceRow(eligibleInstance, eligibleId, 1, seededHash, 1, WorkflowStatus.RUNNING),
                    0,
                    eligible.connection()));
            eligible.commit();

            // Then it succeeds, because nothing it read has changed
            assertThat(eligibleRows).isEqualTo(1);
            assertThat(fixture.instance(eligibleInstance).status()).isEqualTo("RUNNING");
        }
    }

    @Test
    void rejectsSnapshotBeforeFirstDefinitionInsertion(Vertx vertx) {
        String startId = "retirement-it-snapshot-start-doc";
        String migrateId = "retirement-it-snapshot-migrate-doc";
        String eligibleId = "retirement-it-snapshot-eligible-doc";
        String unmanagedId = "retirement-it-snapshot-unmanaged";
        DefinitionKey startKey = new DefinitionKey(WorkflowScopes.DEFAULT, startId, 1);
        DefinitionKey migrateKey = new DefinitionKey(WorkflowScopes.DEFAULT, migrateId, 2);
        String seededHash = "e".repeat(64);

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(startId, migrateId, eligibleId, unmanagedId);
            AppComponent nodeA = fixture.node().app();
            long coolingSeconds = nodeA.workflowDefinitionsConfig().maxStalenessSeconds();
            PgWorkflowInstanceRepository repository = fixture.instances();

            // Given an eligible managed document, activated through the service, and an instance pinned to an
            // unmanaged version, both committed
            CompiledDefinitionRef eligibleLoaded = await(nodeA.definitionService()
                    .load(
                            document(eligibleId, 1, SnapshotEligibleCaseV1.class),
                            DocumentFormat.YAML,
                            source("snapshot-eligible"),
                            null));
            await(nodeA.definitionService().activate(new DefinitionKey(WorkflowScopes.DEFAULT, eligibleId, 1)));
            UUID sourcePin = fixture.insertInstance(migrateId, 1, "old-hash", "RUNNING", false);
            Instance sourceBefore = fixture.instance(sourcePin);

            // And two REPEATABLE READ snapshots established before either definition id has any row
            Tx startSnapshot = fixture.begin("REPEATABLE READ");
            fixture.snapshot(startSnapshot);
            Tx migrateSnapshot = fixture.begin("REPEATABLE READ");
            fixture.snapshot(migrateSnapshot);

            // And another connection then creates and activates both ids, and a second node propagates them
            await(nodeA.definitionService()
                    .load(
                            document(startId, 1, SnapshotStartCaseV1.class),
                            DocumentFormat.YAML,
                            source("snapshot-start"),
                            null));
            await(nodeA.definitionService().activate(startKey));
            CompiledDefinitionRef migrateLoaded = await(nodeA.definitionService()
                    .load(
                            document(migrateId, 2, SnapshotMigrateCaseV2.class),
                            DocumentFormat.YAML,
                            source("snapshot-migrate"),
                            null));
            await(nodeA.definitionService().activate(migrateKey));
            AppComponent nodeB = fixture.node().app();
            TransactionalWorkflowOperations<SqlClient> staleOps = nodeB.transactionalOperations();
            assertThat(nodeB.workflowRegistry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, startId, 1)))
                    .isNotNull();

            // And both ids are deprecated, cooled and retired, while node B still resolves the start version
            await(nodeA.definitionService().deprecate(startKey));
            fixture.ageStatus(startKey, coolingSeconds + 60);
            await(nodeA.definitionService().retire(startKey));
            await(nodeA.definitionService().deprecate(migrateKey));
            fixture.ageStatus(migrateKey, coolingSeconds + 60);
            await(nodeA.definitionService().retire(migrateKey));
            assertThat(fixture.stored(startId, 1).status()).isEqualTo("RETIRED");
            assertThat(fixture.stored(migrateId, 2).status()).isEqualTo("RETIRED");
            long historyBefore = fixture.historyCount();

            // When a start and a migration are attempted from the old snapshots
            Throwable startRefusal = failureOf(staleOps.start(
                    startCommand(startId, SnapshotStartCaseV1.class, "snapshot-start", 1), startSnapshot.connection()));
            startSnapshot.rollback();
            Throwable migrateRefusal = failureOf(repository.migratePinAndState(
                    instanceRow(sourcePin, migrateId, 2, migrateLoaded.planHash(), 1, WorkflowStatus.RUNNING),
                    0,
                    migrateSnapshot.connection()));
            migrateSnapshot.rollback();

            // Then both are refused as an invalid transition naming the stronger-isolation ambiguity
            assertThat(startRefusal).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(failure.key().definitionId()).isEqualTo(startId);
            });
            assertThat(migrateRefusal).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(failure.key().definitionId()).isEqualTo(migrateId);
            });

            // And no instance, dedup or history row remains, and the source pin is unchanged
            assertThat(fixture.instanceCount(startId)).isZero();
            assertThat(fixture.dedupCount(startId)).isZero();
            assertThat(fixture.historyCount()).isEqualTo(historyBefore);
            assertThat(fixture.instance(sourcePin)).isEqualTo(sourceBefore);

            // When a truly unmanaged id is started in READ COMMITTED
            Tx unmanaged = fixture.begin(null);
            await(repository.insert(
                    instanceRow(UUID.randomUUID(), unmanagedId, 1, seededHash, 0, WorkflowStatus.RUNNING),
                    unmanaged.connection()));
            unmanaged.commit();

            // Then the legacy path succeeds
            assertThat(fixture.instanceCount(unmanagedId)).isEqualTo(1);

            // When the same unmanaged id is started from an empty REPEATABLE READ snapshot
            Tx emptySnapshot = fixture.begin("REPEATABLE READ");
            fixture.snapshot(emptySnapshot);
            Throwable emptyRefusal = failureOf(repository.insert(
                    instanceRow(UUID.randomUUID(), unmanagedId, 1, seededHash, 0, WorkflowStatus.RUNNING),
                    emptySnapshot.connection()));
            emptySnapshot.rollback();

            // Then it is refused as an invalid transition, because absence cannot be told from a new managed id
            assertThat(emptyRefusal).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
            });
            assertThat(fixture.instanceCount(unmanagedId)).isEqualTo(1);

            // When an instance is created from a snapshot, taken after every node settled, that sees the unchanged
            // eligible managed row
            Tx eligibleSnapshot = fixture.begin("REPEATABLE READ");
            fixture.snapshot(eligibleSnapshot);
            await(repository.insert(
                    instanceRow(UUID.randomUUID(), eligibleId, 1, eligibleLoaded.planHash(), 0, WorkflowStatus.RUNNING),
                    eligibleSnapshot.connection()));
            eligibleSnapshot.commit();

            // Then it succeeds
            assertThat(fixture.instanceCount(eligibleId)).isEqualTo(1);
        }
    }

    @Test
    void admitsTwoStartsConcurrentlyWhileRetireWaits(Vertx vertx) {
        String id = "retirement-it-concurrent-doc";
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(id);
            AppComponent app = fixture.node().app();
            long coolingSeconds = app.workflowDefinitionsConfig().maxStalenessSeconds();
            TransactionalWorkflowOperations<SqlClient> ops = app.transactionalOperations();

            // Given one ACTIVE definition and two caller transactions
            await(app.definitionService()
                    .load(document(id, 1, ConcurrentCaseV1.class), DocumentFormat.YAML, source("concurrent"), null));
            await(app.definitionService().activate(key));
            Tx first = fixture.begin(null);
            Tx second = fixture.begin(null);

            // When both starts, with distinct instance and idempotency keys, reach their admission barriers
            await(ops.start(startCommand(id, ConcurrentCaseV1.class, "concurrent-first", 1), first.connection()));
            await(ops.start(startCommand(id, ConcurrentCaseV1.class, "concurrent-second", 1), second.connection()));

            // Then both hold the shared id lock at once, before any exclusive request exists
            fixture.awaitAdvisory(id, "ShareLock", true, 2);
            assertThat(fixture.advisoryCount(id, "ExclusiveLock", false)).isZero();

            // When deprecation is requested only now
            Future<Void> deprecation = app.definitionService().deprecate(key);
            fixture.awaitAdvisory(id, "ExclusiveLock", false, 1);

            // Then it waits for both starts, including after the first one has committed
            assertThat(deprecation.isComplete()).isFalse();
            first.commit();
            fixture.awaitAdvisory(id, "ShareLock", true, 1);
            assertThat(deprecation.isComplete()).isFalse();
            assertThat(fixture.stored(id, 1).status()).isEqualTo("ACTIVE");

            // When the second start commits, the deprecation finishes and cooling elapses
            second.commit();
            await(deprecation);
            fixture.ageStatus(key, coolingSeconds + 60);
            Throwable pinned = failureOf(app.definitionService().retire(key));

            // Then retirement fails typed, counting both committed live pins
            assertThat(pinned).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.PINNED_INSTANCES);
                assertThat(failure.key()).isEqualTo(key);
                assertThat(failure.getMessage()).containsPattern("\\b2\\b");
            });
            assertThat(fixture.instanceCount(id)).isEqualTo(2);
            assertThat(fixture.stored(id, 1).status()).isEqualTo("DEPRECATED");
        }
    }

    @Test
    void guardsTerminalResurrectionAndPreservesOptimisticZero(Vertx vertx) {
        String hash = "a".repeat(64);
        String otherHash = "b".repeat(64);
        String raceId = "retirement-it-resurrect-race-doc";
        String barrierId = "retirement-it-resurrect-barrier-doc";
        DefinitionKey raceKey = new DefinitionKey(WorkflowScopes.DEFAULT, raceId, 1);

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(
                    raceId,
                    barrierId,
                    "retirement-it-resurrect-active-doc",
                    "retirement-it-resurrect-superseded-doc",
                    "retirement-it-resurrect-deprecated-doc",
                    "retirement-it-resurrect-retired-doc",
                    "retirement-it-resurrect-candidate-doc",
                    "retirement-it-resurrect-activating-doc",
                    "retirement-it-resurrect-oldhash-doc",
                    "retirement-it-resurrect-zero-doc",
                    "retirement-it-resurrect-fast-doc");
            AppComponent app = fixture.node().app();
            long coolingSeconds = app.workflowDefinitionsConfig().maxStalenessSeconds();
            PgWorkflowInstanceRepository repository = fixture.instances();

            // Given a matching definition row in each status and a FAILED instance pinned to its hash
            List<String> eligibleStatuses = List.of("ACTIVE", "SUPERSEDED", "DEPRECATED");
            List<String> refusedStatuses = List.of("RETIRED", "CANDIDATE", "ACTIVATING");
            for (String status : eligibleStatuses) {
                String id = "retirement-it-resurrect-" + status.toLowerCase() + "-doc";
                fixture.seedDefinition(id, 1, hash, status);
                UUID failed = fixture.insertInstance(id, 1, hash, "FAILED", false);

                // When the FAILED instance is retried in a caller transaction
                Tx retry = fixture.begin(null);
                Integer rows = await(repository.updateOptimistic(
                        instanceRow(failed, id, 1, hash, 1, WorkflowStatus.RUNNING), 0, retry.connection()));
                retry.commit();

                // Then a matching ACTIVE, SUPERSEDED or DEPRECATED owner lets it resurrect
                assertThat(rows).as("resurrection against a %s row", status).isEqualTo(1);
                assertThat(fixture.instance(failed).status()).isEqualTo("RUNNING");
            }
            for (String status : refusedStatuses) {
                String id = "retirement-it-resurrect-" + status.toLowerCase() + "-doc";
                fixture.seedDefinition(id, 1, hash, status);
                UUID failed = fixture.insertInstance(id, 1, hash, "FAILED", false);

                // When the FAILED instance is retried against a row that never authorizes resurrection
                Tx retry = fixture.begin(null);
                Throwable refusal = failureOf(repository.updateOptimistic(
                        instanceRow(failed, id, 1, hash, 1, WorkflowStatus.RUNNING), 0, retry.connection()));
                retry.rollback();

                // Then it is refused and the instance stays FAILED
                assertThat(refusal)
                        .as("resurrection against a %s row", status)
                        .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                            assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                            assertThat(failure.key()).isEqualTo(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1));
                        });
                assertThat(fixture.instance(failed).status()).isEqualTo("FAILED");
            }

            // Given a DEPRECATED row whose hash differs from the FAILED instance's pinned hash
            String oldHashId = "retirement-it-resurrect-oldhash-doc";
            fixture.seedDefinition(oldHashId, 1, otherHash, "DEPRECATED");
            UUID oldHashed = fixture.insertInstance(oldHashId, 1, hash, "FAILED", false);

            // When the instance is retried
            Tx oldHashRetry = fixture.begin(null);
            Throwable hashRefusal = failureOf(repository.updateOptimistic(
                    instanceRow(oldHashed, oldHashId, 1, hash, 1, WorkflowStatus.RUNNING),
                    0,
                    oldHashRetry.connection()));
            oldHashRetry.rollback();

            // Then the hash mismatch refuses it as drift between this node and the committed definition
            assertThat(hashRefusal).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
                assertThat(failure.key()).isEqualTo(new DefinitionKey(WorkflowScopes.DEFAULT, oldHashId, 1));
            });
            assertThat(fixture.instance(oldHashed).status()).isEqualTo("FAILED");

            // Given a RETIRED row, a FAILED instance and an expected version that does not match its row
            String zeroId = "retirement-it-resurrect-zero-doc";
            fixture.seedDefinition(zeroId, 1, hash, "RETIRED");
            UUID mismatched = fixture.insertInstance(zeroId, 1, hash, "FAILED", false);

            // When the retry carries the stale expected version, and another targets an instance that is absent
            Tx zeroTx = fixture.begin(null);
            Integer staleRows = await(repository.updateOptimistic(
                    instanceRow(mismatched, zeroId, 1, hash, 6, WorkflowStatus.RUNNING), 5, zeroTx.connection()));
            Integer absentRows = await(repository.updateOptimistic(
                    instanceRow(UUID.randomUUID(), zeroId, 1, hash, 1, WorkflowStatus.RUNNING),
                    0,
                    zeroTx.connection()));
            zeroTx.rollback();

            // Then the optimistic result is zero rather than a refusal, and the row is unchanged
            assertThat(staleRows).isZero();
            assertThat(absentRows).isZero();
            assertThat(fixture.instance(mismatched).status()).isEqualTo("FAILED");

            // Given a RETIRED row and a WAITING instance that was already a counted pin
            String fastId = "retirement-it-resurrect-fast-doc";
            fixture.seedDefinition(fastId, 1, hash, "RETIRED");
            UUID waiting = fixture.insertInstance(fastId, 1, hash, "WAITING", false);

            // When it advances through an ordinary nonterminal update
            Tx fastTx = fixture.begin(null);
            Integer fastRows = await(repository.updateOptimistic(
                    instanceRow(waiting, fastId, 1, hash, 1, WorkflowStatus.RUNNING), 0, fastTx.connection()));
            fastTx.commit();

            // Then the fast path stays available and needs no admission
            assertThat(fastRows).isEqualTo(1);
            assertThat(fixture.instance(waiting).status()).isEqualTo("RUNNING");

            // Given a DEPRECATED, cooled managed version and a FAILED instance retried into COMPENSATING
            CompiledDefinitionRef race = await(app.definitionService()
                    .load(
                            document(raceId, 1, ResurrectRaceCaseV1.class),
                            DocumentFormat.YAML,
                            source("resurrect-race"),
                            null));
            await(app.definitionService().activate(raceKey));
            await(app.definitionService().deprecate(raceKey));
            fixture.ageStatus(raceKey, coolingSeconds + 60);
            UUID raceInstance = fixture.insertInstance(raceId, 1, race.planHash(), "FAILED", false);
            Tx compensating = fixture.begin(null);
            Integer compensatingRows = await(repository.updateOptimistic(
                    instanceRow(raceInstance, raceId, 1, race.planHash(), 1, WorkflowStatus.COMPENSATING),
                    0,
                    compensating.connection()));
            fixture.awaitAdvisory(raceId, "ShareLock", true, 1);

            // When retirement queues behind that admission and the transaction commits
            Future<Void> retirement = app.definitionService().retire(raceKey);
            fixture.awaitAdvisory(raceId, "ExclusiveLock", false, 1);
            compensating.commit();
            Throwable pinned = failureOf(retirement);

            // Then the COMPENSATING instance is a counted pin and retirement fails
            assertThat(compensatingRows).isEqualTo(1);
            assertThat(pinned).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.PINNED_INSTANCES);
                assertThat(failure.key()).isEqualTo(raceKey);
            });
            assertThat(fixture.instance(raceInstance).status()).isEqualTo("COMPENSATING");

            // Given a retirement in flight that holds the exclusive id lock and set a DEPRECATED row RETIRED by SQL
            fixture.seedDefinition(barrierId, 1, hash, "DEPRECATED");
            UUID barrierInstance = fixture.insertInstance(barrierId, 1, hash, "FAILED", false);
            Tx inFlight = fixture.begin(null);
            await(inFlight.connection()
                    .preparedQuery("SELECT pg_advisory_xact_lock($1)")
                    .execute(Tuple.of(lockKey(barrierId))));
            await(inFlight.connection()
                    .preparedQuery("UPDATE workflow_definitions SET status = 'RETIRED' WHERE definition_id = $1")
                    .execute(Tuple.of(barrierId)));

            // When a retry into COMPENSATING queues behind it and the retirement commits
            Tx late = fixture.begin(null);
            Future<Integer> lateRows = repository.updateOptimistic(
                    instanceRow(barrierInstance, barrierId, 1, hash, 1, WorkflowStatus.COMPENSATING),
                    0,
                    late.connection());
            fixture.awaitAdvisory(barrierId, "ShareLock", false, 1);
            inFlight.commit();
            Throwable lateFailure = failureOf(lateRows);
            late.rollback();

            // Then the committed retirement wins and the instance is not resurrected
            assertThat(lateFailure).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(failure.key()).isEqualTo(new DefinitionKey(WorkflowScopes.DEFAULT, barrierId, 1));
            });
            assertThat(fixture.instance(barrierInstance).status()).isEqualTo("FAILED");
        }
    }

    @Test
    void rejectsAutocommitClientBeforePinWrites(Vertx vertx) {
        String id = "retirement-it-autocommit-doc";
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(id);
            AppComponent app = fixture.node().app();
            TransactionalWorkflowOperations<SqlClient> ops = app.transactionalOperations();
            PgWorkflowInstanceRepository repository = fixture.instances();
            Pool pool = app.pool();

            // Given one ACTIVE definition and the five kinds of invalid client, built from real Vert.x objects
            CompiledDefinitionRef loaded = await(app.definitionService()
                    .load(document(id, 1, AutocommitCaseV1.class), DocumentFormat.YAML, source("autocommit"), null));
            await(app.definitionService().activate(key));
            String hash = loaded.planHash();

            SqlConnection bare = fixture.connection();
            SqlConnection completed = fixture.connection();
            Transaction completedTransaction = await(completed.begin());
            await(completedTransaction.commit());
            SqlConnection rawBegin = fixture.connection();
            await(rawBegin.query("BEGIN").execute());
            SqlConnection wrapped = fixture.connection();
            await(wrapped.begin());
            SqlClient opaque = (SqlClient) Proxy.newProxyInstance(
                    SqlClient.class.getClassLoader(), new Class<?>[] {SqlClient.class}, (proxy, method, args) -> {
                        try {
                            return method.invoke(wrapped, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            List<String> names = List.of("pool", "bare", "completed", "raw begin", "opaque");
            List<SqlClient> invalidClients = List.of(pool, bare, completed, rawBegin, opaque);

            // And the actual transaction states show what the validator can inspect
            assertThat(pool).isNotInstanceOf(SqlConnection.class);
            assertThat(bare.transaction()).isNull();
            assertThat(completed.transaction() == null
                            || completed.transaction().completion().isComplete())
                    .isTrue();
            assertThat(rawBegin.transaction()).isNull();
            assertThat(opaque).isNotInstanceOf(SqlConnection.class);
            assertThat(wrapped.transaction()).isNotNull();
            assertThat(wrapped.transaction().completion().isComplete()).isFalse();

            // And a FAILED instance and a RUNNING instance on an unmanaged version, for the direct Pg calls
            UUID failed = fixture.insertInstance(id, 1, hash, "FAILED", false);
            UUID repinned = fixture.insertInstance(id, 1, "old-hash", "RUNNING", false);
            Instance failedBefore = fixture.instance(failed);
            Instance repinnedBefore = fixture.instance(repinned);
            long baselineInstances = fixture.instanceCount(id);
            long baselineHistory = fixture.historyCount();

            // When the real engine start is invoked with each invalid client and a fresh dedup key
            for (int i = 0; i < invalidClients.size(); i++) {
                String dedupKey = "autocommit-" + i;
                Throwable engineFailure = failureOf(
                        ops.start(startCommand(id, AutocommitCaseV1.class, dedupKey, 1), invalidClients.get(i)));

                // Then it fails with an IllegalArgumentException and leaves no dedup, instance or history row
                assertThat(engineFailure)
                        .as("engine start with %s client", names.get(i))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThat(fixture.dedupCount(id))
                        .as("dedup rows after %s", names.get(i))
                        .isZero();
                assertThat(fixture.instanceCount(id)).isEqualTo(baselineInstances);
                assertThat(fixture.historyCount()).isEqualTo(baselineHistory);
            }

            // And when the Pg repository is called directly with each invalid client
            for (int i = 0; i < invalidClients.size(); i++) {
                SqlClient client = invalidClients.get(i);
                Throwable insertFailure = failureOf(repository.insert(
                        instanceRow(UUID.randomUUID(), id, 1, hash, 0, WorkflowStatus.RUNNING), client));
                Throwable resurrectFailure = failureOf(repository.updateOptimistic(
                        instanceRow(failed, id, 1, hash, 1, WorkflowStatus.RUNNING), 0, client));
                Throwable migrateFailure = failureOf(repository.migratePinAndState(
                        instanceRow(repinned, id, 1, hash, 1, WorkflowStatus.RUNNING), 0, client));

                // Then each fails with an IllegalArgumentException before any SQL, so no row changes
                assertThat(insertFailure)
                        .as("insert with %s client", names.get(i))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThat(resurrectFailure)
                        .as("resurrection with %s client", names.get(i))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThat(migrateFailure)
                        .as("migration with %s client", names.get(i))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThat(fixture.instanceCount(id)).isEqualTo(baselineInstances);
                assertThat(fixture.instance(failed)).isEqualTo(failedBefore);
                assertThat(fixture.instance(repinned)).isEqualTo(repinnedBefore);
            }

            // When each rejected start is retried with the same key in a genuine transaction
            for (int i = 0; i < invalidClients.size(); i++) {
                String dedupKey = "autocommit-" + i;
                WorkflowInstanceId retried = await(pool.withTransaction(
                        connection -> ops.start(startCommand(id, AutocommitCaseV1.class, dedupKey, 1), connection)));

                // Then a real instance is created and the dedup slot resolves to it
                assertThat(fixture.instanceExists(retried.value())).isTrue();
                assertThat(fixture.dedupTarget(id, dedupKey)).isEqualTo(retried.value());
            }
            assertThat(fixture.instanceCount(id)).isEqualTo(baselineInstances + invalidClients.size());

            // Given a genuine active Vert.x transaction
            SqlConnection activeConnection = fixture.connection();
            await(activeConnection.begin());
            assertThat(activeConnection.transaction()).isNotNull();
            assertThat(activeConnection.transaction().completion().isComplete()).isFalse();

            // When the engine starts an instance in it
            WorkflowInstanceId accepted = await(
                    ops.start(startCommand(id, AutocommitCaseV1.class, "autocommit-active", 1), activeConnection));

            // Then the start is accepted and follows shared admission, holding the id lock until commit
            fixture.awaitAdvisory(id, "ShareLock", true, 1);
            assertThat(accepted).isNotNull();
            await(activeConnection.transaction().rollback());
            assertThat(fixture.instanceExists(accepted.value())).isFalse();
        }
    }

    @Test
    void timesOutQueuedMutationAndResumesAdmissions(Vertx vertx) {
        String advisoryId = "retirement-it-timeout-advisory-doc";
        String rowId = "retirement-it-timeout-row-doc";
        String staticId = "retirement-it-timeout-static-doc";
        DefinitionKey advisoryKey = new DefinitionKey(WorkflowScopes.DEFAULT, advisoryId, 1);
        DefinitionKey rowKey = new DefinitionKey(WorkflowScopes.DEFAULT, rowId, 1);

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(advisoryId, rowId, staticId);
            WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();
            // A single-connection pool makes a leaked LOCAL setting or aborted transaction visible on reuse
            AppComponent app = fixture.node(List::of, false, observer, 1).app();
            TransactionalWorkflowOperations<SqlClient> ops = app.transactionalOperations();

            // Given an ACTIVE definition and a shared admission holder from an actual start
            await(app.definitionService()
                    .load(
                            document(advisoryId, 1, TimeoutAdvisoryCaseV1.class),
                            DocumentFormat.YAML,
                            source("timeout-advisory"),
                            null));
            await(app.definitionService().activate(advisoryKey));
            Stored activeBefore = fixture.stored(advisoryId, 1);
            Tx holder = fixture.begin(null);
            await(ops.start(
                    startCommand(advisoryId, TimeoutAdvisoryCaseV1.class, "timeout-holder", 1), holder.connection()));
            fixture.awaitAdvisory(advisoryId, "ShareLock", true, 1);

            // When an exclusive store mutation queues behind it with a shortened wait, and another admission
            // queues behind that writer
            observer.shortenNextDefinitionLockTimeout(2_000);
            Future<Void> mutation = app.definitionService().deprecate(advisoryKey);
            fixture.awaitAdvisory(advisoryId, "ExclusiveLock", false, 1);
            Tx queued = fixture.begin(null);
            Future<WorkflowInstanceId> queuedStart = ops.start(
                    startCommand(advisoryId, TimeoutAdvisoryCaseV1.class, "timeout-queued", 1), queued.connection());
            fixture.awaitAdvisory(advisoryId, "ShareLock", false, 1);
            Throwable timeout = failureOf(mutation);

            // Then the exclusive acquisition times out and maps 55P03 to a retryable persistence failure
            assertThat(observer.shortenedDefinitionLockTimeouts()).isEqualTo(1);
            assertThat(timeout).isInstanceOf(WorkflowPersistenceException.class);
            WorkflowPersistenceException mapped = (WorkflowPersistenceException) timeout;
            assertThat(mapped.retryable()).isTrue();
            assertThat(mapped.getMessage()).contains(advisoryId);
            assertThat(mapped.getCause()).isInstanceOf(PessimisticLockingFailureException.class);
            assertThat(((PessimisticLockingFailureException) mapped.getCause()).sqlState())
                    .isEqualTo("55P03");

            // And the mutation rolled back completely
            assertThat(fixture.stored(advisoryId, 1)).isEqualTo(activeBefore);

            // And the queued admission, no longer behind a writer, proceeds
            await(queuedStart);
            queued.rollback();
            holder.rollback();

            // And the reused connection carries no LOCAL setting or aborted transaction
            Row reused = await(app.pool()
                            .query("SELECT current_setting('lock_timeout') AS lock_timeout")
                            .execute())
                    .iterator()
                    .next();
            assertThat(reused.getString("lock_timeout")).isEqualTo("0");

            // And the same mutation then succeeds
            await(app.definitionService().deprecate(advisoryKey));
            assertThat(fixture.stored(advisoryId, 1).status()).isEqualTo("DEPRECATED");

            // Given another ACTIVE definition whose row lock is held by another transaction, with the id lock free
            await(app.definitionService()
                    .load(
                            document(rowId, 1, TimeoutRowCaseV1.class),
                            DocumentFormat.YAML,
                            source("timeout-row"),
                            null));
            await(app.definitionService().activate(rowKey));
            Stored rowBefore = fixture.stored(rowId, 1);
            Tx rowHolder = fixture.begin(null);
            await(rowHolder.connection().preparedQuery("""
                            SELECT definition_id FROM workflow_definitions
                            WHERE scope_key = 'default' AND definition_id = $1 AND definition_version = 1
                            FOR UPDATE
                            """).execute(Tuple.of(rowId)));

            // When a mutation takes the id lock and then waits for the row lock under the shortened bound
            observer.shortenNextDefinitionLockTimeout();
            Throwable rowTimeout = failureOf(app.definitionService().deprecate(rowKey));
            rowHolder.rollback();

            // Then the same LOCAL timeout maps to the same retryable failure with the id and cause
            assertThat(rowTimeout).isInstanceOf(WorkflowPersistenceException.class);
            WorkflowPersistenceException rowMapped = (WorkflowPersistenceException) rowTimeout;
            assertThat(rowMapped.retryable()).isTrue();
            assertThat(rowMapped.getMessage()).contains(rowId);
            assertThat(rowMapped.getCause()).isInstanceOf(PessimisticLockingFailureException.class);
            assertThat(((PessimisticLockingFailureException) rowMapped.getCause()).sqlState())
                    .isEqualTo("55P03");
            List<String> statements = observer.statements().stream()
                    .map(sql -> sql.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " "))
                    .toList();
            int lastTimeout = statements.lastIndexOf("set local lock_timeout = '30000ms'");
            assertThat(lastTimeout).isGreaterThanOrEqualTo(0);
            List<String> afterTimeout = statements.subList(lastTimeout + 1, statements.size());
            int advisoryAt = indexOfContaining(afterTimeout, "pg_advisory_xact_lock($1)");
            assertThat(advisoryAt).isGreaterThanOrEqualTo(0);
            assertThat(indexOfContaining(afterTimeout.subList(advisoryAt + 1, afterTimeout.size()), "for update"))
                    .isGreaterThanOrEqualTo(0);

            // And the row is unchanged and the connection is reusable
            assertThat(fixture.stored(rowId, 1)).isEqualTo(rowBefore);
            Row reusedAfterRow = await(app.pool()
                            .query("SELECT current_setting('lock_timeout') AS lock_timeout")
                            .execute())
                    .iterator()
                    .next();
            assertThat(reusedAfterRow.getString("lock_timeout")).isEqualTo("0");

            // Given a shared admission holder on an id that a static document reconciles at startup
            Tx startupHolder = fixture.begin(null);
            await(startupHolder
                    .connection()
                    .preparedQuery("SELECT pg_advisory_xact_lock_shared($1)")
                    .execute(Tuple.of(lockKey(staticId))));
            WorkflowDefinitionSqlObserver startupObserver = new WorkflowDefinitionSqlObserver();
            startupObserver.shortenNextDefinitionLockTimeout();
            WorkflowDefinitionSource staticSource = () -> List.of(new DefinitionResource(
                    document(staticId, 1, TimeoutStaticCaseV1.class), DocumentFormat.YAML, source("timeout-static")));

            // When a node starts and its reconciliation waits for the exclusive id lock
            Throwable startupFailure = fixture.startupFailure(staticSource, startupObserver);
            startupHolder.rollback();

            // Then startup fails with the same retryable failure carrying the id and the lock cause
            WorkflowPersistenceException startupMapped = causeOf(startupFailure, WorkflowPersistenceException.class);
            assertThat(startupMapped)
                    .as("startup failure chain: %s", startupFailure)
                    .isNotNull();
            assertThat(startupMapped.retryable()).isTrue();
            assertThat(startupMapped.getMessage()).contains(staticId);
            assertThat(causeOf(startupMapped.getCause(), PessimisticLockingFailureException.class)
                            .sqlState())
                    .isEqualTo("55P03");
            assertThat(fixture.findStored(staticId, 1)).isEmpty();
        }
    }

    @Test
    void refusesNewPinOnNonAdmittingStatusesAndKeepsResurrectionOnDeprecated(Vertx vertx) {
        String hash = "a".repeat(64);
        String deprecatedId = "retirement-it-admit-deprecated-doc";
        String supersededId = "retirement-it-admit-superseded-doc";
        List<String> nonAdmittingStatuses = List.of("CANDIDATE", "ACTIVATING", "RETIRED");
        List<String> siblingIds = nonAdmittingStatuses.stream()
                .map(status -> "retirement-it-admit-" + status.toLowerCase() + "-doc")
                .toList();

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(deprecatedId, supersededId);
            fixture.ownRows(siblingIds.toArray(String[]::new));
            PgWorkflowInstanceRepository repository = fixture.instances();

            // Given one owner whose only row, version 1, is DEPRECATED and therefore holds the id
            // (definition rows are seeded by SQL, because the statuses are not reachable through the public operations)
            fixture.seedDefinition(deprecatedId, 1, hash, "DEPRECATED");
            DefinitionKey deprecatedKey = new DefinitionKey(WorkflowScopes.DEFAULT, deprecatedId, 1);

            // And an instance already pinned to that version that FAILED, and one pinned to an unmanaged version 3
            UUID failed = fixture.insertInstance(deprecatedId, 1, hash, "FAILED", false);
            UUID elsewhere = fixture.insertInstance(deprecatedId, 3, "old-hash", "RUNNING", false);
            Instance elsewhereBefore = fixture.instance(elsewhere);
            long instancesBefore = fixture.instanceCount(deprecatedId);
            long historyBefore = fixture.historyCount();

            // When a new instance is pinned to the DEPRECATED version in READ COMMITTED
            Tx newPin = fixture.begin(null);
            Throwable insertRefusal = failureOf(repository.insert(
                    instanceRow(UUID.randomUUID(), deprecatedId, 1, hash, 0, WorkflowStatus.RUNNING),
                    newPin.connection()));
            newPin.rollback();

            // Then it is refused as an invalid transition naming the key, and nothing remains
            assertThat(insertRefusal).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(failure.key()).isEqualTo(deprecatedKey);
            });
            assertThat(fixture.instanceCount(deprecatedId)).isEqualTo(instancesBefore);
            assertThat(fixture.dedupCount(deprecatedId)).isZero();
            assertThat(fixture.historyCount()).isEqualTo(historyBefore);

            // When another instance is migrated onto the DEPRECATED version
            Tx migration = fixture.begin(null);
            Throwable migrateRefusal = failureOf(repository.migratePinAndState(
                    instanceRow(elsewhere, deprecatedId, 1, hash, 1, WorkflowStatus.RUNNING),
                    0,
                    migration.connection()));
            migration.rollback();

            // Then it is refused the same way and the source pin is unchanged
            assertThat(migrateRefusal).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(failure.key()).isEqualTo(deprecatedKey);
            });
            assertThat(fixture.instance(elsewhere)).isEqualTo(elsewhereBefore);

            // When the FAILED instance already pinned to the DEPRECATED version is retried
            Tx retry = fixture.begin(null);
            Integer resurrected = await(repository.updateOptimistic(
                    instanceRow(failed, deprecatedId, 1, hash, 1, WorkflowStatus.RUNNING), 0, retry.connection()));
            retry.commit();

            // Then the resurrection is admitted, because it keeps an existing pin
            assertThat(resurrected).isEqualTo(1);
            assertThat(fixture.instance(failed).status()).isEqualTo("RUNNING");

            // Given, for each non-admitting status, an owner holding the id with an ACTIVE version 1 and a
            // version 2 of the same owner in that status
            for (int i = 0; i < nonAdmittingStatuses.size(); i++) {
                String status = nonAdmittingStatuses.get(i);
                String id = siblingIds.get(i);
                fixture.seedDefinition(id, 1, hash, "ACTIVE");
                fixture.seedDefinition(id, 2, hash, status);
                long before = fixture.instanceCount(id);

                // When a new instance is pinned to version 2
                Tx sibling = fixture.begin(null);
                Throwable siblingRefusal = failureOf(repository.insert(
                        instanceRow(UUID.randomUUID(), id, 2, hash, 0, WorkflowStatus.RUNNING), sibling.connection()));
                sibling.rollback();

                // Then it is refused although another version of the same owner holds the id
                assertThat(siblingRefusal)
                        .as("a new pin on a %s version", status)
                        .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                            assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                            assertThat(failure.key()).isEqualTo(new DefinitionKey(WorkflowScopes.DEFAULT, id, 2));
                        });
                assertThat(fixture.instanceCount(id)).isEqualTo(before);

                // When a new instance is pinned to the ACTIVE version 1 of the same owner
                Tx held = fixture.begin(null);
                await(repository.insert(
                        instanceRow(UUID.randomUUID(), id, 1, hash, 0, WorkflowStatus.RUNNING), held.connection()));
                held.commit();

                // Then it is admitted
                assertThat(fixture.instanceCount(id)).isEqualTo(before + 1);
            }

            // Given an owner holding the id with a SUPERSEDED version 1 below its ACTIVE version 2
            fixture.seedDefinition(supersededId, 1, hash, "SUPERSEDED");
            fixture.seedDefinition(supersededId, 2, hash, "ACTIVE");

            // When a new instance is pinned to the SUPERSEDED version
            Tx superseded = fixture.begin(null);
            await(repository.insert(
                    instanceRow(UUID.randomUUID(), supersededId, 1, hash, 0, WorkflowStatus.RUNNING),
                    superseded.connection()));
            superseded.commit();

            // Then it is admitted
            assertThat(fixture.instanceCount(supersededId)).isEqualTo(1);
        }
    }

    @Test
    void retireCountsPinCommittedWhileWaitingEvenWithRepeatableReadServerDefault(Vertx vertx) {
        String id = "retirement-it-default-isolation-doc";
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(id);
            PgWorkflowInstanceRepository repository = fixture.instances();

            // Given a database whose default isolation is REPEATABLE READ, restored when the scenario ends
            String database = await(fixture.admin()
                            .query("SELECT current_database() AS name")
                            .execute())
                    .iterator()
                    .next()
                    .getString("name");
            await(fixture.admin()
                    .query("ALTER DATABASE \"" + database + "\" SET default_transaction_isolation = 'repeatable read'")
                    .execute());
            fixture.onClose(() -> await(fixture.admin()
                    .query("ALTER DATABASE \"" + database + "\" RESET default_transaction_isolation")
                    .execute()));

            // And a node started afterwards, whose fresh pool connections inherit that default
            AppComponent app = fixture.node().app();
            long coolingSeconds = app.workflowDefinitionsConfig().maxStalenessSeconds();
            Row serverDefault = await(app.pool()
                            .query("SHOW default_transaction_isolation")
                            .execute())
                    .iterator()
                    .next();
            assertThat(serverDefault.getString(0)).isEqualTo("repeatable read");

            // And a DEPRECATED, cooled version with a FAILED instance whose retry holds the shared admission lock
            CompiledDefinitionRef loaded = await(app.definitionService()
                    .load(
                            document(id, 1, DefaultIsolationCaseV1.class),
                            DocumentFormat.YAML,
                            source("default-isolation"),
                            null));
            await(app.definitionService().activate(key));
            await(app.definitionService().deprecate(key));
            fixture.ageStatus(key, coolingSeconds + 60);
            UUID failed = fixture.insertInstance(id, 1, loaded.planHash(), "FAILED", false);
            Tx retry = fixture.begin(null);
            Integer admitted = await(repository.updateOptimistic(
                    instanceRow(failed, id, 1, loaded.planHash(), 1, WorkflowStatus.RUNNING), 0, retry.connection()));
            fixture.awaitAdvisory(id, "ShareLock", true, 1);

            // When retirement is requested and proven queued behind that admission, and the pin then commits
            Future<Void> retirement = app.definitionService().retire(key);
            fixture.awaitAdvisory(id, "ExclusiveLock", false, 1);
            assertThat(retirement.isComplete()).isFalse();
            retry.commit();
            Throwable pinned = failureOf(retirement);

            // Then the count taken after the wait sees the committed RUNNING instance, whatever the server default
            assertThat(admitted).isEqualTo(1);
            assertThat(pinned).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.PINNED_INSTANCES);
                assertThat(failure.key()).isEqualTo(key);
                assertThat(failure.getMessage()).containsPattern("\\b1\\b");
            });
            assertThat(fixture.instance(failed).status()).isEqualTo("RUNNING");
            assertThat(fixture.stored(id, 1).status()).isEqualTo("DEPRECATED");
        }
    }

    @Test
    void retiresCandidateRegardlessOfPinsHeldByAnotherOwner(Vertx vertx) {
        String id = "retirement-it-candidate-pins-doc";
        DefinitionKey candidateKey = new DefinitionKey("tenant-a", id, 1);
        DefinitionKey registeredKey = new DefinitionKey("tenant-b", id, 1);

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(id);
            AppComponent app = fixture.node().app();

            // Given tenant-a's candidate and tenant-b's ACTIVE document with the same id, version and source hash
            String candidateHash =
                    seedCandidate(app, "tenant-a", document(id, 1, CandidatePinsCaseV1.class), "candidate");
            String registeredHash =
                    seedCandidate(app, "tenant-b", document(id, 1, CandidatePinsCaseV1.class), "registered");
            assertThat(candidateHash).isEqualTo(registeredHash);
            await(app.definitionService().activate(registeredKey));

            // And tenant-b has one RUNNING instance pinned to that id and version (inserted by SQL)
            UUID pin = fixture.insertInstance(id, 1, registeredHash, "RUNNING", false);
            Instance pinBefore = fixture.instance(pin);
            Stored registeredBefore = fixture.stored("tenant-b", id, 1);
            Stored candidateBefore = fixture.stored("tenant-a", id, 1);
            assertThat(candidateBefore.status()).isEqualTo("CANDIDATE");
            assertThat(registeredBefore.status()).isEqualTo("ACTIVE");
            RuntimeWorkflow runtimeBefore = app.workflowRegistry().resolve(registeredKey);

            // When tenant-a's candidate is retired through the service
            await(app.definitionService().retire(candidateKey));

            // Then it retires, because a candidate carries no pins and the pin belongs to the other owner's row
            Stored candidateAfter = fixture.stored("tenant-a", id, 1);
            assertThat(candidateAfter.status()).isEqualTo("RETIRED");
            assertThat(candidateAfter)
                    .usingRecursiveComparison()
                    .ignoringFields("status", "changedAt")
                    .isEqualTo(candidateBefore);

            // And tenant-b's row, its runtime and the pinned instance are unchanged
            assertThat(fixture.stored("tenant-b", id, 1)).isEqualTo(registeredBefore);
            assertThat(app.workflowRegistry().resolve(registeredKey)).isSameAs(runtimeBefore);
            assertThat(fixture.instance(pin)).isEqualTo(pinBefore);
        }
    }

    @Test
    void admitsAndRefusesUnderSerializableIsolation(Vertx vertx) {
        String hash = "e".repeat(64);
        String eligibleId = "retirement-it-serializable-eligible-doc";
        String changedId = "retirement-it-serializable-changed-doc";
        String unmanagedId = "retirement-it-serializable-unmanaged";

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(eligibleId, changedId, unmanagedId);
            PgWorkflowInstanceRepository repository = fixture.instances();

            // Given an unchanged, eligible managed row visible to a SERIALIZABLE snapshot
            fixture.seedDefinition(eligibleId, 1, hash, "ACTIVE");
            Tx eligible = fixture.begin("SERIALIZABLE");
            fixture.snapshot(eligible);

            // When a new instance is pinned to it
            await(repository.insert(
                    instanceRow(UUID.randomUUID(), eligibleId, 1, hash, 0, WorkflowStatus.RUNNING),
                    eligible.connection()));
            eligible.commit();

            // Then it is admitted
            assertThat(fixture.instanceCount(eligibleId)).isEqualTo(1);

            // Given a SERIALIZABLE snapshot in which the id has no visible row
            Tx empty = fixture.begin("SERIALIZABLE");
            fixture.snapshot(empty);

            // When an instance is pinned to that unmanaged id
            Throwable emptyRefusal = failureOf(repository.insert(
                    instanceRow(UUID.randomUUID(), unmanagedId, 1, hash, 0, WorkflowStatus.RUNNING),
                    empty.connection()));
            empty.rollback();

            // Then it is refused as an invalid transition, because absence cannot be told from a new managed id
            assertThat(emptyRefusal).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
            });
            assertThat(fixture.instanceCount(unmanagedId)).isZero();

            // Given a visible row that another transaction changes after the SERIALIZABLE snapshot
            fixture.seedDefinition(changedId, 1, hash, "ACTIVE");
            Tx changed = fixture.begin("SERIALIZABLE");
            fixture.snapshot(changed);
            await(fixture.admin()
                    .preparedQuery("UPDATE workflow_definitions SET status = 'SUPERSEDED' WHERE definition_id = $1")
                    .execute(Tuple.of(changedId)));

            // When an instance is pinned to it from the old snapshot
            Throwable staleFailure = failureOf(repository.insert(
                    instanceRow(UUID.randomUUID(), changedId, 1, hash, 0, WorkflowStatus.RUNNING),
                    changed.connection()));
            changed.rollback();

            // Then the pin fails with a serialization failure and no instance remains
            assertThat(causeOf(staleFailure, OptimisticLockingFailureException.class))
                    .as("a changed visible row must fail serialization: %s", staleFailure)
                    .isNotNull();
            assertThat(fixture.instanceCount(changedId)).isZero();
        }
    }

    /** Loads a contract interface that the shared fixture declares privately. */
    private static Class<?> fixtureContract(String simpleName) {
        try {
            return Class.forName(ManagedDefinitionDatabase.class.getName() + "$" + simpleName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("The fixture contract " + simpleName + " is not available", e);
        }
    }

    private static int indexOfContaining(List<String> statements, String fragment) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).contains(fragment)) return i;
        }
        return -1;
    }

    private static <T extends Throwable> T causeOf(Throwable failure, Class<T> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return type.cast(current);
        }
        return null;
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

    private static StartCommand startCommand(
            String definitionId, Class<?> contract, String idempotencyKey, long version) {
        return new StartCommand(
                definitionId,
                contract.getName(),
                new PlaceOrderDoc("order-" + idempotencyKey),
                idempotencyKey,
                null,
                null,
                version,
                null,
                null);
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

    /** The persisted columns of one instance that the scenarios compare. */
    private record Instance(String status, long definitionVersion, String planHash, long version) {}

    /** A caller-owned transaction on a genuine connection, closed idempotently. */
    private static final class Tx {
        private final SqlConnection connection;
        private final Transaction transaction;
        private boolean done;

        private Tx(SqlConnection connection, Transaction transaction) {
            this.connection = connection;
            this.transaction = transaction;
        }

        SqlConnection connection() {
            return connection;
        }

        void commit() {
            if (done) return;
            done = true;
            try {
                await(transaction.commit());
            } finally {
                await(connection.close());
            }
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

    private static byte[] document(String definitionId, long version, Class<?> contract) {
        String yaml = new String(ManagedDefinitionDatabase.yamlFixture(), StandardCharsets.UTF_8)
                .replace("definitionId: order-fulfillment-doc", "definitionId: " + definitionId)
                .replace("definitionVersion: 1", "definitionVersion: " + version)
                .replace(
                        "dev.vertique.workflow.definition.it.fixture.OrderFulfillmentDocumentWorkflowV1",
                        contract.getName());
        if (!yaml.contains("definitionId: " + definitionId + "\n")
                || !yaml.contains("definitionVersion: " + version + "\n")
                || !yaml.contains("contract: " + contract.getName() + "\n")) {
            throw new IllegalStateException("The workflow fixture did not take the scenario identity");
        }
        return yaml.getBytes(StandardCharsets.UTF_8);
    }

    /** Seeds a tenant candidate of the document and returns the plan hash its pipeline computes. */
    private static String seedCandidate(AppComponent app, String tenant, byte[] document, String label) {
        TenantRowSeed.seed(app, tenant, document, source(label), DefinitionStatus.CANDIDATE);
        return app.definitionPipeline()
                .load(new DefinitionResource(document, DocumentFormat.YAML, source(label)))
                .planHash();
    }

    private static SourceMetadata source(String label) {
        return new SourceMetadata("retirement-test", "memory:" + label, "revision-" + label, null, null, Instant.now());
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting a future", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (TimeoutException e) {
            throw new AssertionError("A future did not complete within ten seconds", e);
        }
    }

    private static Throwable failureOf(Future<?> future) {
        Future<Throwable> outcome =
                future.transform(result -> Future.succeededFuture(result.failed() ? result.cause() : null));
        return await(outcome);
    }

    /** One committed row of the registry table, read directly from the database. */
    private record Stored(
            String tenant,
            long version,
            String status,
            String planHash,
            String document,
            String documentFormat,
            String sourceType,
            String sourceUri,
            String sourceRevision,
            OffsetDateTime loadedAt,
            OffsetDateTime changedAt) {}

    /** A started application graph with its own pool on the shared database; closing it is idempotent. */
    private static final class Node {
        private final Pool pool;
        private final VertiqueApplicationHandle<?> handle;
        private final AppComponent app;
        private boolean closed;

        private Node(Pool pool, VertiqueApplicationHandle<?> handle, AppComponent app) {
            this.pool = pool;
            this.handle = handle;
            this.app = app;
        }

        AppComponent app() {
            return app;
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                await(handle.shutdown());
            } finally {
                await(pool.close());
            }
        }
    }

    @FunctionalInterface
    private interface Cleanup {
        void run() throws Exception;
    }

    /** Owns the application graphs and pools of one scenario and closes them in reverse order. */
    private static final class Fixture implements AutoCloseable {
        private final Vertx vertx;
        private final Deque<Cleanup> cleanups = new ArrayDeque<>();
        private Pool admin;

        Fixture(Vertx vertx) {
            this.vertx = vertx;
        }

        void onClose(Cleanup cleanup) {
            cleanups.push(cleanup);
        }

        /** Starts an application graph with its own pool and registers its closing. */
        Node node() {
            return node(List::of, false);
        }

        /** Starts an application graph with the given static document source and optional code contributor. */
        Node node(WorkflowDefinitionSource source, boolean includeCodeDefinition) {
            return node(source, includeCodeDefinition, new WorkflowDefinitionSqlObserver(), 10);
        }

        /** Starts a graph whose pool of the given size is observed by the given SQL observer. */
        Node node(
                WorkflowDefinitionSource source,
                boolean includeCodeDefinition,
                WorkflowDefinitionSqlObserver observer,
                int poolSize) {
            Pool pool = observer.observe(ManagedDefinitionDatabase.openPool(vertx, db, poolSize));
            VertiqueApplicationHandle<?> handle;
            AppComponent app;
            try {
                app = ManagedDefinitionDatabase.app(
                        vertx,
                        pool,
                        new JsonObject(),
                        new BootObservation("retirement-unused", 1, "unused", false),
                        source,
                        includeCodeDefinition);
                handle = await(VertiqueApplicationBootstrap.start(
                        VertiqueRuntime.of(vertx, new JsonObject()), ignoredRuntime -> app));
            } catch (RuntimeException | Error failure) {
                await(pool.close());
                throw failure;
            }
            Node node = new Node(pool, handle, app);
            onClose(node::close);
            return node;
        }

        /** Returns the pool used for test-owned SQL. */
        Pool admin() {
            if (admin == null) {
                admin = ManagedDefinitionDatabase.openPool(vertx, db);
                Pool opened = admin;
                onClose(() -> await(opened.close()));
            }
            return admin;
        }

        /** Clears the ids' rows and instances now and again on close, so none reaches a later start. */
        void ownRows(String... definitionIds) {
            deleteRows(definitionIds);
            onClose(() -> deleteRows(definitionIds));
        }

        private void deleteRows(String... definitionIds) {
            for (String definitionId : definitionIds) {
                await(admin().preparedQuery("DELETE FROM workflow_dedup WHERE kind = 'start' AND dedup_namespace = $1")
                        .execute(Tuple.of(definitionId)));
                await(admin().preparedQuery("DELETE FROM workflow_instances WHERE definition_id = $1")
                        .execute(Tuple.of(definitionId)));
                await(admin().preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                        .execute(Tuple.of(definitionId)));
            }
        }

        /** Inserts a pinned instance row directly; an archived row carries an archive timestamp. */
        UUID insertInstance(String definitionId, long version, String planHash, String status, boolean archived) {
            UUID instanceId = UUID.randomUUID();
            await(admin().preparedQuery("""
                            INSERT INTO workflow_instances
                                (id, definition_id, definition_version, plan_hash, version, status,
                                 current_step_id, state_json, archived_at)
                            VALUES ($1, $2, $3, $4, 0, $5, 'start', '{}'::jsonb,
                                    CASE WHEN $6::boolean THEN clock_timestamp() ELSE NULL END)
                            """)
                    .execute(Tuple.of(instanceId, definitionId, version, planHash, status, archived)));
            return instanceId;
        }

        void setInstanceStatus(UUID instanceId, String status) {
            await(admin().preparedQuery("UPDATE workflow_instances SET status = $2 WHERE id = $1")
                    .execute(Tuple.of(instanceId, status)));
        }

        long instanceCount(String definitionId) {
            return await(admin().preparedQuery(
                                    "SELECT count(*) AS total FROM workflow_instances WHERE definition_id = $1")
                            .execute(Tuple.of(definitionId)))
                    .iterator()
                    .next()
                    .getLong("total");
        }

        /** Moves the row's status change time to the given age, measured by the database clock. */
        void ageStatus(DefinitionKey key, long ageSeconds) {
            await(admin().preparedQuery("""
                            UPDATE workflow_definitions
                            SET status_changed_at = clock_timestamp() - ($4::bigint * interval '1 second')
                            WHERE scope_key IS NOT DISTINCT FROM $1 AND definition_id = $2
                              AND definition_version = $3
                            """)
                    .execute(Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion(), ageSeconds)));
        }

        Stored stored(String definitionId, long version) {
            return stored(WorkflowScopes.DEFAULT, definitionId, version);
        }

        /** Moves a row to a status that the public operations cannot reach for it. */
        void forceStatus(DefinitionKey key, DefinitionStatus status) {
            await(admin().preparedQuery("""
                            UPDATE workflow_definitions SET status = $4
                            WHERE scope_key IS NOT DISTINCT FROM $1 AND definition_id = $2 AND definition_version = $3
                            """)
                    .execute(Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion(), status.name())));
        }

        /** Begins a caller-owned transaction on a genuine connection, optionally at a stronger isolation level. */
        Tx begin(String isolationLevel) {
            SqlConnection connection = await(admin().getConnection());
            Transaction transaction;
            try {
                transaction = await(connection.begin());
                if (isolationLevel != null) {
                    await(connection
                            .query("SET TRANSACTION ISOLATION LEVEL " + isolationLevel)
                            .execute());
                }
            } catch (RuntimeException | Error failure) {
                await(connection.close());
                throw failure;
            }
            Tx tx = new Tx(connection, transaction);
            onClose(tx::rollback);
            return tx;
        }

        /** Takes the transaction's snapshot by reading the definition table. */
        void snapshot(Tx tx) {
            await(tx.connection()
                    .query("SELECT count(*) FROM workflow_definitions")
                    .execute());
        }

        /** Returns a pooled connection that is closed, after a rollback attempt, when the scenario ends. */
        SqlConnection connection() {
            SqlConnection connection = await(admin().getConnection());
            onClose(() -> {
                try {
                    await(connection.query("ROLLBACK").execute().recover(ignored -> Future.succeededFuture()));
                } finally {
                    await(connection.close().recover(ignored -> Future.succeededFuture()));
                }
            });
            return connection;
        }

        PgWorkflowInstanceRepository instances() {
            return new PgWorkflowInstanceRepository(admin(), new PgDbExceptionMapper());
        }

        /**
         * Inserts a definition row directly. A row in a status that the public operations cannot reach here, such as
         * RETIRED, or that must exist without a runtime, is seeded by SQL as a code-kind row.
         */
        void seedDefinition(String definitionId, long version, String planHash, String status) {
            await(admin().preparedQuery("""
                            INSERT INTO workflow_definitions
                                (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status,
                                 source_type, loaded_at, activation_token)
                            VALUES ('default', $1, $2, 'CODE', 'example.Contract', 'example.State', $3, $4, 'seed', now(),
                                    CASE WHEN $4 = 'ACTIVATING' THEN gen_random_uuid() ELSE NULL END)
                            """).execute(Tuple.of(definitionId, version, planHash, status)));
        }

        Instance instance(UUID instanceId) {
            Row row = await(admin().preparedQuery("""
                            SELECT status, definition_version, plan_hash, version
                            FROM workflow_instances WHERE id = $1
                            """).execute(Tuple.of(instanceId)))
                    .iterator()
                    .next();
            return new Instance(
                    row.getString("status"),
                    row.getLong("definition_version"),
                    row.getString("plan_hash"),
                    row.getLong("version"));
        }

        boolean instanceExists(UUID instanceId) {
            return await(admin().preparedQuery("SELECT count(*) AS total FROM workflow_instances WHERE id = $1")
                                    .execute(Tuple.of(instanceId)))
                            .iterator()
                            .next()
                            .getLong("total")
                    > 0;
        }

        long dedupCount(String definitionId) {
            return await(admin().preparedQuery(
                                    "SELECT count(*) AS total FROM workflow_dedup WHERE kind = 'start' AND dedup_namespace = $1")
                            .execute(Tuple.of(definitionId)))
                    .iterator()
                    .next()
                    .getLong("total");
        }

        UUID dedupTarget(String definitionId, String idempotencyKey) {
            return await(admin().preparedQuery("""
                                    SELECT workflow_id FROM workflow_dedup
                                    WHERE kind = 'start' AND dedup_namespace = $1 AND key = $2
                                    """).execute(Tuple.of(definitionId, idempotencyKey)))
                    .iterator()
                    .next()
                    .getUUID("workflow_id");
        }

        long historyCount() {
            return await(admin().query("SELECT count(*) AS total FROM workflow_history")
                            .execute())
                    .iterator()
                    .next()
                    .getLong("total");
        }

        /** Counts the backends holding or awaiting the definition id's advisory lock in the given mode. */
        long advisoryCount(String definitionId, String mode, boolean granted) {
            long key = lockKey(definitionId);
            long high = (key >>> 32) & 0xFFFFFFFFL;
            long low = key & 0xFFFFFFFFL;
            return await(admin().preparedQuery("""
                                    SELECT count(*) AS total FROM pg_locks
                                    WHERE locktype = 'advisory' AND objsubid = 1
                                      AND classid::bigint = $1 AND objid::bigint = $2
                                      AND mode = $3 AND granted = $4
                                    """).execute(Tuple.of(high, low, mode, granted)))
                    .iterator()
                    .next()
                    .getLong("total");
        }

        /** Proves the precondition that exactly the expected number of locks of the mode are held or awaited. */
        void awaitAdvisory(String definitionId, String mode, boolean granted, int expected) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            long seen = -1;
            while (System.nanoTime() < deadline) {
                seen = advisoryCount(definitionId, mode, granted);
                if (seen == expected) return;
                Thread.onSpinWait();
            }
            throw new AssertionError("Expected " + expected + (granted ? " granted " : " queued ") + mode + " locks on "
                    + definitionId + " but saw " + seen);
        }

        /** Starts a graph that must fail to start, and returns its failure. */
        Throwable startupFailure(WorkflowDefinitionSource source, WorkflowDefinitionSqlObserver observer) {
            Pool pool = observer.observe(ManagedDefinitionDatabase.openPool(vertx, db, 1));
            onClose(() -> await(pool.close()));
            AppComponent app = ManagedDefinitionDatabase.app(
                    vertx,
                    pool,
                    new JsonObject(),
                    new BootObservation("retirement-unused", 1, "unused", false),
                    source,
                    false);
            Future<VertiqueApplicationHandle<AppComponent>> starting =
                    VertiqueApplicationBootstrap.start(VertiqueRuntime.of(vertx, new JsonObject()), ignored -> app);
            Throwable failure = failureOf(starting);
            if (failure == null) onClose(() -> await(starting.result().shutdown()));
            return failure;
        }

        /** Returns the global row, or empty when there is none. */
        Optional<Stored> findStored(String definitionId, long version) {
            long count = await(admin().preparedQuery("""
                                    SELECT count(*) AS total FROM workflow_definitions
                                    WHERE scope_key = 'default' AND definition_id = $1 AND definition_version = $2
                                    """).execute(Tuple.of(definitionId, version)))
                    .iterator()
                    .next()
                    .getLong("total");
            return count == 0 ? Optional.empty() : Optional.of(stored(WorkflowScopes.DEFAULT, definitionId, version));
        }

        Stored stored(String scopeKey, String definitionId, long version) {
            List<Stored> rows = new ArrayList<>();
            for (Row row : await(admin().preparedQuery("""
                            SELECT scope_key, definition_version, status, plan_hash, document, document_format,
                                   source_type, source_uri, source_revision, loaded_at, status_changed_at
                            FROM workflow_definitions
                            WHERE scope_key IS NOT DISTINCT FROM $1 AND definition_id = $2
                              AND definition_version = $3
                            """).execute(Tuple.of(scopeKey, definitionId, version)))) {
                rows.add(new Stored(
                        row.getString("scope_key"),
                        row.getLong("definition_version"),
                        row.getString("status"),
                        row.getString("plan_hash"),
                        row.getString("document"),
                        row.getString("document_format"),
                        row.getString("source_type"),
                        row.getString("source_uri"),
                        row.getString("source_revision"),
                        row.getOffsetDateTime("loaded_at"),
                        row.getOffsetDateTime("status_changed_at")));
            }
            assertThat(rows).as("rows for %s v%s", definitionId, version).hasSize(1);
            return rows.getFirst();
        }

        @Override
        public void close() {
            AssertionError failure = null;
            while (!cleanups.isEmpty()) {
                try {
                    cleanups.pop().run();
                } catch (Throwable cleanupFailure) {
                    if (failure == null) failure = new AssertionError("Scenario cleanup failed", cleanupFailure);
                    else failure.addSuppressed(cleanupFailure);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
