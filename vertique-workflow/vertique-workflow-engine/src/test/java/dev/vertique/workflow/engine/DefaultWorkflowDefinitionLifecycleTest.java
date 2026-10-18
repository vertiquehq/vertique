// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.ActivationOutcome;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

/**
 * Verifies the claim release of {@link DefaultWorkflowDefinitionLifecycle} when a durable activation
 * fails and the follow-up read of the definition row fails as well.
 */
class DefaultWorkflowDefinitionLifecycleTest {

    private static final DefinitionKey KEY = new DefinitionKey(null, "wf", 1);
    private static final String PLAN_HASH = "a".repeat(64);

    @Test
    @DisplayName("a failed reread after an activation that published nothing still releases the claim once")
    void releasesClaimWhenNothingWasPublishedAndRereadFails() {
        // Given a CANDIDATE document row, a claim that succeeds, and no loader bound
        FakeStore store = new FakeStore();
        DefaultWorkflowDefinitionLifecycle lifecycle = lifecycle(store, Set.of(), Mockito.mock(WorkflowRegistry.class));

        // When the activation fails with LOADER_MISSING and the reread of the row fails
        Future<Void> result = lifecycle.activate(KEY);

        // Then the activation failed with the original LOADER_MISSING refusal ...
        assertThat(result.failed()).isTrue();
        assertThat(result.cause())
                .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, e -> assertThat(e.reason())
                        .isEqualTo(WorkflowDefinitionLifecycleException.Reason.LOADER_MISSING));
        // ... and the claim was released exactly once
        assertThat(store.releases.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("control: a failed reread after a local publication does not release the claim")
    void keepsClaimWhenPublicationExistsAndRereadFails() {
        // Given a loader that publishes the definition locally, and a completion that fails unknown
        FakeStore store = new FakeStore();
        store.completion = Future.failedFuture(new IllegalStateException("commit outcome unknown"));
        WorkflowRegistry registry = Mockito.mock(WorkflowRegistry.class);
        Mockito.when(registry.resolvePinned("wf", 1)).thenReturn(Mockito.mock(RuntimeWorkflow.class));
        DefaultWorkflowDefinitionLifecycle lifecycle = lifecycle(store, Set.of(publishingLoader()), registry);

        // When the reread of the row fails after the publication
        Future<Void> result = lifecycle.activate(KEY);

        // Then the activation failed and the claim stays owned by reconciliation
        assertThat(result.failed()).isTrue();
        assertThat(store.releases.get()).isZero();
    }

    @ParameterizedTest(name = "a {0} row is deprecated with that status and then marked locally")
    @EnumSource(
            value = DefinitionStatus.class,
            names = {"ACTIVE", "SUPERSEDED"})
    void deprecatesCommittedRowBeforeMarkingTheRegistry(DefinitionStatus committed) {
        // Given a row committed with the status under test and a transition that succeeds
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.reads.add(Future.succeededFuture(Optional.of(row(committed))));
        scenario.transitions.add(Future.succeededFuture());

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then the transition ran once with the actual status, and only afterwards was the registry marked once
        assertThat(result.succeeded()).isTrue();
        assertThat(scenario.transitionKeys).containsExactly(KEY);
        assertThat(scenario.events).containsExactly("find", "transition " + committed + "->DEPRECATED", "mark wf 1");
    }

    @Test
    @DisplayName("a lost transition response whose reread shows DEPRECATED still succeeds and marks the registry")
    void acceptsLostResponseWhenRereadShowsDeprecated() {
        // Given a transition that fails after the status was committed
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.ACTIVE))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.DEPRECATED))));
        scenario.transitions.add(Future.failedFuture(new IllegalStateException("response lost")));

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then the committed status is accepted without a second transition, and the registry is marked once
        assertThat(result.succeeded()).isTrue();
        assertThat(scenario.events).containsExactly("find", "transition ACTIVE->DEPRECATED", "find", "mark wf 1");
    }

    @Test
    @DisplayName("a transition refused because the status moved is retried exactly once with the new status")
    void retriesOnceWhenTheStatusMoved() {
        // Given a transition refused as invalid because the row moved from ACTIVE to SUPERSEDED meanwhile
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.ACTIVE))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.SUPERSEDED))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.SUPERSEDED))));
        scenario.transitions.add(Future.failedFuture(refusal(Reason.INVALID_TRANSITION)));
        scenario.transitions.add(Future.succeededFuture());

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then the second attempt used the moved status, succeeded, and the registry was marked once afterwards
        assertThat(result.succeeded()).isTrue();
        assertThat(scenario.events)
                .containsExactly(
                        "find",
                        "transition ACTIVE->DEPRECATED",
                        "find",
                        "find",
                        "transition SUPERSEDED->DEPRECATED",
                        "mark wf 1");
    }

    @Test
    @DisplayName("a second move after the retry is not retried again and fails with the latest refusal")
    void doesNotRetryAfterASecondMove() {
        // Given a row that moves again while the single retry is underway
        DeprecationScenario scenario = new DeprecationScenario();
        WorkflowDefinitionLifecycleException firstRefusal = refusal(Reason.INVALID_TRANSITION);
        WorkflowDefinitionLifecycleException secondRefusal = refusal(Reason.INVALID_TRANSITION);
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.ACTIVE))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.SUPERSEDED))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.SUPERSEDED))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.ACTIVE))));
        scenario.transitions.add(Future.failedFuture(firstRefusal));
        scenario.transitions.add(Future.failedFuture(secondRefusal));
        scenario.transitions.add(Future.succeededFuture());

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then there are exactly two transition attempts, the refusal of the last one is reported, and nothing is
        // marked
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(secondRefusal);
        assertThat(scenario.events)
                .filteredOn(event -> event.startsWith("transition"))
                .hasSize(2);
        assertThat(scenario.events).noneMatch(event -> event.startsWith("mark"));
    }

    @Test
    @DisplayName(
            "a failed transition with a failed reread reports the transition failure with the read failure suppressed")
    void reportsTransitionFailureWithRereadFailureSuppressed() {
        // Given a transition that fails and a reread that fails as well
        DeprecationScenario scenario = new DeprecationScenario();
        IllegalStateException transitionFailure = new IllegalStateException("transition failed");
        IllegalStateException readFailure = new IllegalStateException("reread failed");
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.ACTIVE))));
        scenario.reads.add(Future.failedFuture(readFailure));
        scenario.transitions.add(Future.failedFuture(transitionFailure));

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then the original failure is reported carrying the read failure, and nothing is marked
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(transitionFailure);
        assertThat(result.cause().getSuppressed()).containsExactly(readFailure);
        assertThat(scenario.events).noneMatch(event -> event.startsWith("mark"));
    }

    @Test
    @DisplayName("a JVM error from the transition is passed through without a reread, a retry or a mark")
    void passesThroughJvmErrors() {
        // Given a transition that fails with an Error
        DeprecationScenario scenario = new DeprecationScenario();
        FatalProbe fatal = new FatalProbe();
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.ACTIVE))));
        scenario.transitions.add(Future.failedFuture(fatal));

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then that same error is reported after a single read and a single transition, and nothing is marked
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(fatal);
        assertThat(scenario.events).containsExactly("find", "transition ACTIVE->DEPRECATED");
    }

    @Test
    @DisplayName("an already deprecated row issues no transition but still marks the registry")
    void marksRegistryForAlreadyDeprecatedRow() {
        // Given a row that is already DEPRECATED, for instance after a restart or a lost response
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.DEPRECATED))));

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then no transition ran and the registry was marked once
        assertThat(result.succeeded()).isTrue();
        assertThat(scenario.events).containsExactly("find", "mark wf 1");
    }

    @ParameterizedTest(name = "a {0} row cannot be deprecated")
    @EnumSource(
            value = DefinitionStatus.class,
            names = {"CANDIDATE", "ACTIVATING", "RETIRED"})
    void refusesStatusesThatCannotBeDeprecated(DefinitionStatus committed) {
        // Given a row in a status that deprecation does not accept
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.reads.add(Future.succeededFuture(Optional.of(row(committed))));

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then it fails as an invalid transition naming the key, with no transition and no mark
        assertInvalidTransition(result);
        assertThat(scenario.events).containsExactly("find");
    }

    @Test
    @DisplayName("a missing row cannot be deprecated")
    void refusesMissingRow() {
        // Given no row for the key
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.reads.add(Future.succeededFuture(Optional.empty()));

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then it fails as an invalid transition naming the key, with no transition and no mark
        assertInvalidTransition(result);
        assertThat(scenario.events).containsExactly("find");
    }

    @Test
    @DisplayName("a failure unrelated to the row status is reported without a retry when the row is unchanged")
    void doesNotRetryUnrelatedFailureWhenRowUnchanged() {
        // Given a transition that fails for an unrelated reason while the row stays ACTIVE
        DeprecationScenario scenario = new DeprecationScenario();
        IllegalStateException failure = new IllegalStateException("database unavailable");
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.ACTIVE))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.ACTIVE))));
        scenario.transitions.add(Future.failedFuture(failure));
        scenario.transitions.add(Future.succeededFuture());

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then the failure is reported after one transition and nothing is marked
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(failure);
        assertThat(scenario.events).containsExactly("find", "transition ACTIVE->DEPRECATED", "find");
    }

    @Test
    @DisplayName("a failure that is not an invalid-transition refusal is not retried even if the row moved")
    void doesNotRetryOtherFailuresWhenRowMoved() {
        // Given a plain failure and a row that moved to SUPERSEDED, and a refusal for another reason with the same move
        DeprecationScenario plain = new DeprecationScenario();
        IllegalStateException failure = new IllegalStateException("connection reset");
        plain.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.ACTIVE))));
        plain.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.SUPERSEDED))));
        plain.transitions.add(Future.failedFuture(failure));
        plain.transitions.add(Future.succeededFuture());
        DeprecationScenario otherReason = new DeprecationScenario();
        WorkflowDefinitionLifecycleException ownerConflict = refusal(Reason.OWNER_CONFLICT);
        otherReason.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.ACTIVE))));
        otherReason.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.SUPERSEDED))));
        otherReason.transitions.add(Future.failedFuture(ownerConflict));
        otherReason.transitions.add(Future.succeededFuture());

        // When each definition is deprecated
        Future<Void> plainResult = plain.lifecycle().deprecate(KEY);
        Future<Void> otherResult = otherReason.lifecycle().deprecate(KEY);

        // Then each reports its own failure after a single transition and nothing is marked
        assertThat(plainResult.cause()).isSameAs(failure);
        assertThat(otherResult.cause()).isSameAs(ownerConflict);
        for (DeprecationScenario scenario : List.of(plain, otherReason)) {
            assertThat(scenario.events)
                    .filteredOn(event -> event.startsWith("transition"))
                    .hasSize(1);
            assertThat(scenario.events).noneMatch(event -> event.startsWith("mark"));
        }
    }

    @ParameterizedTest(name = "an invalid-transition refusal is not retried when the row is now {0}")
    @EnumSource(
            value = DefinitionStatus.class,
            names = {"CANDIDATE", "RETIRED"})
    void doesNotRetryWhenRowLeftTheDeprecatableStatuses(DefinitionStatus now) {
        // Given an invalid-transition refusal and a reread that shows a status deprecation does not accept
        DeprecationScenario scenario = new DeprecationScenario();
        WorkflowDefinitionLifecycleException refused = refusal(Reason.INVALID_TRANSITION);
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.ACTIVE))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(now))));
        scenario.transitions.add(Future.failedFuture(refused));
        scenario.transitions.add(Future.succeededFuture());

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then the refusal is reported after one transition and nothing is marked
        assertThat(result.cause()).isSameAs(refused);
        assertThat(scenario.events)
                .filteredOn(event -> event.startsWith("transition"))
                .hasSize(1);
        assertThat(scenario.events).noneMatch(event -> event.startsWith("mark"));
    }

    @Test
    @DisplayName("a null key fails the returned future instead of throwing, and marks nothing")
    void failsFutureForNullKey() {
        // Given a lifecycle with a store
        DeprecationScenario scenario = new DeprecationScenario();

        // When deprecate is called with a null key
        Future<Void> result = scenario.lifecycle().deprecate(null);

        // Then the future fails, the store is untouched and nothing is marked
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isInstanceOf(NullPointerException.class);
        assertThat(scenario.events).isEmpty();
    }

    @Test
    @DisplayName("without a durable store deprecation fails the returned future and marks nothing")
    void failsFutureWithoutStore() {
        // Given a lifecycle without a durable store
        List<String> events = new ArrayList<>();
        WorkflowRegistry registry = recordingRegistry(events);
        DefaultWorkflowDefinitionLifecycle lifecycle = new DefaultWorkflowDefinitionLifecycle(
                Optional.empty(), () -> registry, Set.of(), new DefinitionApplyQueue());

        // When the definition is deprecated
        Future<Void> result = lifecycle.deprecate(KEY);

        // Then the future fails as an invalid transition and nothing is marked
        assertInvalidTransition(result);
        assertThat(events).isEmpty();
    }

    private static void assertInvalidTransition(Future<Void> result) {
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
            assertThat(failure.key()).isEqualTo(KEY);
        });
    }

    private static WorkflowDefinitionLifecycleException refusal(Reason reason) {
        return new WorkflowDefinitionLifecycleException(reason, KEY, "refused " + reason);
    }

    private static WorkflowDefinitionRecord row(DefinitionStatus status) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new WorkflowDefinitionRecord(
                KEY,
                DefinitionKind.DOCUMENT,
                PLAN_HASH,
                status,
                new DefinitionProvenance("test", null, null, null, null, now),
                "YAML",
                now,
                now);
    }

    /** A registry that logs every deprecation mark into the shared event log. */
    private static WorkflowRegistry recordingRegistry(List<String> events) {
        WorkflowRegistry registry = Mockito.mock(WorkflowRegistry.class);
        Mockito.doAnswer(invocation -> {
                    events.add("mark " + invocation.getArgument(0) + " " + invocation.getArgument(1));
                    return null;
                })
                .when(registry)
                .markDeprecated(Mockito.anyString(), Mockito.anyLong());
        return registry;
    }

    /** A JVM-level failure that must never be retried or swallowed. */
    private static final class FatalProbe extends Error {}

    /** Scripts the reads and transitions of one deprecation and logs them with the registry marks in order. */
    private static final class DeprecationScenario {
        final List<String> events = new ArrayList<>();
        final Deque<Future<Optional<WorkflowDefinitionRecord>>> reads = new ArrayDeque<>();
        final Deque<Future<Void>> transitions = new ArrayDeque<>();
        final List<DefinitionKey> transitionKeys = new ArrayList<>();
        private final WorkflowRegistry registry = recordingRegistry(events);

        DefaultWorkflowDefinitionLifecycle lifecycle() {
            return new DefaultWorkflowDefinitionLifecycle(
                    Optional.of(new ScriptedStore(this)), () -> registry, Set.of(), new DefinitionApplyQueue());
        }
    }

    /** Serves the scripted reads and transitions of a {@link DeprecationScenario}; every other operation is unsupported. */
    private static final class ScriptedStore implements WorkflowDefinitionRegistryStore {
        private final DeprecationScenario scenario;

        ScriptedStore(DeprecationScenario scenario) {
            this.scenario = scenario;
        }

        @Override
        public Future<Optional<WorkflowDefinitionRecord>> find(DefinitionKey key) {
            scenario.events.add("find");
            Future<Optional<WorkflowDefinitionRecord>> next = scenario.reads.poll();
            return next != null ? next : Future.failedFuture(new IllegalStateException("unscripted read"));
        }

        @Override
        public Future<Void> transition(DefinitionKey key, DefinitionStatus from, DefinitionStatus to) {
            scenario.events.add("transition " + from + "->" + to);
            scenario.transitionKeys.add(key);
            Future<Void> next = scenario.transitions.poll();
            return next != null ? next : Future.failedFuture(new IllegalStateException("unscripted transition"));
        }

        @Override
        public Future<ActivationClaim> claim(DefinitionKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Optional<WorkflowDefinitionDocument>> findDocument(DefinitionKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<ActivationOutcome> complete(ActivationClaim claim) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> release(ActivationClaim claim) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> insertCandidate(WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<List<WorkflowDefinitionRecord>> list(DefinitionFilter filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<ReconcileOutcome> upsertDocument(
                WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> recomputeActive(String tenantKey, String definitionId) {
            throw new UnsupportedOperationException();
        }
    }

    private static DefaultWorkflowDefinitionLifecycle lifecycle(
            FakeStore store, Set<PersistedDefinitionLoader> loaders, WorkflowRegistry registry) {
        return new DefaultWorkflowDefinitionLifecycle(
                Optional.of(store), () -> registry, loaders, new DefinitionApplyQueue());
    }

    private static PersistedDefinitionLoader publishingLoader() {
        WorkflowDefinition<?, ?> definition = Mockito.mock(WorkflowDefinition.class);
        Mockito.when(definition.definitionId()).thenReturn("wf");
        Mockito.when(definition.definitionVersion()).thenReturn(1L);
        PersistedDefinitionLoader loader = Mockito.mock(PersistedDefinitionLoader.class);
        Mockito.when(loader.supports("YAML")).thenReturn(true);
        Mockito.doReturn(definition).when(loader).load(Mockito.any(), Mockito.any());
        Mockito.when(loader.planHash(definition)).thenReturn(PLAN_HASH);
        return loader;
    }

    /** Serves the candidate row for the first two reads, fails every later read, counts releases. */
    private static final class FakeStore implements WorkflowDefinitionRegistryStore {
        final AtomicInteger releases = new AtomicInteger();
        private final AtomicInteger finds = new AtomicInteger();
        Future<ActivationOutcome> completion = Future.succeededFuture(ActivationOutcome.ACTIVE);

        private static WorkflowDefinitionRecord candidate() {
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            return new WorkflowDefinitionRecord(
                    KEY,
                    DefinitionKind.DOCUMENT,
                    PLAN_HASH,
                    DefinitionStatus.CANDIDATE,
                    new DefinitionProvenance("test", null, null, null, null, now),
                    "YAML",
                    now,
                    now);
        }

        @Override
        public Future<Optional<WorkflowDefinitionRecord>> find(DefinitionKey key) {
            if (finds.incrementAndGet() > 2) return Future.failedFuture(new IllegalStateException("reread failed"));
            return Future.succeededFuture(Optional.of(candidate()));
        }

        @Override
        public Future<ActivationClaim> claim(DefinitionKey key) {
            return Future.succeededFuture(new ActivationClaim(key, UUID.randomUUID()));
        }

        @Override
        public Future<Optional<WorkflowDefinitionDocument>> findDocument(DefinitionKey key) {
            return Future.succeededFuture(Optional.of(new WorkflowDefinitionDocument(key, "{}", "YAML")));
        }

        @Override
        public Future<ActivationOutcome> complete(ActivationClaim claim) {
            return completion;
        }

        @Override
        public Future<Void> release(ActivationClaim claim) {
            releases.incrementAndGet();
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> insertCandidate(WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<List<WorkflowDefinitionRecord>> list(DefinitionFilter filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<ReconcileOutcome> upsertDocument(
                WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> recomputeActive(String tenantKey, String definitionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> transition(DefinitionKey key, DefinitionStatus from, DefinitionStatus to) {
            throw new UnsupportedOperationException();
        }
    }
}
