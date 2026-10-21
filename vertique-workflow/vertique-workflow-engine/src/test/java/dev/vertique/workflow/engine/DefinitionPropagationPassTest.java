// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.RegisteredDefinitionClass;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.DocumentOrigin;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Verifies one reconciliation pass against fakes of the table, the registry, the loader and the timer: which
 * hand-offs it makes and when, how it bounds and contains a failing row, which settled rows it leaves alone, and
 * what it logs.
 *
 * <p>Everything is synchronous: the table answers at once, the timer fires only when the test says so, and no test
 * waits for time to pass.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DefinitionPropagationPassTest {

    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final DefinitionProvenance PROVENANCE = new DefinitionProvenance("test", null, null, null, null, T0);

    private World world;
    private PassLog log;

    @BeforeEach
    void setUp() {
        world = new World(Optional.empty());
        log = new PassLog();
    }

    @AfterEach
    void tearDown() {
        log.close();
    }

    // ----- loader hand-off ordering -----

    @Test
    @DisplayName("a status change of a held document row is handed to the loader as a restoration")
    void handsOverAStatusChangeOfAHeldDocumentRow() {
        // Given a document row whose runtime this node holds and that the first pass has applied
        WorkflowDefinitionRecord active = document(WorkflowScopes.DEFAULT, "wf-held", DefinitionStatus.AVAILABLE, T0);
        world.hold(WorkflowScopes.DEFAULT, "wf-held", 1, HASH);
        world.store.put(active);
        world.runOk();
        assertThat(world.loader.restored).containsExactly(active);

        // When the row is deprecated by another node and the next pass runs
        WorkflowDefinitionRecord deprecated =
                document(WorkflowScopes.DEFAULT, "wf-held", DefinitionStatus.DEPRECATED, T0.plusSeconds(5));
        world.store.put(deprecated);
        world.runOk();

        // Then the new status was handed over and the runtime is marked
        assertThat(world.loader.restored).containsExactly(active, deprecated);
        assertThat(world.registry.isDeprecated(new DefinitionKey(WorkflowScopes.DEFAULT, "wf-held", 1)))
                .isTrue();
        assertThat(world.view.applyFailures()).isZero();
    }

    @Test
    @DisplayName("a deprecated row that another node reinstated has its mark cleared and is handed to the loader")
    void clearsTheMarkOfAReinstatedRow() {
        // Given a deprecated document row whose runtime this node holds marked, and that a first pass applied
        WorkflowDefinitionRecord deprecated =
                document(WorkflowScopes.DEFAULT, "wf-reinstated", DefinitionStatus.DEPRECATED, T0);
        world.hold(WorkflowScopes.DEFAULT, "wf-reinstated", 1, HASH);
        world.store.put(deprecated);
        world.runOk();
        DefinitionKey key = deprecated.key();
        assertThat(world.registry.isDeprecated(key)).isTrue();

        // When the row is reinstated by another node and the next pass runs
        WorkflowDefinitionRecord reinstated =
                document(WorkflowScopes.DEFAULT, "wf-reinstated", DefinitionStatus.AVAILABLE, T0.plusSeconds(5));
        world.store.put(reinstated);
        world.runOk();

        // Then the mark is cleared, the registration is the same one, and the new status was handed over
        assertThat(world.registry.isDeprecated(key)).isFalse();
        assertThat(world.registry.holds("wf-reinstated", 1)).isTrue();
        assertThat(world.registry.unregistered).isEmpty();
        assertThat(world.loader.restored).containsExactly(deprecated, reinstated);
        assertThat(world.view.recorded(key)).contains(reinstated);
        assertThat(world.view.applyFailures()).isZero();
    }

    @Test
    @DisplayName("a hand-off that throws once leaves the row failing, and the next pass delivers it and clears it")
    void retriesAHandOffThatThrew() {
        // Given a held document row whose loader fails the first delivery
        WorkflowDefinitionRecord active = document(WorkflowScopes.DEFAULT, "wf-retry", DefinitionStatus.AVAILABLE, T0);
        world.hold(WorkflowScopes.DEFAULT, "wf-retry", 1, HASH);
        world.store.put(active);
        world.loader.failRestored = 1;

        // When the first pass runs
        world.runOk();

        // Then the row counts as failing and nothing was delivered
        assertThat(world.view.applyFailures()).isEqualTo(1);
        assertThat(world.loader.restored).isEmpty();

        // When the next pass runs
        world.runOk();

        // Then the delivery happened and the failure is gone
        assertThat(world.loader.restored).containsExactly(active);
        assertThat(world.view.applyFailures()).isZero();
        assertThat(world.view.recorded(active.key())).contains(active);
    }

    @Test
    @DisplayName("a retired and a candidate document row each deliver their metadata")
    void handsOverMetadataOfRetiredAndCandidateRows() {
        // Given a retired and a candidate document row
        WorkflowDefinitionRecord retired = document(WorkflowScopes.DEFAULT, "wf-retired", DefinitionStatus.RETIRED, T0);
        WorkflowDefinitionRecord candidate =
                document(WorkflowScopes.DEFAULT, "wf-candidate", DefinitionStatus.CANDIDATE, T0);
        world.store.put(retired);
        world.store.put(candidate);

        // When a pass runs
        world.runOk();

        // Then both rows were handed over as metadata
        assertThat(world.loader.metadata).containsExactlyInAnyOrder(retired, candidate);
        assertThat(world.loader.restored).isEmpty();
    }

    @Test
    @DisplayName("a held runtime whose definition is not retained is handed over as metadata")
    void handsOverMetadataWhenTheHeldDefinitionIsNotRetained() {
        // Given a held runtime that was registered without a retained definition
        WorkflowDefinitionRecord active = document(WorkflowScopes.DEFAULT, "wf-bare", DefinitionStatus.AVAILABLE, T0);
        world.holdWithoutDefinition(WorkflowScopes.DEFAULT, "wf-bare", 1, HASH);
        world.store.put(active);

        // When a pass runs
        world.runOk();

        // Then the row is delivered as metadata
        assertThat(world.loader.metadata).containsExactly(active);
        assertThat(world.loader.restored).isEmpty();
    }

    @Test
    @DisplayName("a restoration from the stored document delivers the restored definition")
    void handsOverARestoration() {
        // Given a live document row whose runtime this node does not hold
        WorkflowDefinitionRecord active =
                document(WorkflowScopes.DEFAULT, "wf-restore", DefinitionStatus.AVAILABLE, T0);
        world.store.put(active);

        // When a pass runs
        world.runOk();

        // Then the runtime is published and the restoration was delivered
        assertThat(world.registry.holds("wf-restore", 1)).isTrue();
        assertThat(world.loader.restored).containsExactly(active);
        assertThat(world.view.recorded(active.key())).contains(active);
    }

    // ----- hung and fatal rows -----

    @Test
    @DisplayName("a row whose apply never completes is timed out and counted, and the other rows still apply")
    void timesOutAHungRow() {
        // Given a first row whose stored document never arrives and a second row that applies at once
        WorkflowDefinitionRecord hung = document(WorkflowScopes.DEFAULT, "wf-hung", DefinitionStatus.AVAILABLE, T0);
        WorkflowDefinitionRecord healthy =
                document(WorkflowScopes.DEFAULT, "wf-healthy", DefinitionStatus.AVAILABLE, T0);
        world.store.put(hung);
        world.store.put(healthy);
        Promise<Optional<WorkflowDefinitionDocument>> never = Promise.promise();
        world.store.documentGates.put(hung.key(), never);

        // When a pass runs
        Future<Void> pass = world.pass.run();

        // Then the pass waits on the hung row, which is bounded by twice the default poll period
        assertThat(pass.isComplete()).isFalse();
        assertThat(world.timers.pending()).singleElement().satisfies(timer -> assertThat(timer.delayMs)
                .isEqualTo(60_000L));

        // When the deadline passes
        world.timers.fire(world.timers.pending().getFirst());

        // Then the pass completes, the hung row is counted and logged, and the healthy row applied
        assertThat(pass.succeeded()).isTrue();
        assertThat(world.view.applyFailures()).isEqualTo(1);
        assertThat(world.view.failing(hung.key())).isTrue();
        assertThat(world.registry.holds("wf-healthy", 1)).isTrue();
        assertThat(world.view.recorded(healthy.key())).contains(healthy);
        assertThat(log.warnings())
                .singleElement()
                .satisfies(text -> assertThat(text).contains("wf-hung").contains("TIMEOUT"));

        // And the late completion of the abandoned row does no harm
        never.complete(Optional.of(new WorkflowDefinitionDocument(hung.key(), "{}", "YAML")));
        assertThat(world.timers.pending()).isEmpty();
    }

    @Test
    @DisplayName("the per-row deadline is twice the configured poll period")
    void rowDeadlineFollowsTheConfiguredPeriod() {
        // Given a node that polls every five seconds and a row that never completes
        world = new World(Optional.of(new WorkflowDefinitionsConfig(5, 1024)));
        WorkflowDefinitionRecord hung = document(WorkflowScopes.DEFAULT, "wf-hung", DefinitionStatus.AVAILABLE, T0);
        world.store.put(hung);
        world.store.documentGates.put(hung.key(), Promise.promise());

        // When a pass runs
        Future<Void> pass = world.pass.run();

        // Then the row is bounded by ten seconds
        assertThat(world.timers.pending()).singleElement().satisfies(timer -> assertThat(timer.delayMs)
                .isEqualTo(10_000L));
        world.timers.fire(world.timers.pending().getFirst());
        assertThat(pass.succeeded()).isTrue();
    }

    @Test
    @DisplayName("a row that overflows the stack is counted like any failing row and later rows still apply")
    void containsAStackOverflowInOneRow() {
        // Given a row whose loader overflows the stack and a later healthy row
        WorkflowDefinitionRecord overflowing =
                document(WorkflowScopes.DEFAULT, "wf-deep", DefinitionStatus.AVAILABLE, T0);
        WorkflowDefinitionRecord healthy = document(WorkflowScopes.DEFAULT, "wf-later", DefinitionStatus.AVAILABLE, T0);
        world.store.put(overflowing);
        world.store.put(healthy);
        world.loader.loadError = new StackOverflowError();
        world.loader.loadErrorFor = "wf-deep";

        // When a pass runs
        Future<Void> pass = world.pass.run();

        // Then the pass completes, the row is counted, and the later row applied
        assertThat(pass.succeeded()).as("the pass: %s", pass.cause()).isTrue();
        assertThat(world.view.applyFailures()).isEqualTo(1);
        assertThat(world.view.failing(overflowing.key())).isTrue();
        assertThat(world.registry.holds("wf-later", 1)).isTrue();
    }

    @Test
    @DisplayName("an out-of-memory error is not contained: it fails the pass")
    void doesNotContainAnOutOfMemoryError() {
        // Given a row whose loader runs out of memory
        world.store.put(document(WorkflowScopes.DEFAULT, "wf-oom", DefinitionStatus.AVAILABLE, T0));
        world.loader.loadError = new OutOfMemoryError("heap");
        world.loader.loadErrorFor = "wf-oom";

        // When a pass runs
        Future<Void> pass = world.pass.run();

        // Then the pass failed with that error
        assertThat(pass.failed()).isTrue();
        assertThat(pass.cause()).isInstanceOf(OutOfMemoryError.class);
    }

    // ----- settled rows cost nothing -----

    @Test
    @DisplayName("a second pass over unchanged settled rows reads and queues nothing for them")
    void skipsSettledRows() {
        // Given every kind of settled row: held live, retired, candidate, publishing, and code live and deprecated
        world.hold(WorkflowScopes.DEFAULT, "wf-live", 1, HASH);
        world.store.put(document(WorkflowScopes.DEFAULT, "wf-live", DefinitionStatus.AVAILABLE, T0));
        world.store.put(document(WorkflowScopes.DEFAULT, "wf-retired", DefinitionStatus.RETIRED, T0));
        world.store.put(document(WorkflowScopes.DEFAULT, "wf-candidate", DefinitionStatus.CANDIDATE, T0));
        world.store.put(document(WorkflowScopes.DEFAULT, "wf-publishing", DefinitionStatus.PUBLISHING, T0));
        world.deployCode("wf-code", HASH);
        world.deployCode("wf-dep", HASH);
        world.store.put(code("wf-code", DefinitionStatus.AVAILABLE, T0));
        world.store.put(code("wf-dep", DefinitionStatus.DEPRECATED, T0));
        world.runOk();
        assertThat(world.store.totalFinds()).isEqualTo(6);
        int metadataDeliveries = world.loader.metadata.size();
        world.store.finds.clear();

        // When the next pass runs over the same table
        world.runOk();

        // Then no row was re-read and nothing was delivered again
        assertThat(world.store.totalFinds()).isZero();
        assertThat(world.store.lists).isEqualTo(2);
        assertThat(world.loader.metadata).hasSize(metadataDeliveries);
        assertThat(world.view.applyFailures()).isZero();
    }

    @Test
    @DisplayName("a changed status or change instant takes the full path again")
    void rereadsARowWhoseStatusChanged() {
        // Given settled live and retired rows
        world.hold(WorkflowScopes.DEFAULT, "wf-live", 1, HASH);
        WorkflowDefinitionRecord live = document(WorkflowScopes.DEFAULT, "wf-live", DefinitionStatus.AVAILABLE, T0);
        WorkflowDefinitionRecord retired = document(WorkflowScopes.DEFAULT, "wf-retired", DefinitionStatus.RETIRED, T0);
        world.store.put(live);
        world.store.put(retired);
        world.runOk();
        world.store.finds.clear();

        // When the live row is deprecated and the retired row is rewritten with a later instant
        world.store.put(document(WorkflowScopes.DEFAULT, "wf-live", DefinitionStatus.DEPRECATED, T0.plusSeconds(1)));
        world.store.put(document(WorkflowScopes.DEFAULT, "wf-retired", DefinitionStatus.RETIRED, T0.plusSeconds(1)));
        world.runOk();

        // Then both were re-read once and the deprecation was applied
        assertThat(world.store.findCount(live.key())).isEqualTo(1);
        assertThat(world.store.findCount(retired.key())).isEqualTo(1);
        assertThat(world.registry.isDeprecated(new DefinitionKey(WorkflowScopes.DEFAULT, "wf-live", 1)))
                .isTrue();
    }

    @Test
    @DisplayName("a runtime that was removed from the registry behind the pass is healed on the next pass")
    void healsAnExternallyRemovedRuntime() {
        // Given an applied code row
        world.deployCode("wf-code", HASH);
        WorkflowDefinitionRecord row = code("wf-code", DefinitionStatus.AVAILABLE, T0);
        world.store.put(row);
        world.runOk();
        assertThat(world.registry.holds("wf-code", 1)).isTrue();
        world.store.finds.clear();

        // When something removes its runtime without the pass knowing
        world.registry.drop("wf-code", 1);
        world.runOk();

        // Then the row was re-read and the runtime is published again
        assertThat(world.store.findCount(row.key())).isEqualTo(1);
        assertThat(world.registry.holds("wf-code", 1)).isTrue();
    }

    @Test
    @DisplayName("a retired row is removed when its own runtime reappears after it was settled")
    void removesARuntimeThatReappearsForASettledRetiredRow() {
        // Given a retired row that the pass has settled
        WorkflowDefinitionRecord retired = document(WorkflowScopes.DEFAULT, "wf-retired", DefinitionStatus.RETIRED, T0);
        world.store.put(retired);
        world.runOk();
        world.store.finds.clear();

        // When its own runtime shows up again
        world.hold(WorkflowScopes.DEFAULT, "wf-retired", 1, HASH);
        world.runOk();

        // Then the row was re-read and the runtime removed
        assertThat(world.store.findCount(retired.key())).isEqualTo(1);
        assertThat(world.registry.holds("wf-retired", 1)).isFalse();
    }

    @Test
    @DisplayName("a live row that failed is re-read until it heals, and only then skipped")
    void rereadsAFailingLiveRowUntilItHeals() {
        // Given a held live row whose first delivery fails
        WorkflowDefinitionRecord row = document(WorkflowScopes.DEFAULT, "wf-heal", DefinitionStatus.AVAILABLE, T0);
        world.hold(WorkflowScopes.DEFAULT, "wf-heal", 1, HASH);
        world.store.put(row);
        world.loader.failRestored = 1;
        world.runOk();
        assertThat(world.view.applyFailures()).isEqualTo(1);
        world.store.finds.clear();

        // When the next pass heals it
        world.runOk();

        // Then it was re-read once and no longer fails
        assertThat(world.store.findCount(row.key())).isEqualTo(1);
        assertThat(world.view.applyFailures()).isZero();

        // And the pass after that skips it
        world.runOk();
        assertThat(world.store.findCount(row.key())).isEqualTo(1);
    }

    @Test
    @DisplayName("a retired row is settled only after its hand-off succeeded")
    void settlesARetiredRowOnlyAfterSuccess() {
        // Given a retired document row whose first metadata hand-off fails
        WorkflowDefinitionRecord retired = document(WorkflowScopes.DEFAULT, "wf-retired", DefinitionStatus.RETIRED, T0);
        world.store.put(retired);
        world.loader.failMetadata = 1;
        world.runOk();
        assertThat(world.view.applyFailures()).isEqualTo(1);
        world.store.finds.clear();

        // When the next pass runs
        world.runOk();

        // Then the row took the full path, was delivered and is clear
        assertThat(world.store.findCount(retired.key())).isEqualTo(1);
        assertThat(world.loader.metadata).containsExactly(retired);
        assertThat(world.view.applyFailures()).isZero();

        // And the pass after that skips it
        world.runOk();
        assertThat(world.store.findCount(retired.key())).isEqualTo(1);
    }

    // ----- failing closed on a mark failure -----

    @Test
    @DisplayName("a restored runtime that cannot be marked deprecated is removed again and the row is counted")
    void failsClosedWhenTheDeprecationMarkFails() {
        // Given a deprecated code row and a registry that refuses to mark it
        world.deployCode("wf-code", HASH);
        WorkflowDefinitionRecord row = code("wf-code", DefinitionStatus.DEPRECATED, T0);
        world.store.put(row);
        world.registry.failMark = true;

        // When a pass restores it
        world.runOk();

        // Then nothing stays published or attributed, and the row is counted
        assertThat(world.registry.holds("wf-code", 1)).isFalse();
        assertThat(world.registry.unregistered).containsExactly("wf-code@1");
        RuntimeWorkflow published = world.registry.history.getFirst();
        assertThat(world.registrations.classify(WorkflowScopes.DEFAULT, "wf-code", 1, published))
                .isEqualTo(ManagedRegistrations.Standing.UNATTRIBUTED);
        assertThat(world.view.applyFailures()).isEqualTo(1);
        assertThat(world.view.recorded(row.key())).isEmpty();
    }

    // ----- deprecation before drift -----

    @Test
    @DisplayName("a deprecated row marks its held runtime even when the hash drifted, and still counts as drift")
    void marksBeforeReportingDrift() {
        // Given a deprecated row and a held runtime with another hash
        WorkflowDefinitionRecord row = document(WorkflowScopes.DEFAULT, "wf-drift", DefinitionStatus.DEPRECATED, T0);
        world.hold(WorkflowScopes.DEFAULT, "wf-drift", 1, OTHER_HASH);
        world.store.put(row);

        // When a pass runs
        world.runOk();

        // Then the runtime is marked deprecated and the row is counted as drift
        assertThat(world.registry.isDeprecated(new DefinitionKey(WorkflowScopes.DEFAULT, "wf-drift", 1)))
                .isTrue();
        assertThat(world.view.applyFailures()).isEqualTo(1);
        assertThat(log.warnings())
                .singleElement()
                .satisfies(text -> assertThat(text).contains("wf-drift").contains("DRIFT"));
    }

    @Test
    @DisplayName("a retired row of one scope never evicts the runtime another scope holds for the same id and version")
    void neverEvictsAnotherScopesRuntimeForARetiredRow() {
        // Given a runtime held for tenant-b and a retired row of tenant-a with the same id, version and hash
        WorkflowDefinitionRecord retired = document("tenant-a", "wf-shared", DefinitionStatus.RETIRED, T0);
        world.hold("tenant-b", "wf-shared", 1, HASH);
        world.store.put(retired);

        // When a pass runs
        world.runOk();

        // Then tenant-b's runtime is still registered and nothing failed
        assertThat(world.registry.holds("wf-shared", 1)).isTrue();
        assertThat(world.registry.unregistered).isEmpty();
        assertThat(world.view.applyFailures()).isZero();
    }

    @Test
    @DisplayName("a settled live row is checked again when the held runtime carries another plan hash")
    void rechecksALiveRowWhoseHeldRuntimeHashChanged() {
        // Given an active row that a first pass applied and the next pass would skip
        WorkflowDefinitionRecord row = document(WorkflowScopes.DEFAULT, "wf-rehash", DefinitionStatus.AVAILABLE, T0);
        world.hold(WorkflowScopes.DEFAULT, "wf-rehash", 1, HASH);
        world.store.put(row);
        world.runOk();
        assertThat(world.view.applyFailures()).isZero();

        // When the node's runtime for that version is replaced by one with another plan hash
        world.registry.drop("wf-rehash", 1);
        world.hold(WorkflowScopes.DEFAULT, "wf-rehash", 1, OTHER_HASH);
        world.store.finds.clear();
        world.runOk();

        // Then the row was read again and counted as drift
        assertThat(world.store.findCount(row.key())).isEqualTo(1);
        assertThat(world.view.applyFailures()).isEqualTo(1);
    }

    // ----- unattributed runtimes -----

    @Test
    @DisplayName("a retired row with an unattributed runtime has nothing of its own to remove and does not fail")
    void settlesARetiredRowWithAnUnattributedRuntime() {
        // Given a retired row, an applied-looking view entry, and a runtime that nobody attributed
        WorkflowDefinitionRecord retired = document(WorkflowScopes.DEFAULT, "wf-foreign", DefinitionStatus.RETIRED, T0);
        world.holdWithoutDefinitionOrAttribution("wf-foreign", 1, HASH);
        world.store.put(retired);
        world.view.applied(retired);

        // When a pass runs
        world.runOk();

        // Then the foreign runtime is untouched, the view entry is gone and nothing fails
        assertThat(world.registry.holds("wf-foreign", 1)).isTrue();
        assertThat(world.registry.unregistered).isEmpty();
        assertThat(world.view.recorded(retired.key())).isEmpty();
        assertThat(world.view.applyFailures()).isZero();
        assertThat(log.warnings()).isEmpty();
    }

    @Test
    @DisplayName("a live row with an unattributed runtime fails as an invalid transition")
    void failsALiveRowWithAnUnattributedRuntime() {
        // Given a live row and a runtime that nobody attributed
        world.holdWithoutDefinitionOrAttribution("wf-foreign", 1, HASH);
        world.store.put(document(WorkflowScopes.DEFAULT, "wf-foreign", DefinitionStatus.AVAILABLE, T0));

        // When a pass runs
        world.runOk();

        // Then the row is counted and the foreign runtime is untouched
        assertThat(world.view.applyFailures()).isEqualTo(1);
        assertThat(world.registry.holds("wf-foreign", 1)).isTrue();
        assertThat(log.warnings())
                .singleElement()
                .satisfies(text -> assertThat(text).contains("wf-foreign").contains("INVALID_TRANSITION"));
    }

    // ----- unreadable table -----

    @Test
    @DisplayName("a store error while re-reading a row fails the pass instead of counting healthy rows")
    void failsThePassWhenARowCannotBeRead() {
        // Given three rows, the second of which cannot be read
        world.deployCode("wf-a", HASH);
        world.deployCode("wf-b", HASH);
        world.deployCode("wf-c", HASH);
        world.store.put(code("wf-a", DefinitionStatus.AVAILABLE, T0));
        WorkflowDefinitionRecord unreadable = code("wf-b", DefinitionStatus.AVAILABLE, T0);
        world.store.put(unreadable);
        world.store.put(code("wf-c", DefinitionStatus.AVAILABLE, T0));
        IllegalStateException refused = new IllegalStateException("connection reset");
        world.store.findFailures.put(unreadable.key(), refused);

        // When a pass runs
        Future<Void> pass = world.pass.run();

        // Then the pass fails with the store error and no row is counted as failing
        assertThat(pass.failed()).isTrue();
        assertThat(pass.cause()).isSameAs(refused);
        assertThat(world.view.applyFailures()).isZero();
    }

    // ----- mismatch between listing and re-read -----

    @Test
    @DisplayName("a row listed as not live but re-read as live is left untouched, failure included")
    void leavesARowThatBecameLiveUntouchedByTheRemovalRound() {
        // Given a listing that shows a candidate row, a table that holds the row active, and a recorded failure
        WorkflowDefinitionRecord listed = document(WorkflowScopes.DEFAULT, "wf-moved", DefinitionStatus.CANDIDATE, T0);
        WorkflowDefinitionRecord actual =
                document(WorkflowScopes.DEFAULT, "wf-moved", DefinitionStatus.AVAILABLE, T0.plusSeconds(1));
        world.hold(WorkflowScopes.DEFAULT, "wf-moved", 1, HASH);
        world.store.put(actual);
        world.store.listing = () -> List.of(listed);
        world.view.failed(listed.key());

        // When a pass runs
        world.runOk();

        // Then nothing was done to the row: the runtime stays, the failure stays, and nothing was handed over
        assertThat(world.registry.holds("wf-moved", 1)).isTrue();
        assertThat(world.view.failing(listed.key())).isTrue();
        assertThat(world.loader.metadata).isEmpty();
        assertThat(world.loader.restored).isEmpty();
    }

    @Test
    @DisplayName("a row listed as live but re-read as retired is left untouched, failure included")
    void leavesARowThatRetiredUntouchedByTheRestoreRound() {
        // Given a listing that shows a live row, a table that holds it retired, a held runtime and a failure
        WorkflowDefinitionRecord listed = document(WorkflowScopes.DEFAULT, "wf-moved", DefinitionStatus.AVAILABLE, T0);
        WorkflowDefinitionRecord actual =
                document(WorkflowScopes.DEFAULT, "wf-moved", DefinitionStatus.RETIRED, T0.plusSeconds(1));
        world.hold(WorkflowScopes.DEFAULT, "wf-moved", 1, HASH);
        world.store.put(actual);
        world.store.listing = () -> List.of(listed);
        world.view.failed(listed.key());

        // When a pass runs
        world.runOk();

        // Then nothing was done to the row; the next pass sees the retired listing
        assertThat(world.registry.holds("wf-moved", 1)).isTrue();
        assertThat(world.registry.unregistered).isEmpty();
        assertThat(world.view.failing(listed.key())).isTrue();
        assertThat(world.loader.metadata).isEmpty();
    }

    @Test
    @DisplayName("a listed row that vanished before it was re-read loses its view entry and its failure")
    void forgetsARowThatVanished() {
        // Given a listed row that is no longer in the table, with a view entry and a failure
        WorkflowDefinitionRecord listed = document(WorkflowScopes.DEFAULT, "wf-gone", DefinitionStatus.AVAILABLE, T0);
        world.store.listing = () -> List.of(listed);
        world.view.applied(listed);
        world.view.failed(listed.key());

        // When a pass runs
        world.runOk();

        // Then both are gone
        assertThat(world.view.recorded(listed.key())).isEmpty();
        assertThat(world.view.applyFailures()).isZero();
    }

    // ----- failure log throttling -----

    @Test
    @DisplayName("a repeated identical failure logs a warning once, a changed reason warns again, the count stays")
    void throttlesRepeatedFailureWarnings() {
        // Given a code row the deployed code does not provide
        WorkflowDefinitionRecord row = code("wf-missing", DefinitionStatus.AVAILABLE, T0);
        world.store.put(row);

        // When three passes fail the same way
        world.runOk();
        world.runOk();
        world.runOk();

        // Then one warning was logged, the repeats were debug, and the row is counted once
        assertThat(log.warnings()).hasSize(1);
        assertThat(log.warnings().getFirst()).contains("wf-missing").contains("CODE_UNAVAILABLE");
        assertThat(log.debugs()).hasSize(2);
        assertThat(world.view.applyFailures()).isEqualTo(1);

        // When the failure changes its reason
        world.deployCode("wf-missing", OTHER_HASH);
        world.runOk();

        // Then it warns again with the new reason
        assertThat(log.warnings()).hasSize(2);
        assertThat(log.warnings().getLast()).contains("DRIFT");
        assertThat(world.view.applyFailures()).isEqualTo(1);

        // When it heals and then fails again
        world.deployCode("wf-missing", HASH);
        world.runOk();
        assertThat(world.view.applyFailures()).isZero();
        world.registry.drop("wf-missing", 1);
        world.deployCode("wf-missing", OTHER_HASH);
        world.runOk();

        // Then the new failure warns again
        assertThat(log.warnings()).hasSize(3);
    }

    // ----- remaining outcomes -----

    @Test
    @DisplayName("a publishing row is left alone, even when its runtime is held")
    void leavesAPublishingRowAlone() {
        // Given a publishing row whose runtime this node holds
        world.hold(WorkflowScopes.DEFAULT, "wf-publishing", 1, HASH);
        world.store.put(document(WorkflowScopes.DEFAULT, "wf-publishing", DefinitionStatus.PUBLISHING, T0));

        // When a pass runs
        world.runOk();

        // Then nothing happened to it
        assertThat(world.registry.holds("wf-publishing", 1)).isTrue();
        assertThat(world.registry.unregistered).isEmpty();
        assertThat(world.loader.metadata).isEmpty();
        assertThat(world.loader.restored).isEmpty();
        assertThat(world.view.applyFailures()).isZero();
        assertThat(world.view.registered()).isEmpty();
    }

    @Test
    @DisplayName("a stored document that compiles to another hash fails as drift and publishes nothing")
    void failsADocumentWhoseCompiledHashDiffers() {
        // Given a live document row whose loader compiles it to another hash
        world.store.put(document(WorkflowScopes.DEFAULT, "wf-doc", DefinitionStatus.AVAILABLE, T0));
        world.loader.hash = OTHER_HASH;

        // When a pass runs
        world.runOk();

        // Then the row is counted as drift and nothing is published
        assertThat(world.view.applyFailures()).isEqualTo(1);
        assertThat(world.registry.holds("wf-doc", 1)).isFalse();
        assertThat(log.warnings())
                .singleElement()
                .satisfies(text -> assertThat(text).contains("wf-doc").contains("DRIFT"));
    }

    @Test
    @DisplayName("the logged message of a parse failure carries no source text")
    void logsNoSourceTextOfAParseFailure() {
        // Given a stored document whose loader fails with a message that quotes the text
        WorkflowDefinitionRecord row = document(WorkflowScopes.DEFAULT, "wf-parse", DefinitionStatus.AVAILABLE, T0);
        world.store.put(row);
        world.store.documents.put(row.key(), new WorkflowDefinitionDocument(row.key(), "SECRET-DOCUMENT-TEXT", "YAML"));
        world.loader.quoteDocumentInFailure = true;

        // When a pass runs
        world.runOk();

        // Then the row is counted and nothing logged, at any level, contains the text
        assertThat(world.view.applyFailures()).isEqualTo(1);
        assertThat(log.warnings()).isNotEmpty();
        assertThat(log.everything()).noneSatisfy(text -> assertThat(text).contains("SECRET-DOCUMENT-TEXT"));
    }

    // ----- fixtures -----

    private static WorkflowDefinitionRecord document(
            String tenant, String id, DefinitionStatus status, Instant changedAt) {
        return new WorkflowDefinitionRecord(
                new DefinitionKey(tenant, id, 1),
                DefinitionKind.DOCUMENT,
                "example.Contract",
                "example.State",
                HASH,
                status,
                PROVENANCE,
                "YAML",
                T0,
                changedAt);
    }

    private static WorkflowDefinitionRecord code(String id, DefinitionStatus status, Instant changedAt) {
        return new WorkflowDefinitionRecord(
                new DefinitionKey(WorkflowScopes.DEFAULT, id, 1),
                DefinitionKind.CODE,
                "example.Contract",
                "example.State",
                HASH,
                status,
                PROVENANCE,
                null,
                T0,
                changedAt);
    }

    /** The pass under test together with its fakes. */
    private static final class World {
        final TableStore store = new TableStore();
        final Registry registry = new Registry();
        final Loader loader = new Loader();
        final ManagedRegistrations registrations = new ManagedRegistrations();
        final WorkflowCodeDefinitionCatalog catalog = new WorkflowCodeDefinitionCatalog();
        final DefaultWorkflowDefinitionRegistryView view = new DefaultWorkflowDefinitionRegistryView();
        final Timers timers = new Timers();
        final DefinitionPropagationPass pass;
        private final Map<String, String> deployed = new LinkedHashMap<>();

        World(Optional<WorkflowDefinitionsConfig> config) {
            pass = new DefinitionPropagationPass(
                    Optional.of(store),
                    () -> registry,
                    Set.of(loader),
                    new DefinitionApplyQueue(),
                    registrations,
                    catalog,
                    view,
                    timers.vertx(),
                    config);
        }

        void runOk() {
            Future<Void> result = pass.run();
            assertThat(result.succeeded())
                    .as("the pass completed: %s", result.cause())
                    .isTrue();
        }

        /** Registers a runtime with a retained definition and attributes it to the owner. */
        void hold(String tenant, String id, long version, String hash) {
            registry.hashes.put(id + "@" + version, hash);
            registry.register(registry.definition(id, version));
            registrations.attribute(
                    tenant, id, version, registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, version)));
        }

        void holdWithoutDefinition(String tenant, String id, long version, String hash) {
            registrations.attribute(tenant, id, version, registry.put(id, version, hash));
        }

        void holdWithoutDefinitionOrAttribution(String id, long version, String hash) {
            registry.put(id, version, hash);
        }

        /** Makes the deployed code provide a version-1 definition with the plan hash. */
        void deployCode(String id, String hash) {
            deployed.put(id, hash);
            Registry code = new Registry();
            deployed.forEach((deployedId, deployedHash) -> {
                code.hashes.put(deployedId + "@1", deployedHash);
                code.register(code.definition(deployedId, 1));
            });
            catalog.capture(code, Set.of());
        }
    }

    /** A table whose reads are counted and can be made to fail, gate or answer another listing. */
    private static final class TableStore implements WorkflowDefinitionRegistryStore {
        final Map<DefinitionKey, WorkflowDefinitionRecord> rows = new LinkedHashMap<>();
        final Map<DefinitionKey, WorkflowDefinitionDocument> documents = new HashMap<>();
        final Map<DefinitionKey, Integer> finds = new HashMap<>();
        final Map<DefinitionKey, Throwable> findFailures = new HashMap<>();
        final Map<DefinitionKey, Promise<Optional<WorkflowDefinitionDocument>>> documentGates = new HashMap<>();
        Supplier<List<WorkflowDefinitionRecord>> listing;
        int lists;

        void put(WorkflowDefinitionRecord row) {
            rows.put(row.key(), row);
        }

        int findCount(DefinitionKey key) {
            return finds.getOrDefault(key, 0);
        }

        int totalFinds() {
            return finds.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public Future<List<WorkflowDefinitionRecord>> list(DefinitionFilter filter) {
            lists++;
            return Future.succeededFuture(listing != null ? listing.get() : List.copyOf(rows.values()));
        }

        @Override
        public Future<Optional<WorkflowDefinitionRecord>> find(DefinitionKey key) {
            finds.merge(key, 1, Integer::sum);
            Throwable failure = findFailures.get(key);
            if (failure != null) return Future.failedFuture(failure);
            return Future.succeededFuture(Optional.ofNullable(rows.get(key)));
        }

        @Override
        public Future<Optional<WorkflowDefinitionDocument>> findDocument(DefinitionKey key) {
            Promise<Optional<WorkflowDefinitionDocument>> gate = documentGates.get(key);
            if (gate != null) return gate.future();
            return Future.succeededFuture(
                    Optional.of(documents.getOrDefault(key, new WorkflowDefinitionDocument(key, "{}", "YAML"))));
        }

        @Override
        public Future<Void> insertCandidate(WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<ReconcileOutcome> upsertDocument(
                WorkflowDefinitionRecord record, WorkflowDefinitionDocument document, boolean activateDefault) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<ReconcileOutcome> upsertCode(WorkflowDefinitionRecord record, boolean activateDefault) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<ActivationClaim> claim(DefinitionKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> complete(ActivationClaim claim) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> release(ActivationClaim claim) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> transition(DefinitionKey key, DefinitionStatus from, DefinitionStatus to) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> resetStalePublishing(String definitionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> putActivation(DefinitionActivation activation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> removeActivation(String scopeKey, String definitionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<List<DefinitionActivation>> listActivations(String scopeKey) {
            return Future.succeededFuture(List.of());
        }

        @Override
        public Future<Void> retire(DefinitionKey key) {
            throw new UnsupportedOperationException();
        }
    }

    /** A registry that rejects a second registration, retains definitions and creates a runtime per registration. */
    private static final class Registry implements WorkflowRegistry {
        private final Map<String, RuntimeWorkflow> runtimes = new LinkedHashMap<>();
        private final Map<String, WorkflowDefinition<?, ?>> definitions = new LinkedHashMap<>();
        private final Set<String> deprecated = new HashSet<>();
        final Map<String, String> hashes = new HashMap<>();
        final List<RuntimeWorkflow> history = new ArrayList<>();
        final List<String> unregistered = new ArrayList<>();
        boolean failMark;

        WorkflowDefinition<?, ?> definition(String id, long version) {
            WorkflowDefinition<?, ?> definition = Mockito.mock(WorkflowDefinition.class);
            Mockito.when(definition.definitionId()).thenReturn(id);
            Mockito.when(definition.definitionVersion()).thenReturn(version);
            return definition;
        }

        boolean holds(String id, long version) {
            return runtimes.containsKey(id + "@" + version);
        }

        /** Registers a bare runtime, without a retained definition. */
        RuntimeWorkflow put(String id, long version, String hash) {
            RuntimeWorkflow runtime = runtime(id, version, hash);
            runtimes.put(id + "@" + version, runtime);
            return runtime;
        }

        /** Removes the runtime without telling anyone, as an unmanaged caller could. */
        void drop(String id, long version) {
            runtimes.remove(id + "@" + version);
            definitions.remove(id + "@" + version);
        }

        private RuntimeWorkflow runtime(String id, long version, String hash) {
            WorkflowPlan plan = Mockito.mock(WorkflowPlan.class);
            Mockito.when(plan.planHash()).thenReturn(hash);
            Mockito.when(plan.definitionId()).thenReturn(id);
            Mockito.when(plan.definitionVersion()).thenReturn(version);
            RuntimeWorkflow runtime = Mockito.mock(RuntimeWorkflow.class);
            Mockito.when(runtime.plan()).thenReturn(plan);
            Mockito.when(runtime.definitionScopeKey()).thenReturn(WorkflowScopes.DEFAULT);
            history.add(runtime);
            return runtime;
        }

        @Override
        public void register(WorkflowDefinition<?, ?> def) {
            String name = def.definitionId() + "@" + def.definitionVersion();
            if (runtimes.containsKey(name))
                throw new WorkflowDefinitionException("definition '" + def.definitionId() + "' v"
                        + def.definitionVersion() + " is already registered");
            runtimes.put(name, runtime(def.definitionId(), def.definitionVersion(), hashes.getOrDefault(name, HASH)));
            definitions.put(name, def);
        }

        @Override
        public RuntimeWorkflow resolve(DefinitionKey key) {
            String definitionId = key.definitionId();
            long version = key.definitionVersion();
            RuntimeWorkflow found = runtimes.get(definitionId + "@" + version);
            if (found == null) throw new WorkflowVersionPinUnavailableException(definitionId, version, null);
            return found;
        }

        @Override
        public Optional<RuntimeWorkflow> find(DefinitionKey key) {
            return Optional.ofNullable(runtimes.get(key.definitionId() + "@" + key.definitionVersion()));
        }

        @Override
        public List<DefinitionKey> keysFor(String definitionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void register(String scopeKey, WorkflowDefinition<?, ?> def) {
            register(def);
        }

        @Override
        public Optional<RegisteredDefinitionClass> definitionClass(Class<?> definitionClass) {
            return Optional.empty();
        }

        @Override
        public Collection<RuntimeWorkflow> allRegistered() {
            return List.copyOf(runtimes.values());
        }

        @Override
        public Optional<WorkflowDefinition<?, ?>> registeredDefinition(DefinitionKey key) {
            String definitionId = key.definitionId();
            long version = key.definitionVersion();
            return Optional.ofNullable(definitions.get(definitionId + "@" + version));
        }

        @Override
        public void unregister(DefinitionKey key) {
            String definitionId = key.definitionId();
            long version = key.definitionVersion();
            String name = definitionId + "@" + version;
            unregistered.add(name);
            runtimes.remove(name);
            definitions.remove(name);
            deprecated.remove(name);
        }

        @Override
        public void markDeprecated(DefinitionKey key) {
            String definitionId = key.definitionId();
            long version = key.definitionVersion();
            if (failMark) throw new IllegalStateException("cannot mark");
            deprecated.add(definitionId + "@" + version);
        }

        @Override
        public void unmarkDeprecated(DefinitionKey key) {
            deprecated.remove(key.definitionId() + "@" + key.definitionVersion());
        }

        @Override
        public boolean isDeprecated(DefinitionKey key) {
            String definitionId = key.definitionId();
            long version = key.definitionVersion();
            return deprecated.contains(definitionId + "@" + version);
        }
    }

    /** A YAML loader that records its hand-offs and can be made to fail in the ways the tests need. */
    private static final class Loader implements PersistedDefinitionLoader {
        final List<WorkflowDefinitionRecord> restored = new ArrayList<>();
        final List<WorkflowDefinitionRecord> metadata = new ArrayList<>();
        int failRestored;
        int failMetadata;
        String hash = HASH;
        Error loadError;
        String loadErrorFor;
        boolean quoteDocumentInFailure;

        @Override
        public boolean supports(String documentFormat) {
            return "YAML".equals(documentFormat);
        }

        @Override
        public List<DocumentOrigin> documentOrigins() {
            return List.of();
        }

        @Override
        public WorkflowDefinition<?, ?> load(WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
            if (loadError != null && record.key().definitionId().equals(loadErrorFor)) throw loadError;
            if (quoteDocumentInFailure) throw new IllegalArgumentException("cannot parse: " + document.document());
            WorkflowDefinition<?, ?> definition = Mockito.mock(WorkflowDefinition.class);
            Mockito.when(definition.definitionId()).thenReturn(record.key().definitionId());
            Mockito.when(definition.definitionVersion()).thenReturn(record.key().definitionVersion());
            return definition;
        }

        @Override
        public String planHash(WorkflowDefinition<?, ?> definition) {
            return hash;
        }

        @Override
        public void onRestored(WorkflowDefinitionRecord record, WorkflowDefinition<?, ?> definition) {
            if (failRestored > 0) {
                failRestored--;
                throw new IllegalStateException("restored hand-off failed");
            }
            restored.add(record);
        }

        @Override
        public void onMetadata(WorkflowDefinitionRecord record) {
            if (failMetadata > 0) {
                failMetadata--;
                throw new IllegalStateException("metadata hand-off failed");
            }
            metadata.add(record);
        }
    }

    /** A Vert.x double whose one-shot timers fire only when the test fires them. */
    private static final class Timers {
        private final Vertx vertx = mock(Vertx.class);
        private final List<Timer> all = new ArrayList<>();

        Timers() {
            when(vertx.setTimer(anyLong(), ArgumentMatchers.<Handler<Long>>any()))
                    .thenAnswer(invocation -> {
                        Timer timer = new Timer(all.size() + 1L, invocation.getArgument(0), invocation.getArgument(1));
                        all.add(timer);
                        return timer.id;
                    });
            when(vertx.cancelTimer(anyLong())).thenAnswer(invocation -> {
                long id = invocation.getArgument(0);
                all.stream().filter(timer -> timer.id == id).forEach(timer -> timer.cancelled = true);
                return true;
            });
        }

        Vertx vertx() {
            return vertx;
        }

        List<Timer> pending() {
            return all.stream()
                    .filter(timer -> !timer.cancelled && !timer.fired)
                    .toList();
        }

        void fire(Timer timer) {
            timer.fired = true;
            timer.handler.handle(timer.id);
        }
    }

    private static final class Timer {
        final long id;
        final long delayMs;
        final Handler<Long> handler;
        boolean cancelled;
        boolean fired;

        Timer(long id, long delayMs, Handler<Long> handler) {
            this.id = id;
            this.delayMs = delayMs;
            this.handler = handler;
        }
    }

    /** Collects what the pass logs while it is open. */
    private static final class PassLog implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(WorkflowDefinitionPropagationPoller.class.getName());
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private final Level previousLevel = logger.getLevel();

        PassLog() {
            appender.start();
            logger.setLevel(Level.DEBUG);
            logger.addAppender(appender);
        }

        List<String> warnings() {
            return texts(Level.WARN, Level.ERROR);
        }

        List<String> debugs() {
            return texts(Level.DEBUG, Level.DEBUG);
        }

        List<String> everything() {
            return texts(Level.TRACE, Level.ERROR);
        }

        private List<String> texts(Level from, Level to) {
            return new ArrayList<>(appender.list)
                    .stream()
                            .filter(event ->
                                    event.getLevel().isGreaterOrEqual(from) && to.isGreaterOrEqual(event.getLevel()))
                            .map(event -> {
                                String failure = event.getThrowableProxy() == null
                                        ? ""
                                        : " " + event.getThrowableProxy().getMessage();
                                return event.getFormattedMessage() + failure;
                            })
                            .toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }
}
