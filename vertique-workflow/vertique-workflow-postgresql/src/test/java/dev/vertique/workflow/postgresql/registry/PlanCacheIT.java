// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.failureOf;
import static dev.vertique.workflow.postgresql.registry.PlanCacheFixture.ACME;
import static dev.vertique.workflow.postgresql.registry.PlanCacheFixture.FIRST_WAIT;
import static dev.vertique.workflow.postgresql.registry.PlanCacheFixture.awaitCondition;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.cache.spi.event.CacheOperation;
import dev.vertique.cache.spi.event.CacheOutcome;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc;
import dev.vertique.workflow.engine.registry.PlanCache;
import dev.vertique.workflow.exception.WorkflowSignalRejectedException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.registry.PlanCacheFixture.CommandRow;
import dev.vertique.workflow.postgresql.registry.PlanCacheFixture.HistoryEntry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that document plans the node does not hold are built on demand into a bounded plan cache: how many stay
 * resident, which events the cache reports, that concurrent commands share one read and one build, that a plan whose
 * rebuilt hash differs is deferred and remembered, that a pool of two connections is enough, and that the first build
 * of a row written before its signal names were stored fills them.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class PlanCacheIT {

    /** The contract of the fixture document; it declares the one signal the document waits for first. */
    @WorkflowContract("plan-cache-paid")
    public interface PaidContract {

        @WorkflowSignal("paid")
        Future<Void> paid(WorkflowInstanceId id, InventoryReservedDoc payload, @SignalDedupKey String dedupKey);
    }

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_plan_cache_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String CACHE = "workflow-plans";
    private static final Duration SETTLE = Duration.ofSeconds(20);

    @Test
    @DisplayName("resident document plans stay within the configured maximum")
    void residentDocumentPlansStayWithinMaxEntries(Vertx vertx) {
        try (PlanCacheFixture fixture = PlanCacheFixture.open(vertx, db, 100, 10, 0)) {
            // Given a plan cache of at most 100 plans, 150 versions of one acme document seeded after startup, and
            // one start command per version recorded in version order
            List<DefinitionKey> versions = fixture.seedVersions(ACME, 150);
            List<WorkflowInstanceId> instances =
                    versions.stream().map(fixture::recordStart).toList();

            // When the executor runs until every instance is idle and the cache has run its maintenance
            fixture.awaitAllIdle();
            PlanCache cache = fixture.planCache();
            long resident = cache.size();

            // Then the cache holds at most 100 plans
            assertThat(resident).isLessThanOrEqualTo(100);

            // And every instance reached its first wait with the state its start payload produced
            for (int i = 0; i < instances.size(); i++) {
                JsonObject instance = fixture.instanceOf(instances.get(i));
                assertThat(instance.getString("status"))
                        .as("instance %s", i + 1)
                        .isEqualTo("WAITING");
                assertThat(instance.getString("current_step_id")).isEqualTo(FIRST_WAIT);
                assertThat(PlanCacheFixture.json(instance.getValue("state_json"))
                                .getString("orderId"))
                        .isEqualTo("order-" + (i + 1));
            }

            // And no command was deferred or dead-lettered
            assertThat(fixture.allCommands()).hasSize(150).allSatisfy(command -> {
                assertThat(command.state()).isEqualTo("COMPLETE");
                assertThat(command.deferralCount()).isZero();
            });
        }
    }

    @Test
    @DisplayName("cache events report misses, loads and size evictions but never hits")
    void cacheEventsReportMissesLoadsAndSizeEvictionsButNoHits(Vertx vertx) {
        try (PlanCacheFixture fixture = PlanCacheFixture.open(vertx, db, 100, 10, 0)) {
            // Given the same 150 versions and start commands, with a recording observer bound to the cache
            List<DefinitionKey> versions = fixture.seedVersions(ACME, 150);
            versions.forEach(fixture::recordStart);
            RecordingCacheObserver events = fixture.cacheEvents();

            // When the 150 commands execute and the cache has run its maintenance
            fixture.awaitAllIdle();
            fixture.planCache().size();
            awaitCondition(
                    "50 size evictions",
                    SETTLE,
                    () -> events.count(CACHE, CacheOperation.EVICT, CacheOutcome.SIZE_EVICTED) >= 50);

            // Then the workflow-plans cache reported 150 misses, 150 successful loads with a positive duration and
            // 50 size evictions
            assertThat(events.count(CACHE, CacheOperation.GET, CacheOutcome.MISS))
                    .isEqualTo(150);
            assertThat(events.count(CACHE, CacheOperation.LOAD, CacheOutcome.SUCCESS))
                    .isEqualTo(150);
            assertThat(events.elapsed(CACHE, CacheOperation.LOAD, CacheOutcome.SUCCESS))
                    .allSatisfy(took -> assertThat(took).isPositive());
            assertThat(events.count(CACHE, CacheOperation.EVICT, CacheOutcome.SIZE_EVICTED))
                    .isEqualTo(50);

            // And it reported no hit
            assertThat(events.count(CACHE, CacheOutcome.HIT)).isZero();
        }
    }

    @Test
    @DisplayName("concurrent commands of one missing version read its document once and build it once")
    void concurrentCommandsOfOneMissingVersionReadAndBuildItOnce(Vertx vertx) {
        try (PlanCacheFixture fixture = PlanCacheFixture.open(vertx, db, 100, 10, 0)) {
            // Given one seeded acme version and 50 instances, each with a start command, and an executor that
            // claims eight commands at a time
            DefinitionKey version = fixture.seedVersions(ACME, 1).getFirst();
            List<WorkflowInstanceId> instances = java.util.stream.IntStream.range(0, 50)
                    .mapToObj(i -> fixture.recordStart(version))
                    .toList();

            // When the executor runs to idle
            fixture.awaitAllIdle();

            // Then the document of the key was read once and the plan was built once
            assertThat(fixture.documentReads(version)).isEqualTo(1);
            assertThat(fixture.builds()).isEqualTo(1);

            // And all 50 instances reached their wait
            assertThat(instances).allSatisfy(instance -> {
                JsonObject row = fixture.instanceOf(instance);
                assertThat(row.getString("status")).isEqualTo("WAITING");
                assertThat(row.getString("current_step_id")).isEqualTo(FIRST_WAIT);
            });
        }
    }

    @Test
    @DisplayName("a rebuilt hash mismatch defers the command and writes nothing else")
    void rebuiltHashMismatchDefersTheCommandAndWritesNothingElse(Vertx vertx) {
        try (PlanCacheFixture fixture = PlanCacheFixture.open(vertx, db, 100, 10, 0)) {
            // Given a seeded acme row whose stored plan hash was then replaced, and one start command
            DefinitionKey version = fixture.seedVersions(ACME, 1).getFirst();
            fixture.corruptHash(version);
            WorkflowInstanceId instance = fixture.recordStart(version);

            // When the executor peeks the command
            awaitCondition("the first deferral", SETTLE, () -> fixture.commandsOf(instance).stream()
                    .anyMatch(command -> command.deferralCount() == 1));

            // Then the command is deferred once with no attempt spent and the instance is still pending
            CommandRow deferred = fixture.commandsOf(instance).getFirst();
            assertThat(deferred.state()).isEqualTo("DEFERRED");
            assertThat(deferred.deferralCount()).isEqualTo(1);
            assertThat(deferred.attempts()).isZero();
            assertThat(fixture.instanceOf(instance).getString("status")).isEqualTo("PENDING");

            // And the history holds the one deferral entry and nothing of an execution or a transition
            assertThat(fixture.historyOf(instance))
                    .extracting(HistoryEntry::type)
                    .containsExactly("COMMAND_DEFERRED");

            // And no timer, outbox intent or other row was written
            assertThat(fixture.rowCount("workflow_timers")).isZero();
            assertThat(fixture.outboxRows()).isZero();

            // And the plan was built once and the cache reported one failed load
            assertThat(fixture.builds()).isEqualTo(1);
            assertThat(fixture.cacheEvents().count(CACHE, CacheOperation.LOAD, CacheOutcome.ERROR))
                    .isEqualTo(1);

            // When the backoff has elapsed and the executor peeks the command again
            fixture.fastForward(instance);
            awaitCondition("the second deferral", SETTLE, () -> fixture.commandsOf(instance).stream()
                    .anyMatch(command -> command.deferralCount() == 2));

            // Then the command is deferred again without a second history entry and without a second build
            CommandRow again = fixture.commandsOf(instance).getFirst();
            assertThat(again.state()).isEqualTo("DEFERRED");
            assertThat(again.attempts()).isZero();
            assertThat(fixture.historyOf(instance))
                    .extracting(HistoryEntry::type)
                    .containsExactly("COMMAND_DEFERRED");
            assertThat(fixture.builds()).isEqualTo(1);
            assertThat(fixture.cacheEvents().count(CACHE, CacheOperation.LOAD, CacheOutcome.ERROR))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("cold versions run on a pool of two connections without an acquisition timeout")
    void coldVersionsRunOnAPoolOfTwoWithoutAcquisitionTimeout(Vertx vertx) {
        try (PlanCacheFixture fixture = PlanCacheFixture.open(vertx, db, 100, 2, 2)) {
            // Given a pool of two connections whose acquisition gives up after two seconds, ten seeded acme
            // versions and one start command each, and an executor that claims eight commands at a time
            List<DefinitionKey> versions = fixture.seedVersions(ACME, 10);
            List<WorkflowInstanceId> instances =
                    versions.stream().map(fixture::recordStart).toList();

            // When the executor runs to idle
            fixture.awaitAllIdle();

            // Then all ten instances reached their wait
            assertThat(instances).allSatisfy(instance -> {
                JsonObject row = fixture.instanceOf(instance);
                assertThat(row.getString("status")).isEqualTo("WAITING");
                assertThat(row.getString("current_step_id")).isEqualTo(FIRST_WAIT);
            });

            // And no command recorded a failure and no acquisition timeout was logged
            assertThat(fixture.allCommands()).extracting(CommandRow::lastError).containsOnlyNulls();
            assertThat(fixture.warnings().stream()
                            .filter(message -> message.toLowerCase().contains("waiting for a connection"))
                            .collect(Collectors.toList()))
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("an on-demand build fills a legacy scoped row so undeclared signals are refused at record time")
    void onDemandBuildFillsALegacyScopedRowSoUndeclaredSignalsAreRefused(Vertx vertx) {
        try (PlanCacheFixture fixture = PlanCacheFixture.open(vertx, db, 100, 10, 0)) {
            // Given an available acme row declaring the instance signal paid whose two columns were cleared, as a row
            // written before they existed, and one start of it recorded and committed
            DefinitionKey version = fixture.seedVersions(ACME, 1).getFirst();
            fixture.legacyRow(version);
            JsonObject legacy = fixture.definitionRow(version);
            assertThat(legacy.getValue("signal_names_json")).isNull();
            assertThat(legacy.getValue("start_schema_json")).isNull();
            WorkflowInstanceId instance = fixture.recordStart(version);

            // When the executor runs the start, which builds the row's plan, and the fill has run
            fixture.awaitIdle(instance);
            awaitCondition(
                    "the row to be filled",
                    SETTLE,
                    () -> fixture.definitionRow(version).getValue("signal_names_json") != null);

            // Then the instance waits at paid
            JsonObject waiting = fixture.instanceOf(instance);
            assertThat(waiting.getString("status")).isEqualTo("WAITING");
            assertThat(waiting.getString("current_step_id")).isEqualTo(FIRST_WAIT);

            // And the row holds the signal names of its plan and the schema of its start payload
            JsonObject filled = fixture.definitionRow(version);
            assertThat(PlanCacheFixture.json(filled.getValue("signal_names_json")))
                    .isEqualTo(new JsonObject("{\"instance\":[\"paid\"],\"branches\":{}}"));
            assertThat(PlanCacheFixture.json(filled.getValue("start_schema_json"))
                            .getJsonObject("properties")
                            .fieldNames())
                    .contains("orderId");
            assertThat(filled.getString("plan_hash")).isEqualTo(legacy.getString("plan_hash"));

            // And the fill happened once, in a transaction of its own and not in the segment's
            assertThat(fixture.fillStatements()).isEqualTo(1);
            Set<String> segmentTransactions = fixture.historyOf(instance).stream()
                    .map(HistoryEntry::transaction)
                    .collect(Collectors.toSet());
            assertThat(segmentTransactions).isNotEmpty().doesNotContain(filled.getString("tx"));

            // When a caller transaction records a signal the plan does not declare
            Throwable refusal = failureOf(fixture.recordSignal(instance, "refund", "refund-1"));

            // Then the recording fails at once and leaves no command and no dedup row
            assertThat(refusal).isInstanceOf(WorkflowSignalRejectedException.class);
            assertThat(fixture.signalRows(instance)).isZero();
        }
    }
}
