// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static dev.vertique.workflow.engine.DefaultWorkflowDefinitionLifecycleTest.KEY;
import static dev.vertique.workflow.engine.DefaultWorkflowDefinitionLifecycleTest.PLAN_HASH;
import static dev.vertique.workflow.engine.DefaultWorkflowDefinitionLifecycleTest.publishingLoader;
import static dev.vertique.workflow.engine.DefaultWorkflowDefinitionLifecycleTest.row;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.engine.DefaultWorkflowDefinitionLifecycleTest.ActivationStore;
import dev.vertique.workflow.engine.spi.CommandActor;
import dev.vertique.workflow.engine.testsupport.InMemoryWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowNodeRoleException;
import dev.vertique.workflow.history.HistoryAttribution;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionLifecycleChange;
import dev.vertique.workflow.registry.store.DefinitionLifecycleChange.ActivationChange;
import dev.vertique.workflow.registry.store.DefinitionLifecycleChange.ActivationOperation;
import dev.vertique.workflow.registry.store.DefinitionLifecycleChange.VersionChange;
import dev.vertique.workflow.registry.store.DefinitionLifecycleChange.VersionOperation;
import dev.vertique.workflow.registry.store.DefinitionLifecycleFailure;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycleListener;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.HistoryActor;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies what {@link DefaultWorkflowDefinitionLifecycle} and the bootstrap reporting tell the registered
 * {@link WorkflowDefinitionLifecycleListener}s: one change per call, one failure for a refused or failed call, the
 * actor of the caller, and that no listener can alter the result of the operation.
 */
class DefinitionLifecycleEventsTest {

    private static final DefinitionKey V2 = new DefinitionKey(WorkflowScopes.DEFAULT, "wf", 2);
    private static final Instant NOW = Instant.parse("2026-02-03T04:05:06Z");
    private static final CommandActor ALICE =
            new CommandActor(CommandActor.Kind.USER, "alice", null, "carol", CommandActor.Attribution.VERIFIED);
    private static final CommandActor BOB =
            new CommandActor(CommandActor.Kind.SERVICE, "bob", null, null, CommandActor.Attribution.DEGRADED);

    /** Records every call, in order. */
    private static final class Recorder implements WorkflowDefinitionLifecycleListener {
        final List<DefinitionLifecycleChange> changes = new ArrayList<>();
        final List<DefinitionLifecycleFailure> failures = new ArrayList<>();

        @Override
        public void onDefinitionLifecycleChange(DefinitionLifecycleChange change) {
            changes.add(change);
        }

        @Override
        public void onDefinitionLifecycleFailed(DefinitionLifecycleFailure failure) {
            failures.add(failure);
        }

        VersionChange onlyVersionChange() {
            assertThat(changes).hasSize(1);
            return (VersionChange) changes.get(0);
        }

        ActivationChange onlyActivationChange() {
            assertThat(changes).hasSize(1);
            return (ActivationChange) changes.get(0);
        }
    }

    /** Throws from both callbacks. */
    private static final class Throwing implements WorkflowDefinitionLifecycleListener {
        @Override
        public void onDefinitionLifecycleChange(DefinitionLifecycleChange change) {
            throw new IllegalStateException("listener broke");
        }

        @Override
        public void onDefinitionLifecycleFailed(DefinitionLifecycleFailure failure) {
            throw new IllegalStateException("listener broke");
        }
    }

    private final AtomicReference<CommandActor> actor = new AtomicReference<>(ALICE);
    private final AtomicInteger captures = new AtomicInteger();
    private final Recorder recorder = new Recorder();
    private final DefinitionApplyQueue queue = new DefinitionApplyQueue();

    private DefinitionLifecycleEvents events(WorkflowDefinitionLifecycleListener... listeners) {
        return new DefinitionLifecycleEvents(
                Set.of(listeners),
                () -> {
                    captures.incrementAndGet();
                    return actor.get();
                },
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DefaultWorkflowDefinitionLifecycle lifecycle(
            InMemoryWorkflowDefinitionRegistryStore store,
            Set<PersistedDefinitionLoader> loaders,
            DefinitionLifecycleEvents events) {
        WorkflowRegistry registry = new DefaultWorkflowRegistry();
        return new DefaultWorkflowDefinitionLifecycle(
                store, () -> registry, loaders, queue, new WorkflowCodeDefinitionCatalog(), Optional.empty(), events);
    }

    /** A store holding the available document rows of versions 1 and 2 of {@code wf}, with no activation record. */
    private static InMemoryWorkflowDefinitionRegistryStore store() {
        InMemoryWorkflowDefinitionRegistryStore store = new InMemoryWorkflowDefinitionRegistryStore();
        seed(store, KEY);
        seed(store, V2);
        return store;
    }

    private static void seed(InMemoryWorkflowDefinitionRegistryStore store, DefinitionKey key) {
        var base = row(DefinitionStatus.AVAILABLE, PLAN_HASH, DefinitionKind.DOCUMENT);
        var record = new WorkflowDefinitionRecord(
                key,
                base.kind(),
                base.contractName(),
                base.stateTypeName(),
                base.planHash(),
                base.status(),
                base.provenance(),
                base.documentFormat(),
                base.createdAt(),
                base.statusChangedAt(),
                base.nameBinding(),
                null);
        store.upsertDocument(record, new WorkflowDefinitionDocument(key, "{}", "YAML"), false);
    }

    // --- Version operations ---

    @Test
    @DisplayName("a publication reports one PUBLISH change with the actor and the id of the call")
    void publishReportsAChange() {
        // Given a candidate whose document builds
        ActivationStore store = new ActivationStore();
        store.completions.add(Future.succeededFuture());
        WorkflowRegistry registry = new DefaultWorkflowRegistry();
        var lifecycle = new DefaultWorkflowDefinitionLifecycle(
                store,
                () -> registry,
                Set.of(publishingLoader()),
                queue,
                new WorkflowCodeDefinitionCatalog(),
                Optional.empty(),
                events(recorder));

        // When it is published
        Future<Void> result = lifecycle.publish(KEY);

        // Then the call succeeded and one change was reported
        assertThat(result.succeeded()).isTrue();
        VersionChange change = recorder.onlyVersionChange();
        assertThat(change.operation()).isEqualTo(VersionOperation.PUBLISH);
        assertThat(change.key()).isEqualTo(KEY);
        assertThat(change.actor()).isEqualTo(new HistoryActor(HistoryActor.Kind.USER, "alice", null));
        assertThat(change.onBehalfOf()).isEqualTo("carol");
        assertThat(change.attribution()).isEqualTo(HistoryAttribution.VERIFIED);
        assertThat(change.occurredAt()).isEqualTo(NOW);
        assertThat(change.changeId()).isNotNull();
        assertThat(recorder.failures).isEmpty();
    }

    @Test
    @DisplayName("a publication that is refused reports the typed reason on a failure with the same change")
    void publishRefusalIsReportedWithItsReason() {
        // Given a candidate and no loader
        ActivationStore store = new ActivationStore();
        var refusing = new DefaultWorkflowDefinitionLifecycle(
                store,
                () -> new DefaultWorkflowRegistry(),
                Set.of(),
                queue,
                new WorkflowCodeDefinitionCatalog(),
                Optional.empty(),
                events(recorder));

        // When it is published
        Future<Void> result = refusing.publish(KEY);

        // Then the refusal is reported with its reason and no change
        assertThat(result.failed()).isTrue();
        assertThat(recorder.changes).isEmpty();
        assertThat(recorder.failures).hasSize(1);
        DefinitionLifecycleFailure failure = recorder.failures.get(0);
        assertThat(failure.reason()).isEqualTo(Reason.LOADER_MISSING);
        assertThat(failure.errorType()).isEqualTo("WorkflowDefinitionLifecycleException");
        assertThat(failure.attempted()).isInstanceOf(VersionChange.class);
        assertThat(((VersionChange) failure.attempted()).operation()).isEqualTo(VersionOperation.PUBLISH);
    }

    @Test
    @DisplayName("a hash that drifted is reported as a DRIFT failure")
    void driftIsReported() {
        // Given a candidate whose stored hash differs from the rebuilt one
        ActivationStore store = new ActivationStore();
        store.planHash = "b".repeat(64);
        WorkflowRegistry registry = new DefaultWorkflowRegistry();
        var lifecycle = new DefaultWorkflowDefinitionLifecycle(
                store,
                () -> registry,
                Set.of(publishingLoader()),
                queue,
                new WorkflowCodeDefinitionCatalog(),
                Optional.empty(),
                events(recorder));

        // When it is published
        lifecycle.publish(KEY);

        // Then the failure carries DRIFT
        assertThat(recorder.failures).singleElement().satisfies(failure -> assertThat(failure.reason())
                .isEqualTo(Reason.DRIFT));
    }

    @Test
    @DisplayName("a commit that fails for another reason reports its class and no reason")
    void failingStoreIsReportedWithItsErrorType() {
        // Given a completion that fails with an unknown outcome
        ActivationStore store = new ActivationStore();
        store.completions.add(Future.failedFuture(new IllegalStateException("secret detail")));
        WorkflowRegistry registry = new DefaultWorkflowRegistry();
        var lifecycle = new DefaultWorkflowDefinitionLifecycle(
                store,
                () -> registry,
                Set.of(publishingLoader()),
                queue,
                new WorkflowCodeDefinitionCatalog(),
                Optional.empty(),
                events(recorder));

        // When it is published
        Future<Void> result = lifecycle.publish(KEY);

        // Then the failure is returned unchanged and reported by class only
        assertThat(result.cause()).isInstanceOf(IllegalStateException.class).hasMessage("secret detail");
        assertThat(recorder.failures).singleElement().satisfies(failure -> {
            assertThat(failure.reason()).isNull();
            assertThat(failure.errorType()).isEqualTo("IllegalStateException");
        });
    }

    @Test
    @DisplayName("deprecate, retire and reinstate each report their operation, and an accepted repeat is reported")
    void versionOperationsAreReported() {
        // Given two available versions
        var lifecycle = lifecycle(store(), Set.of(), events(recorder));

        // When version 1 is deprecated twice, retired and reinstated
        assertThat(lifecycle.deprecate(KEY).succeeded()).isTrue();
        assertThat(lifecycle.deprecate(KEY).succeeded()).isTrue();
        assertThat(lifecycle.retire(KEY).succeeded()).isTrue();
        // reinstating a retired document needs its loader, so the refusal is part of the sequence
        Future<Void> reinstated = lifecycle.reinstate(KEY);

        // Then each call is reported once, in order, with its own id
        assertThat(recorder.changes)
                .extracting(change -> ((VersionChange) change).operation())
                .containsExactly(VersionOperation.DEPRECATE, VersionOperation.DEPRECATE, VersionOperation.RETIRE);
        assertThat(recorder.changes)
                .extracting(DefinitionLifecycleChange::changeId)
                .doesNotHaveDuplicates();
        assertThat(reinstated.failed()).isTrue();
        assertThat(recorder.failures).singleElement().satisfies(failure -> {
            assertThat(failure.reason()).isEqualTo(Reason.LOADER_MISSING);
            assertThat(((VersionChange) failure.attempted()).operation()).isEqualTo(VersionOperation.REINSTATE);
        });
    }

    @Test
    @DisplayName("reinstating a deprecated version reports a REINSTATE change")
    void reinstateIsReported() {
        // Given a deprecated version
        var lifecycle = lifecycle(store(), Set.of(), events(recorder));
        lifecycle.deprecate(KEY);
        recorder.changes.clear();

        // When it is reinstated
        assertThat(lifecycle.reinstate(KEY).succeeded()).isTrue();

        // Then a REINSTATE change is reported
        assertThat(recorder.onlyVersionChange().operation()).isEqualTo(VersionOperation.REINSTATE);
    }

    @Test
    @DisplayName("a transition the store refuses is reported as INVALID_TRANSITION")
    void invalidTransitionIsReported() {
        // Given no version at all
        var lifecycle = lifecycle(new InMemoryWorkflowDefinitionRegistryStore(), Set.of(), events(recorder));

        // When a missing version is deprecated and retired
        lifecycle.deprecate(KEY);
        lifecycle.retire(KEY);

        // Then both refusals are reported with their reason
        assertThat(recorder.changes).isEmpty();
        assertThat(recorder.failures)
                .extracting(DefinitionLifecycleFailure::reason)
                .containsExactly(Reason.INVALID_TRANSITION, Reason.INVALID_TRANSITION);
    }

    @Test
    @DisplayName("a call that cannot name a change is not reported")
    void argumentsThatCannotFormAChangeAreNotReported() {
        // Given a lifecycle with a listener
        var lifecycle = lifecycle(store(), Set.of(), events(recorder));

        // When the key is null
        Future<Void> result = lifecycle.deprecate(null);

        // Then the call fails as before and nothing is reported
        assertThat(result.cause()).isInstanceOf(NullPointerException.class);
        assertThat(lifecycle.activate(null, "wf", WorkflowScopes.DEFAULT, 1).failed())
                .isTrue();
        assertThat(recorder.changes).isEmpty();
        assertThat(recorder.failures).isEmpty();
    }

    @Test
    @DisplayName("a refusal before the call is queued is reported when a change can be built")
    void refusalBeforeQueueingIsReported() {
        // Given a recording-only node
        WorkflowRegistry registry = new DefaultWorkflowRegistry();
        var lifecycle = new DefaultWorkflowDefinitionLifecycle(
                store(),
                () -> registry,
                Set.of(),
                queue,
                new WorkflowCodeDefinitionCatalog(),
                Optional.of(recordingOnly()),
                events(recorder));

        // When a version is published
        Future<Void> result = lifecycle.publish(KEY);

        // Then the node role refusal is reported by class
        assertThat(result.cause()).isInstanceOf(WorkflowNodeRoleException.class);
        assertThat(recorder.failures).singleElement().satisfies(failure -> assertThat(failure.errorType())
                .isEqualTo("WorkflowNodeRoleException"));
    }

    // --- Activation operations ---

    @Test
    @DisplayName("activate, deactivateDefault and follow report their scope, target and version")
    void activationOperationsAreReported() {
        // Given two available versions
        var lifecycle = lifecycle(store(), Set.of(), events(recorder));

        // When version 2 is activated, a scope follows, and the default is cleared
        assertThat(lifecycle
                        .activate(WorkflowScopes.DEFAULT, "wf", WorkflowScopes.DEFAULT, 2)
                        .succeeded())
                .isTrue();
        assertThat(lifecycle.follow("acme", "wf").succeeded()).isTrue();
        assertThat(lifecycle.deactivateDefault("wf").succeeded()).isTrue();

        // Then each is reported
        assertThat(recorder.changes).hasSize(3);
        ActivationChange activate = (ActivationChange) recorder.changes.get(0);
        assertThat(activate.operation()).isEqualTo(ActivationOperation.ACTIVATE);
        assertThat(activate.scopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(activate.definitionId()).isEqualTo("wf");
        assertThat(activate.targetScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(activate.version()).isEqualTo(2L);
        ActivationChange follow = (ActivationChange) recorder.changes.get(1);
        assertThat(follow.operation()).isEqualTo(ActivationOperation.FOLLOW);
        assertThat(follow.scopeKey()).isEqualTo("acme");
        assertThat(follow.targetScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(follow.version()).isNull();
        ActivationChange deactivate = (ActivationChange) recorder.changes.get(2);
        assertThat(deactivate.operation()).isEqualTo(ActivationOperation.DEACTIVATE_DEFAULT);
        assertThat(deactivate.scopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(deactivate.targetScopeKey()).isNull();
        assertThat(deactivate.version()).isNull();
        assertThat(recorder.failures).isEmpty();
    }

    @Test
    @DisplayName("an activation of a missing version and a scope that follows itself are reported as failures")
    void refusedActivationsAreReported() {
        // Given a lifecycle
        var lifecycle = lifecycle(store(), Set.of(), events(recorder));

        // When a missing version is activated and the default scope follows itself
        lifecycle.activate(WorkflowScopes.DEFAULT, "wf", WorkflowScopes.DEFAULT, 99);
        lifecycle.follow(WorkflowScopes.DEFAULT, "wf");

        // Then both are failures
        assertThat(recorder.changes).isEmpty();
        assertThat(recorder.failures).hasSize(2);
        assertThat(recorder.failures.get(0).reason()).isEqualTo(Reason.INVALID_TRANSITION);
        assertThat(recorder.failures.get(1).reason()).isNull();
        assertThat(recorder.failures.get(1).errorType()).isEqualTo("IllegalArgumentException");
    }

    // --- Isolation, actor and cost ---

    @Test
    @DisplayName("a listener that throws changes neither the result nor the other listeners' calls")
    void aThrowingListenerDoesNotAlterTheResult() {
        // Given a throwing listener next to a recorder
        var lifecycle = lifecycle(store(), Set.of(), events(new Throwing(), recorder));

        // When a change succeeds and another is refused
        Future<Void> ok = lifecycle.deprecate(KEY);
        Future<Void> refused = lifecycle.deprecate(new DefinitionKey(WorkflowScopes.DEFAULT, "wf", 9));

        // Then the results are the operations' own and the recorder heard both
        assertThat(ok.succeeded()).isTrue();
        assertThat(refused.failed()).isTrue();
        assertThat(recorder.changes).hasSize(1);
        assertThat(recorder.failures).hasSize(1);
    }

    @Test
    @DisplayName("two listeners are both called for the same change")
    void everyListenerIsCalled() {
        // Given two recorders
        Recorder other = new Recorder();
        var lifecycle = lifecycle(store(), Set.of(), events(recorder, other));

        // When a version is deprecated
        lifecycle.deprecate(KEY);

        // Then both received the same change
        assertThat(recorder.changes).hasSize(1);
        assertThat(other.changes).isEqualTo(recorder.changes);
    }

    @Test
    @DisplayName("the actor is captured when the method is called, before the queued work runs")
    void actorIsCapturedOnTheCallingThreadBeforeTheQueueRuns() {
        // Given work already running for the id, so that the next call waits in the queue
        Promise<Void> gate = Promise.promise();
        queue.submit("wf", gate::future);
        var lifecycle = lifecycle(store(), Set.of(), events(recorder));

        // When a call is made as alice, and the binding changes to bob before the queue runs it
        Future<Void> result = lifecycle.deprecate(KEY);
        actor.set(BOB);
        assertThat(result.isComplete()).isFalse();
        gate.complete();

        // Then the change names alice
        assertThat(result.succeeded()).isTrue();
        assertThat(recorder.onlyVersionChange().actor().id()).isEqualTo("alice");
        assertThat(captures).hasValue(1);
    }

    @Test
    @DisplayName("a capture that throws leaves the call to proceed with an absent actor")
    void aFailedCaptureFallsBackToNoActor() {
        // Given an actor binding that throws
        var events = new DefinitionLifecycleEvents(
                Set.of(recorder),
                () -> {
                    throw new IllegalStateException("mapper broke");
                },
                Clock.fixed(NOW, ZoneOffset.UTC));
        var lifecycle = lifecycle(store(), Set.of(), events);

        // When a version is deprecated
        Future<Void> result = lifecycle.deprecate(KEY);

        // Then the call succeeded and the change names no actor
        assertThat(result.succeeded()).isTrue();
        VersionChange change = recorder.onlyVersionChange();
        assertThat(change.actor().kind()).isEqualTo(HistoryActor.Kind.NONE);
        assertThat(change.attribution()).isEqualTo(HistoryAttribution.ABSENT);
    }

    @Test
    @DisplayName("with no listener bound the actor is never captured and the result is the operation's own")
    void noListenerIsAPurePassThrough() {
        // Given events with no listener
        var lifecycle = lifecycle(store(), Set.of(), events());

        // When operations succeed and fail
        Future<Void> ok = lifecycle.deprecate(KEY);
        Future<Void> refused = lifecycle.deprecate(new DefinitionKey(WorkflowScopes.DEFAULT, "wf", 9));

        // Then the results are unchanged and nothing was captured
        assertThat(ok.succeeded()).isTrue();
        assertThat(refused.failed()).isTrue();
        assertThat(captures).hasValue(0);
    }

    // --- Bootstrap ---

    @Test
    @DisplayName("bootstrap reports a SYSTEM publication, and an activation when the default took effect")
    void bootstrapReportsFirstPublications() {
        // Given events with a recorder
        var events = events(recorder);

        // When startup reconciled an insert, an insert that became the default, and the outcomes that change nothing
        events.bootstrapped(KEY, ReconcileOutcome.INSERTED);
        events.bootstrapped(V2, ReconcileOutcome.INSERTED_AND_ACTIVATED);
        for (ReconcileOutcome none : List.of(
                ReconcileOutcome.UNCHANGED,
                ReconcileOutcome.REHASHED,
                ReconcileOutcome.DRIFT,
                ReconcileOutcome.KIND_CONFLICT)) {
            events.bootstrapped(KEY, none);
        }

        // Then three changes were reported, by the system with the reason bootstrap
        assertThat(recorder.changes).hasSize(3);
        VersionChange first = (VersionChange) recorder.changes.get(0);
        assertThat(first.operation()).isEqualTo(VersionOperation.PUBLISH);
        assertThat(first.key()).isEqualTo(KEY);
        assertThat(first.actor()).isEqualTo(new HistoryActor(HistoryActor.Kind.SYSTEM, null, "bootstrap"));
        assertThat(first.attribution()).isEqualTo(HistoryAttribution.VERIFIED);
        VersionChange second = (VersionChange) recorder.changes.get(1);
        assertThat(second.operation()).isEqualTo(VersionOperation.PUBLISH);
        assertThat(second.key()).isEqualTo(V2);
        ActivationChange activation = (ActivationChange) recorder.changes.get(2);
        assertThat(activation.operation()).isEqualTo(ActivationOperation.ACTIVATE);
        assertThat(activation.scopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(activation.targetScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(activation.definitionId()).isEqualTo("wf");
        assertThat(activation.version()).isEqualTo(2L);
        assertThat(activation.actor()).isEqualTo(second.actor());
        assertThat(activation.changeId()).isNotEqualTo(second.changeId());
        assertThat(recorder.failures).isEmpty();
        assertThat(captures).hasValue(0);
    }

    @Test
    @DisplayName("the in-memory store tells an insert that became the default from one that did not")
    void inMemoryStoreDistinguishesTheActivatedInsert() {
        // Given a store that holds version 2 as the default
        var store = new InMemoryWorkflowDefinitionRegistryStore();
        var higher = row(DefinitionStatus.AVAILABLE, PLAN_HASH, DefinitionKind.DOCUMENT);
        store.upsertDocument(withKey(higher, V2), new WorkflowDefinitionDocument(V2, "{}", "YAML"), true);

        // When version 1 is inserted with activation requested
        Future<ReconcileOutcome> lower =
                store.upsertDocument(higher, new WorkflowDefinitionDocument(KEY, "{}", "YAML"), true);
        Future<ReconcileOutcome> fresh = store.upsertDocument(
                withKey(higher, new DefinitionKey(WorkflowScopes.DEFAULT, "other", 1)),
                new WorkflowDefinitionDocument(new DefinitionKey(WorkflowScopes.DEFAULT, "other", 1), "{}", "YAML"),
                true);

        // Then only the insert that took the default is reported as activated
        assertThat(lower.result()).isEqualTo(ReconcileOutcome.INSERTED);
        assertThat(fresh.result()).isEqualTo(ReconcileOutcome.INSERTED_AND_ACTIVATED);
    }

    private static WorkflowExecutorConfig recordingOnly() {
        WorkflowExecutorConfig defaults = WorkflowExecutorConfig.defaults();
        return new WorkflowExecutorConfig(
                defaults.instances(),
                defaults.concurrency(),
                defaults.pollIntervalMs(),
                defaults.leaseSeconds(),
                defaults.maxAttempts(),
                defaults.transientBackoffBaseMs(),
                defaults.transientBackoffMaxMs(),
                defaults.maxStepsPerSegment(),
                defaults.stuckAfterSeconds(),
                false);
    }

    private static WorkflowDefinitionRecord withKey(WorkflowDefinitionRecord base, DefinitionKey key) {
        return new WorkflowDefinitionRecord(
                key,
                base.kind(),
                base.contractName(),
                base.stateTypeName(),
                base.planHash(),
                base.status(),
                base.provenance(),
                base.documentFormat(),
                base.createdAt(),
                base.statusChangedAt(),
                base.nameBinding(),
                null);
    }
}
