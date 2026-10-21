// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.RegisteredDefinitionClass;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.ActivationOutcome;
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
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 * Verifies the claim release of {@link DefaultWorkflowDefinitionLifecycle} when a durable activation
 * fails and the follow-up read of the definition row fails as well.
 */
class DefaultWorkflowDefinitionLifecycleTest {

    private static final DefinitionKey KEY = new DefinitionKey(WorkflowScopes.DEFAULT, "wf", 1);
    private static final String PLAN_HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    @Test
    @DisplayName("a failed reread after an activation that published nothing still releases the claim once")
    void releasesClaimWhenNothingWasPublishedAndRereadFails() {
        // Given a CANDIDATE document row, a claim that succeeds, and no loader bound
        FakeStore store = new FakeStore();
        DefaultWorkflowDefinitionLifecycle lifecycle = lifecycle(store, Set.of(), Mockito.mock(WorkflowRegistry.class));

        // When the activation fails with LOADER_MISSING and the reread of the row fails
        Future<Void> result = lifecycle.publish(KEY);

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
        Mockito.when(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "wf", 1)))
                .thenReturn(Mockito.mock(RuntimeWorkflow.class));
        DefaultWorkflowDefinitionLifecycle lifecycle = lifecycle(store, Set.of(publishingLoader()), registry);

        // When the reread of the row fails after the publication
        Future<Void> result = lifecycle.publish(KEY);

        // Then the activation failed and the claim stays owned by reconciliation
        assertThat(result.failed()).isTrue();
        assertThat(store.releases.get()).isZero();
    }

    @ParameterizedTest(name = "a {0} row is deprecated with that status and then marked locally")
    @EnumSource(
            value = DefinitionStatus.class,
            names = {"AVAILABLE"})
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
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.DEPRECATED))));
        scenario.transitions.add(Future.failedFuture(new IllegalStateException("response lost")));

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then the committed status is accepted without a second transition, and the registry is marked once
        assertThat(result.succeeded()).isTrue();
        assertThat(scenario.events).containsExactly("find", "transition AVAILABLE->DEPRECATED", "find", "mark wf 1");
    }

    @Test
    @DisplayName("a transition refused while the row reads as available again is retried exactly once")
    void retriesOnceWhenTheStatusMoved() {
        // Given a transition refused as invalid because the row changed meanwhile and reads as available again
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        scenario.transitions.add(Future.failedFuture(refusal(Reason.INVALID_TRANSITION)));
        scenario.transitions.add(Future.succeededFuture());

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then the second attempt succeeded, and the registry was marked once afterwards
        assertThat(result.succeeded()).isTrue();
        assertThat(scenario.events)
                .containsExactly(
                        "find",
                        "transition AVAILABLE->DEPRECATED",
                        "find",
                        "find",
                        "transition AVAILABLE->DEPRECATED",
                        "mark wf 1");
    }

    @Test
    @DisplayName("a second move after the retry is not retried again and fails with the latest refusal")
    void doesNotRetryAfterASecondMove() {
        // Given a row that moves again while the single retry is underway
        DeprecationScenario scenario = new DeprecationScenario();
        WorkflowDefinitionLifecycleException firstRefusal = refusal(Reason.INVALID_TRANSITION);
        WorkflowDefinitionLifecycleException secondRefusal = refusal(Reason.INVALID_TRANSITION);
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
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
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
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
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        scenario.transitions.add(Future.failedFuture(fatal));

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then that same error is reported after a single read and a single transition, and nothing is marked
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(fatal);
        assertThat(scenario.events).containsExactly("find", "transition AVAILABLE->DEPRECATED");
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
            names = {"CANDIDATE", "PUBLISHING", "RETIRED"})
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
        // Given a transition that fails for an unrelated reason while the row stays AVAILABLE
        DeprecationScenario scenario = new DeprecationScenario();
        IllegalStateException failure = new IllegalStateException("database unavailable");
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        scenario.transitions.add(Future.failedFuture(failure));
        scenario.transitions.add(Future.succeededFuture());

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then the failure is reported after one transition and nothing is marked
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(failure);
        assertThat(scenario.events).containsExactly("find", "transition AVAILABLE->DEPRECATED", "find");
    }

    @Test
    @DisplayName("a failure that is not an invalid-transition refusal is not retried even if the row is available")
    void doesNotRetryOtherFailuresWhenRowMoved() {
        // Given a plain failure and a row that is still available, and a refusal for another reason
        DeprecationScenario plain = new DeprecationScenario();
        IllegalStateException failure = new IllegalStateException("connection reset");
        plain.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        plain.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        plain.transitions.add(Future.failedFuture(failure));
        plain.transitions.add(Future.succeededFuture());
        DeprecationScenario otherReason = new DeprecationScenario();
        WorkflowDefinitionLifecycleException staleClaim = refusal(Reason.STALE_CLAIM);
        otherReason.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        otherReason.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        otherReason.transitions.add(Future.failedFuture(staleClaim));
        otherReason.transitions.add(Future.succeededFuture());

        // When each definition is deprecated
        Future<Void> plainResult = plain.lifecycle().deprecate(KEY);
        Future<Void> otherResult = otherReason.lifecycle().deprecate(KEY);

        // Then each reports its own failure after a single transition and nothing is marked
        assertThat(plainResult.cause()).isSameAs(failure);
        assertThat(otherResult.cause()).isSameAs(staleClaim);
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
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
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
    @DisplayName("deprecating over a runtime the lifecycle did not publish is refused before any store call")
    void deprecateRefusesUnattributedRuntimeBeforeTheStore() {
        // Given a runtime registered for the key that no attribution names, as after a direct registry call
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.registryHolds(Mockito.mock(RuntimeWorkflow.class));

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle(new ManagedRegistrations()).deprecate(KEY);

        // Then it fails as an invalid transition naming the key, and neither the store nor the registry is touched
        assertInvalidTransition(result);
        assertThat(scenario.events).isEmpty();
    }

    @Test
    @DisplayName("deprecating over a runtime published for another scope's key is refused before any store call")
    void deprecateRefusesRuntimePublishedForAnotherScopeBeforeTheStore() {
        // Given a runtime attributed to the same id and version in another scope
        DeprecationScenario scenario = new DeprecationScenario();
        RuntimeWorkflow otherScopes = Mockito.mock(RuntimeWorkflow.class);
        scenario.registryHolds(otherScopes);
        ManagedRegistrations registrations = new ManagedRegistrations();
        registrations.attribute("tenant-other", "wf", 1, otherScopes);

        // When this scope's row is deprecated
        Future<Void> result = scenario.lifecycle(registrations).deprecate(KEY);

        // Then the registration is not this key's own, so the call is refused and neither the store nor the registry
        // is touched
        assertInvalidTransition(result);
        assertThat(scenario.events).isEmpty();
    }

    @Test
    @DisplayName("deprecating marks the runtime the lifecycle published for this key")
    void deprecateMarksOwnPublishedRuntime() {
        // Given a runtime attributed to this owner
        DeprecationScenario scenario = new DeprecationScenario();
        RuntimeWorkflow own = Mockito.mock(RuntimeWorkflow.class);
        scenario.registryHolds(own);
        ManagedRegistrations registrations = new ManagedRegistrations();
        registrations.attribute(WorkflowScopes.DEFAULT, "wf", 1, own);
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        scenario.transitions.add(Future.succeededFuture());

        // When the row is deprecated
        Future<Void> result = scenario.lifecycle(registrations).deprecate(KEY);

        // Then the transition ran and the registry was marked afterwards
        assertThat(result.succeeded()).isTrue();
        assertThat(scenario.events).containsExactly("find", "transition AVAILABLE->DEPRECATED", "mark wf 1");
    }

    @Test
    @DisplayName("retiring over a runtime the lifecycle did not publish is refused before the store retires")
    void retireRefusesUnattributedRuntimeBeforeTheStore() {
        // Given a runtime registered for the key that no attribution names
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.registryHolds(Mockito.mock(RuntimeWorkflow.class));
        scenario.retirements.add(Future.succeededFuture());

        // When the definition is retired
        Future<Void> result = scenario.lifecycle(new ManagedRegistrations()).retire(KEY);

        // Then it fails as an invalid transition naming the key, and the store and the registry are untouched
        assertInvalidTransition(result);
        assertThat(scenario.events).isEmpty();
    }

    @Test
    @DisplayName("retiring over a runtime published for another scope's key is refused before the store retires")
    void retireRefusesRuntimePublishedForAnotherScopeBeforeTheStore() {
        // Given a runtime attributed to the same id and version in another scope
        DeprecationScenario scenario = new DeprecationScenario();
        RuntimeWorkflow otherScopes = Mockito.mock(RuntimeWorkflow.class);
        scenario.registryHolds(otherScopes);
        ManagedRegistrations registrations = new ManagedRegistrations();
        registrations.attribute("tenant-other", "wf", 1, otherScopes);
        scenario.retirements.add(Future.succeededFuture());

        // When this scope's row is retired
        Future<Void> result = scenario.lifecycle(registrations).retire(KEY);

        // Then the registration is not this key's own, so the call is refused and nothing is touched
        assertInvalidTransition(result);
        assertThat(scenario.events).isEmpty();
    }

    @Test
    @DisplayName("retiring removes the runtime the lifecycle published for this key")
    void retireRemovesOwnPublishedRuntime() {
        // Given a runtime attributed to this owner
        DeprecationScenario scenario = new DeprecationScenario();
        RuntimeWorkflow own = Mockito.mock(RuntimeWorkflow.class);
        scenario.registryHolds(own);
        ManagedRegistrations registrations = new ManagedRegistrations();
        registrations.attribute(WorkflowScopes.DEFAULT, "wf", 1, own);
        scenario.retirements.add(Future.succeededFuture());

        // When the row is retired
        Future<Void> result = scenario.lifecycle(registrations).retire(KEY);

        // Then the store retired the row, and only afterwards was the runtime unregistered
        assertThat(result.succeeded()).isTrue();
        assertThat(scenario.events).containsExactly("retire", "unregister wf 1");
    }

    @Test
    @DisplayName("a retirement whose response was lost but whose row is retired still removes the runtime")
    void retireWithLostResponseRemovesOwnRuntimeWhenTheRowIsRetired() {
        // Given a runtime attributed to this owner and a retirement that fails after it committed
        DeprecationScenario scenario = new DeprecationScenario();
        RuntimeWorkflow own = Mockito.mock(RuntimeWorkflow.class);
        scenario.registryHolds(own);
        ManagedRegistrations registrations = new ManagedRegistrations();
        registrations.attribute(WorkflowScopes.DEFAULT, "wf", 1, own);
        scenario.retirements.add(Future.failedFuture(new IllegalStateException("commit outcome unknown")));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.RETIRED))));

        // When the row is retired
        Future<Void> result = scenario.lifecycle(registrations).retire(KEY);

        // Then the read-back found the retirement committed, so the runtime was unregistered and the call succeeded
        assertThat(result.succeeded()).isTrue();
        assertThat(scenario.events).containsExactly("retire", "find", "unregister wf 1");
    }

    @Test
    @DisplayName("a failed retirement whose row is not retired keeps the runtime and reports the original failure")
    void retireThatDidNotCommitKeepsTheRuntimeAndFailsWithTheOriginalError() {
        // Given a runtime attributed to this owner and a retirement that failed without committing
        DeprecationScenario scenario = new DeprecationScenario();
        RuntimeWorkflow own = Mockito.mock(RuntimeWorkflow.class);
        scenario.registryHolds(own);
        ManagedRegistrations registrations = new ManagedRegistrations();
        registrations.attribute(WorkflowScopes.DEFAULT, "wf", 1, own);
        IllegalStateException original = new IllegalStateException("pins exist");
        scenario.retirements.add(Future.failedFuture(original));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.DEPRECATED))));

        // When the row is retired
        Future<Void> result = scenario.lifecycle(registrations).retire(KEY);

        // Then the original failure is reported and nothing was unregistered
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(original);
        assertThat(scenario.events).containsExactly("retire", "find");
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
                "example.Contract",
                "example.State",
                PLAN_HASH,
                status,
                new DefinitionProvenance("test", null, null, null, null, now),
                "YAML",
                now,
                now);
    }

    private static WorkflowDefinitionRecord row(DefinitionStatus status, String planHash) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new WorkflowDefinitionRecord(
                KEY,
                DefinitionKind.DOCUMENT,
                "example.Contract",
                "example.State",
                planHash,
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
                    DefinitionKey key = invocation.getArgument(0);
                    events.add("mark " + key.definitionId() + " " + key.definitionVersion());
                    return null;
                })
                .when(registry)
                .markDeprecated(Mockito.any(DefinitionKey.class));
        Mockito.doAnswer(invocation -> {
                    DefinitionKey key = invocation.getArgument(0);
                    events.add("unmark " + key.definitionId() + " " + key.definitionVersion());
                    return null;
                })
                .when(registry)
                .unmarkDeprecated(Mockito.any(DefinitionKey.class));
        Mockito.doAnswer(invocation -> {
                    DefinitionKey key = invocation.getArgument(0);
                    events.add("unregister " + key.definitionId() + " " + key.definitionVersion());
                    return null;
                })
                .when(registry)
                .unregister(Mockito.any(DefinitionKey.class));
        return registry;
    }

    /** A JVM-level failure that must never be retried or swallowed. */
    private static final class FatalProbe extends Error {}

    /** Scripts the reads and transitions of one deprecation and logs them with the registry marks in order. */
    private static final class DeprecationScenario {
        final List<String> events = new ArrayList<>();
        final Deque<Future<Optional<WorkflowDefinitionRecord>>> reads = new ArrayDeque<>();
        final Deque<Future<Void>> transitions = new ArrayDeque<>();
        final Deque<Future<Void>> retirements = new ArrayDeque<>();
        final List<DefinitionKey> transitionKeys = new ArrayList<>();
        private final WorkflowRegistry registry = recordingRegistry(events);

        DefaultWorkflowDefinitionLifecycle lifecycle() {
            return new DefaultWorkflowDefinitionLifecycle(
                    Optional.of(new ScriptedStore(this)), () -> registry, Set.of(), new DefinitionApplyQueue());
        }

        /** Builds the lifecycle over the given attributions, so a test decides who published the registration. */
        DefaultWorkflowDefinitionLifecycle lifecycle(ManagedRegistrations registrations) {
            return new DefaultWorkflowDefinitionLifecycle(
                    Optional.of(new ScriptedStore(this)),
                    () -> registry,
                    Set.of(),
                    new DefinitionApplyQueue(),
                    registrations);
        }

        /** Makes the registry hold the given runtime for the test key, as a published or direct registration. */
        void registryHolds(RuntimeWorkflow runtime) {
            Mockito.when(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "wf", 1)))
                    .thenReturn(runtime);
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
        public Future<ReconcileOutcome> upsertCode(WorkflowDefinitionRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> resetStaleActivating(String definitionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<ReconcileOutcome> upsertDocument(
                WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> recomputeActive(String scopeKey, String definitionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> retire(DefinitionKey key) {
            scenario.events.add("retire");
            Future<Void> next = scenario.retirements.poll();
            return next != null ? next : Future.failedFuture(new IllegalStateException("unscripted retirement"));
        }
    }

    @Test
    @DisplayName("a retried activation adopts the runtime its unresolved earlier attempt published")
    void retryAfterUnresolvedCompletionAdoptsItsOwnPublication() {
        // Given a first activation that publishes locally, then fails to complete with an unknown outcome
        // while the follow-up reread fails as well
        ActivationStore store = new ActivationStore();
        store.completions.add(Future.failedFuture(new IllegalStateException("commit outcome unknown")));
        store.failReadsAfterFirstCompletion = true;
        store.completions.add(Future.succeededFuture(ActivationOutcome.ACTIVE));
        RecordingRegistry registry = new RecordingRegistry();
        DefaultWorkflowDefinitionLifecycle lifecycle = new DefaultWorkflowDefinitionLifecycle(
                Optional.of(store), () -> registry, Set.of(publishingLoader()), new DefinitionApplyQueue());

        Future<Void> first = lifecycle.publish(KEY);

        assertThat(first.failed()).isTrue();
        RuntimeWorkflow published = registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "wf", 1));
        assertThat(registry.allRegistered()).containsExactly(published);
        assertThat(registry.unregistered).isEmpty();

        // When the same key is activated again after the claim became claimable again and reads work
        store.readsFail = false;
        Future<Void> second = lifecycle.publish(KEY);

        // Then the activation succeeds, the registry still holds exactly the first runtime, and it was never removed
        assertThat(second.succeeded())
                .as("second activation: %s", second.cause())
                .isTrue();
        assertThat(store.completes).isEqualTo(2);
        assertThat(registry.allRegistered()).hasSize(1);
        assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "wf", 1)))
                .isSameAs(published);
        assertThat(registry.unregistered).isEmpty();
    }

    @Test
    @DisplayName("a registration made by someone else is never adopted and survives the failed activation")
    void doesNotAdoptForeignRegistration() {
        // Given a runtime for the key registered directly in the registry, and a lifecycle that never published it
        ActivationStore store = new ActivationStore();
        store.completions.add(Future.succeededFuture(ActivationOutcome.ACTIVE));
        RecordingRegistry registry = new RecordingRegistry();
        WorkflowDefinition<?, ?> foreignDefinition = Mockito.mock(WorkflowDefinition.class);
        Mockito.when(foreignDefinition.definitionId()).thenReturn("wf");
        Mockito.when(foreignDefinition.definitionVersion()).thenReturn(1L);
        registry.register(foreignDefinition);
        RuntimeWorkflow foreign = registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "wf", 1));
        DefaultWorkflowDefinitionLifecycle lifecycle = new DefaultWorkflowDefinitionLifecycle(
                Optional.of(store), () -> registry, Set.of(publishingLoader()), new DefinitionApplyQueue());

        // When the key is activated
        Future<Void> result = lifecycle.publish(KEY);

        // Then the activation fails on the existing registration, which stays registered, and the claim is released
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isInstanceOf(WorkflowDefinitionException.class);
        assertThat(registry.allRegistered()).containsExactly(foreign);
        assertThat(registry.unregistered).isEmpty();
        assertThat(store.completes).isZero();
        assertThat(store.releases).isEqualTo(1);
    }

    @Test
    @DisplayName("activating a key whose earlier activation succeeded changes nothing")
    void secondActivationOfActiveKeyIsANoOp() {
        // Given a key that was activated successfully
        ActivationStore store = new ActivationStore();
        store.completions.add(Future.succeededFuture(ActivationOutcome.ACTIVE));
        RecordingRegistry registry = new RecordingRegistry();
        DefaultWorkflowDefinitionLifecycle lifecycle = new DefaultWorkflowDefinitionLifecycle(
                Optional.of(store), () -> registry, Set.of(publishingLoader()), new DefinitionApplyQueue());
        assertThat(lifecycle.publish(KEY).succeeded()).isTrue();
        RuntimeWorkflow published = registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "wf", 1));

        // When it is activated again
        Future<Void> again = lifecycle.publish(KEY);

        // Then it succeeds without another claim or completion, and the registration is unchanged
        assertThat(again.succeeded()).isTrue();
        assertThat(store.claims).isEqualTo(1);
        assertThat(store.completes).isEqualTo(1);
        assertThat(registry.allRegistered()).containsExactly(published);
        assertThat(registry.unregistered).isEmpty();
    }

    @ParameterizedTest(name = "a retry that finds the earlier change committed as {0} completes and hands the row over")
    @EnumSource(
            value = DefinitionStatus.class,
            names = {"AVAILABLE"})
    void retryAfterAnUnknownCommitOutcomeAdoptsTheCommittedRow(DefinitionStatus committed) {
        // Given a reinstatement whose change failed and whose follow-up read failed as well
        ReinstateStore store = new ReinstateStore();
        store.transitions.add(() -> {
            store.readsFail = true;
            return Future.failedFuture(new IllegalStateException("response lost"));
        });
        ReinstateWorld world = new ReinstateWorld(store);
        Future<Void> first = world.lifecycle.reinstate(KEY);
        assertThat(first.failed()).isTrue();
        RuntimeWorkflow published = world.registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "wf", 1));
        assertThat(world.registrations.classify(WorkflowScopes.DEFAULT, "wf", 1, published))
                .isEqualTo(ManagedRegistrations.Standing.OWN);
        assertThat(world.registry.unregistered).isEmpty();

        // And the row turns out to have been committed, with the hash of the published runtime
        store.readsFail = false;
        store.status = committed;

        // When the reinstatement is retried
        Future<Void> retry = world.lifecycle.reinstate(KEY);

        // Then it succeeds without refusing the committed row, and the published runtime is kept
        assertThat(retry.succeeded()).as("retry: %s", retry.cause()).isTrue();
        assertThat(world.registry.allRegistered()).containsExactly(published);
        assertThat(world.registry.unregistered).isEmpty();
        assertThat(world.registrations.classify(WorkflowScopes.DEFAULT, "wf", 1, published))
                .isEqualTo(ManagedRegistrations.Standing.OWN);

        // And the committed row is handed to the loader exactly once
        assertThat(world.loader.handOffs)
                .extracting(WorkflowDefinitionRecord::status)
                .containsExactly(committed);
    }

    @Test
    @DisplayName("a retry that the store refuses definitively removes the publication the unknown outcome left behind")
    void retryRefusedByTheStoreRemovesTheLeftoverPublication() {
        // Given a reinstatement whose change failed and whose follow-up read failed, so its publication stays
        ReinstateStore store = new ReinstateStore();
        store.transitions.add(() -> {
            store.readsFail = true;
            return Future.failedFuture(new IllegalStateException("response lost"));
        });
        ReinstateWorld world = new ReinstateWorld(store);
        assertThat(world.lifecycle.reinstate(KEY).failed()).isTrue();
        RuntimeWorkflow published = world.registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "wf", 1));
        assertThat(world.registry.allRegistered()).containsExactly(published);

        // And the row is still retired, and the store now refuses the change for good
        store.readsFail = false;
        WorkflowDefinitionLifecycleException conflict = refusal(Reason.INVALID_TRANSITION);
        store.transitions.add(() -> Future.failedFuture(conflict));

        // When the reinstatement is retried
        Future<Void> retry = world.lifecycle.reinstate(KEY);

        // Then the refusal is reported and no runtime of the id remains registered or attributed
        assertThat(retry.failed()).isTrue();
        assertThat(retry.cause()).isSameAs(conflict);
        assertThat(world.registry.allRegistered()).isEmpty();
        assertThat(world.registry.unregistered).contains("wf@1");
        assertThat(world.registrations.classify(WorkflowScopes.DEFAULT, "wf", 1, published))
                .isEqualTo(ManagedRegistrations.Standing.UNATTRIBUTED);
        assertThat(world.loader.handOffs).isEmpty();
    }

    @Test
    @DisplayName("a retry refused before any change removes the publication the unknown outcome left behind")
    void retryRefusedBeforeAnyChangeRemovesTheLeftoverPublication() {
        // Given a reinstatement whose change failed and whose follow-up read failed, so its publication stays
        ReinstateStore store = new ReinstateStore();
        store.transitions.add(() -> {
            store.readsFail = true;
            return Future.failedFuture(new IllegalStateException("response lost"));
        });
        ReinstateWorld world = new ReinstateWorld(store);
        assertThat(world.lifecycle.reinstate(KEY).failed()).isTrue();
        RuntimeWorkflow published = world.registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "wf", 1));
        assertThat(world.registry.allRegistered()).containsExactly(published);
        store.events.clear();

        // And the row is still retired but now carries a different plan hash, so the retry is refused on drift
        store.readsFail = false;
        store.planHash = OTHER_HASH;

        // When the reinstatement is retried
        Future<Void> retry = world.lifecycle.reinstate(KEY);

        // Then the refusal is reported before any change was attempted, and the leftover runtime is gone
        assertThat(retry.failed()).isTrue();
        assertThat(retry.cause()).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
            assertThat(failure.key()).isEqualTo(KEY);
        });
        assertThat(store.events).containsExactly("find");
        assertThat(world.registry.allRegistered()).isEmpty();
        assertThat(world.registry.unregistered).contains("wf@1");
        assertThat(world.registrations.classify(WorkflowScopes.DEFAULT, "wf", 1, published))
                .isEqualTo(ManagedRegistrations.Standing.UNATTRIBUTED);
        assertThat(world.loader.handOffs).isEmpty();
    }

    static Stream<Arguments> rereadsThatAreNotTheCommit() {
        return Stream.of(
                Arguments.of(DefinitionStatus.DEPRECATED, PLAN_HASH),
                Arguments.of(DefinitionStatus.AVAILABLE, OTHER_HASH));
    }

    @ParameterizedTest(name = "a failed change whose reread shows {0} with hash {1} is not a commit")
    @MethodSource("rereadsThatAreNotTheCommit")
    void aRereadThatIsNotTheCommitRemovesThePublication(DefinitionStatus reread, String hash) {
        // Given a change that fails, after which the row reads as something other than this reinstatement's commit
        ReinstateStore store = new ReinstateStore();
        IllegalStateException failure = new IllegalStateException("change failed");
        store.transitions.add(() -> {
            store.status = reread;
            store.planHash = hash;
            return Future.failedFuture(failure);
        });
        ReinstateWorld world = new ReinstateWorld(store);

        // When the reinstatement runs
        Future<Void> result = world.lifecycle.reinstate(KEY);

        // Then the failure is reported, the publication is removed again, and nothing is handed to the loader
        assertThat(result.failed())
                .as("a row that is not this reinstatement's commit must not count as committed")
                .isTrue();
        assertThat(result.cause()).isSameAs(failure);
        assertThat(world.registry.allRegistered()).isEmpty();
        assertThat(world.registry.unregistered).contains("wf@1");
        assertThat(world.loader.handOffs).isEmpty();
    }

    @ParameterizedTest(name = "a failed change whose reread shows {0} with the published hash is a commit")
    @EnumSource(
            value = DefinitionStatus.class,
            names = {"AVAILABLE"})
    void aRereadWithThePublishedHashIsTheCommit(DefinitionStatus reread) {
        // Given a change whose response is lost after the row was committed with the published runtime's hash
        ReinstateStore store = new ReinstateStore();
        store.transitions.add(() -> {
            store.status = reread;
            return Future.failedFuture(new IllegalStateException("response lost"));
        });
        ReinstateWorld world = new ReinstateWorld(store);

        // When the reinstatement runs
        Future<Void> result = world.lifecycle.reinstate(KEY);

        // Then it succeeds, the publication stays, and the committed row is handed to the loader
        assertThat(result.succeeded()).as("result: %s", result.cause()).isTrue();
        assertThat(world.registry.allRegistered()).hasSize(1);
        assertThat(world.registry.unregistered).isEmpty();
        assertThat(world.loader.handOffs)
                .extracting(WorkflowDefinitionRecord::status)
                .containsExactly(reread);
    }

    @Test
    @DisplayName("a change that fails definitively after a document was published removes just that publication")
    void aDefinitiveFailureAfterPublishingADocumentCompensates() {
        // Given a document whose change is refused for good after its source was published
        ReinstateStore store = new ReinstateStore();
        WorkflowDefinitionLifecycleException conflict = refusal(Reason.INVALID_TRANSITION);
        store.transitions.add(() -> Future.failedFuture(conflict));
        ReinstateWorld world = new ReinstateWorld(store);

        // When the reinstatement runs
        Future<Void> result = world.lifecycle.reinstate(KEY);

        // Then the refusal is reported, the runtime is unregistered and its attribution is gone
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(conflict);
        assertThat(world.registry.history).hasSize(1);
        assertThat(world.registry.allRegistered()).isEmpty();
        assertThat(world.registry.unregistered).containsExactly("wf@1");
        assertThat(world.registrations.classify(WorkflowScopes.DEFAULT, "wf", 1, world.registry.history.getFirst()))
                .isEqualTo(ManagedRegistrations.Standing.UNATTRIBUTED);
        assertThat(world.loader.handOffs).isEmpty();
    }

    @Test
    @DisplayName("a reinstated document hands the committed row to the loader after the change")
    void reinstatingADocumentHandsTheCommittedRowToTheLoader() {
        // Given a retired document and a change that succeeds
        ReinstateStore store = new ReinstateStore();
        store.transitions.add(() -> {
            store.status = DefinitionStatus.AVAILABLE;
            return Future.succeededFuture();
        });
        ReinstateWorld world = new ReinstateWorld(store);

        // When it is reinstated
        Future<Void> result = world.lifecycle.reinstate(KEY);

        // Then the source was published before the change, and the committed row was handed over once
        assertThat(result.succeeded()).as("result: %s", result.cause()).isTrue();
        assertThat(store.events).containsExactly("find", "findDocument", "transition RETIRED->AVAILABLE", "find");
        assertThat(world.registry.allRegistered()).hasSize(1);
        assertThat(world.loader.handOffs)
                .extracting(WorkflowDefinitionRecord::status)
                .containsExactly(DefinitionStatus.AVAILABLE);
    }

    @Test
    @DisplayName("a registration that the lifecycle did not publish for this key refuses the reinstatement")
    void reinstatementRefusesARegistrationThatIsNotThisKeys() {
        // Given a retired row and a runtime registered for its id and version that is not this key's publication
        ReinstateStore store = new ReinstateStore();
        ReinstateWorld world = new ReinstateWorld(store);
        WorkflowDefinition<?, ?> foreign = Mockito.mock(WorkflowDefinition.class);
        Mockito.when(foreign.definitionId()).thenReturn("wf");
        Mockito.when(foreign.definitionVersion()).thenReturn(1L);
        world.registry.register(foreign);
        RuntimeWorkflow registered = world.registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "wf", 1));

        // When the reinstatement runs
        Future<Void> result = world.lifecycle.reinstate(KEY);

        // Then it fails as an invalid transition, before any source is loaded or any change is made
        assertInvalidTransition(result);
        assertThat(store.events).containsExactly("find");
        assertThat(world.registry.history).containsExactly(registered);
        assertThat(world.registry.allRegistered()).containsExactly(registered);
        assertThat(world.registry.unregistered).isEmpty();
    }

    @Test
    @DisplayName("reinstating a deprecated row commits it as available and then clears the registry mark")
    void reinstatingADeprecatedRowCommitsBeforeClearingTheMark() {
        // Given a deprecated row whose registration this lifecycle published for the key
        DeprecationScenario scenario = new DeprecationScenario();
        RuntimeWorkflow own = Mockito.mock(RuntimeWorkflow.class);
        scenario.registryHolds(own);
        ManagedRegistrations registrations = new ManagedRegistrations();
        registrations.attribute(WorkflowScopes.DEFAULT, "wf", 1, own);
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.DEPRECATED))));
        scenario.transitions.add(Future.succeededFuture());

        // When the row is reinstated
        Future<Void> result = scenario.lifecycle(registrations).reinstate(KEY);

        // Then the durable change ran once from deprecated to available, and only afterwards the mark was cleared;
        // the source was not published again
        assertThat(result.succeeded()).as("result: %s", result.cause()).isTrue();
        assertThat(scenario.events).containsExactly("find", "transition DEPRECATED->AVAILABLE", "unmark wf 1");
    }

    @Test
    @DisplayName("a lost response whose reread shows the deprecated row available still clears the registry mark")
    void reinstatingADeprecatedRowAcceptsALostResponseThatCommitted() {
        // Given a transition that fails after the row was committed as available
        DeprecationScenario scenario = new DeprecationScenario();
        RuntimeWorkflow own = Mockito.mock(RuntimeWorkflow.class);
        scenario.registryHolds(own);
        ManagedRegistrations registrations = new ManagedRegistrations();
        registrations.attribute(WorkflowScopes.DEFAULT, "wf", 1, own);
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.DEPRECATED))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        scenario.transitions.add(Future.failedFuture(new IllegalStateException("response lost")));

        // When the row is reinstated
        Future<Void> result = scenario.lifecycle(registrations).reinstate(KEY);

        // Then the committed row is accepted and the mark is cleared once
        assertThat(result.succeeded()).as("result: %s", result.cause()).isTrue();
        assertThat(scenario.events).containsExactly("find", "transition DEPRECATED->AVAILABLE", "find", "unmark wf 1");
    }

    @Test
    @DisplayName("a failed change that did not commit leaves the deprecated row and its mark alone")
    void reinstatingADeprecatedRowThatDidNotCommitClearsNothing() {
        // Given a transition that fails while the row still reads as deprecated
        DeprecationScenario scenario = new DeprecationScenario();
        RuntimeWorkflow own = Mockito.mock(RuntimeWorkflow.class);
        scenario.registryHolds(own);
        ManagedRegistrations registrations = new ManagedRegistrations();
        registrations.attribute(WorkflowScopes.DEFAULT, "wf", 1, own);
        IllegalStateException failure = new IllegalStateException("database unavailable");
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.DEPRECATED))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.DEPRECATED))));
        scenario.transitions.add(Future.failedFuture(failure));

        // When the row is reinstated
        Future<Void> result = scenario.lifecycle(registrations).reinstate(KEY);

        // Then the original failure is reported and the mark stays
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(failure);
        assertThat(scenario.events).containsExactly("find", "transition DEPRECATED->AVAILABLE", "find");
    }

    @Test
    @DisplayName("reinstating a deprecated row over a runtime the lifecycle did not publish is refused before a change")
    void reinstatingADeprecatedRowRefusesAnUnattributedRuntime() {
        // Given a deprecated row and a runtime registered for the key that no attribution names
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.registryHolds(Mockito.mock(RuntimeWorkflow.class));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.DEPRECATED))));

        // When the row is reinstated
        Future<Void> result = scenario.lifecycle(new ManagedRegistrations()).reinstate(KEY);

        // Then it fails as an invalid transition after the single read, with nothing changed
        assertInvalidTransition(result);
        assertThat(scenario.events).containsExactly("find");
    }

    @ParameterizedTest(name = "a {0} row cannot be reinstated")
    @EnumSource(
            value = DefinitionStatus.class,
            names = {"CANDIDATE", "PUBLISHING", "AVAILABLE"})
    void reinstatementRefusesARowThatIsNotRetired(DefinitionStatus status) {
        // Given a row that is not retired
        ReinstateStore store = new ReinstateStore();
        store.status = status;
        ReinstateWorld world = new ReinstateWorld(store);

        // When the reinstatement runs
        Future<Void> result = world.lifecycle.reinstate(KEY);

        // Then it fails as an invalid transition after the single read, with nothing published or changed
        assertInvalidTransition(result);
        assertThat(store.events).containsExactly("find");
        assertThat(world.registry.history).isEmpty();
    }

    @Test
    @DisplayName("a missing row cannot be reinstated")
    void reinstatementRefusesAMissingRow() {
        // Given no row for the key
        ReinstateStore store = new ReinstateStore();
        store.missing = true;
        ReinstateWorld world = new ReinstateWorld(store);

        // When the reinstatement runs
        Future<Void> result = world.lifecycle.reinstate(KEY);

        // Then it fails as an invalid transition after the single read, with nothing published or changed
        assertInvalidTransition(result);
        assertThat(store.events).containsExactly("find");
        assertThat(world.registry.history).isEmpty();
    }

    /** A lifecycle over a {@link ReinstateStore}, with the registry, attributions and loader it works against. */
    private static final class ReinstateWorld {
        final PlannedRegistry registry = new PlannedRegistry();
        final ManagedRegistrations registrations = new ManagedRegistrations();
        final LoggingLoader loader = new LoggingLoader();
        final DefaultWorkflowDefinitionLifecycle lifecycle;

        ReinstateWorld(ReinstateStore store) {
            lifecycle = new DefaultWorkflowDefinitionLifecycle(
                    Optional.of(store), () -> registry, Set.of(loader), new DefinitionApplyQueue(), registrations);
        }
    }

    /** Hands out the stored text of a document and logs the rows the lifecycle hands over after a commit. */
    private static final class LoggingLoader implements PersistedDefinitionLoader {
        final List<WorkflowDefinitionRecord> handOffs = new ArrayList<>();
        private final WorkflowDefinition<?, ?> definition;

        LoggingLoader() {
            definition = Mockito.mock(WorkflowDefinition.class);
            Mockito.when(definition.definitionId()).thenReturn("wf");
            Mockito.when(definition.definitionVersion()).thenReturn(1L);
        }

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
            return definition;
        }

        @Override
        public String planHash(WorkflowDefinition<?, ?> definition) {
            return PLAN_HASH;
        }

        @Override
        public void onRestored(WorkflowDefinitionRecord record, WorkflowDefinition<?, ?> definition) {
            handOffs.add(record);
        }

        @Override
        public void onMetadata(WorkflowDefinitionRecord record) {
            handOffs.add(record);
        }
    }

    /**
     * Behaves like a registry table for one retired global document row: reads reflect its current status and
     * hash and can be made to fail, and every change is scripted by the test, which also decides what the row
     * reads as afterwards.
     */
    private static final class ReinstateStore implements WorkflowDefinitionRegistryStore {
        final List<String> events = new ArrayList<>();
        final Deque<Supplier<Future<Void>>> transitions = new ArrayDeque<>();
        DefinitionStatus status = DefinitionStatus.RETIRED;
        String planHash = PLAN_HASH;
        boolean readsFail;
        boolean missing;

        @Override
        public Future<Optional<WorkflowDefinitionRecord>> find(DefinitionKey key) {
            events.add("find");
            if (readsFail) return Future.failedFuture(new IllegalStateException("read failed"));
            return Future.succeededFuture(missing ? Optional.empty() : Optional.of(row(status, planHash)));
        }

        @Override
        public Future<List<WorkflowDefinitionRecord>> list(DefinitionFilter filter) {
            events.add("list");
            return Future.succeededFuture(List.of());
        }

        @Override
        public Future<Optional<WorkflowDefinitionDocument>> findDocument(DefinitionKey key) {
            events.add("findDocument");
            return Future.succeededFuture(Optional.of(new WorkflowDefinitionDocument(key, "{}", "YAML")));
        }

        @Override
        public Future<Void> transition(DefinitionKey key, DefinitionStatus from, DefinitionStatus to) {
            events.add("transition " + from + "->" + to);
            Supplier<Future<Void>> next = transitions.poll();
            return next != null ? next.get() : Future.failedFuture(new IllegalStateException("unscripted transition"));
        }

        @Override
        public Future<ActivationClaim> claim(DefinitionKey key) {
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
        public Future<ReconcileOutcome> upsertCode(WorkflowDefinitionRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> resetStaleActivating(String definitionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<ReconcileOutcome> upsertDocument(
                WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> recomputeActive(String scopeKey, String definitionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> retire(DefinitionKey key) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Rejects a second registration of an id and version like the default registry does, retains the registered
     * definitions, creates a new runtime per registration that reports the shared plan hash, and logs every
     * removal and every runtime ever created.
     */
    private static final class PlannedRegistry implements WorkflowRegistry {
        private final Map<String, RuntimeWorkflow> registrations = new LinkedHashMap<>();
        private final Map<String, WorkflowDefinition<?, ?>> definitions = new LinkedHashMap<>();
        final List<RuntimeWorkflow> history = new ArrayList<>();
        final List<String> unregistered = new ArrayList<>();

        @Override
        public void register(WorkflowDefinition<?, ?> def) {
            String name = def.definitionId() + "@" + def.definitionVersion();
            if (registrations.containsKey(name))
                throw new WorkflowDefinitionException("definition '" + def.definitionId() + "' v"
                        + def.definitionVersion() + " is already registered");
            WorkflowPlan plan = Mockito.mock(WorkflowPlan.class);
            Mockito.when(plan.planHash()).thenReturn(PLAN_HASH);
            RuntimeWorkflow runtime = Mockito.mock(RuntimeWorkflow.class);
            Mockito.when(runtime.plan()).thenReturn(plan);
            registrations.put(name, runtime);
            definitions.put(name, def);
            history.add(runtime);
        }

        @Override
        public RuntimeWorkflow resolveCurrent(String definitionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuntimeWorkflow resolve(DefinitionKey key) {
            String definitionId = key.definitionId();
            long version = key.definitionVersion();
            RuntimeWorkflow found = registrations.get(definitionId + "@" + version);
            if (found == null) throw new WorkflowVersionPinUnavailableException(definitionId, version, null);
            return found;
        }

        @Override
        public Optional<RuntimeWorkflow> find(DefinitionKey key) {
            return Optional.ofNullable(registrations.get(key.definitionId() + "@" + key.definitionVersion()));
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
            return List.copyOf(registrations.values());
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
            registrations.remove(name);
            definitions.remove(name);
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

    /** Behaves like a registry table for one key: reads reflect the committed status and can be made to fail. */
    private static final class ActivationStore implements WorkflowDefinitionRegistryStore {
        final Deque<Future<ActivationOutcome>> completions = new ArrayDeque<>();
        DefinitionStatus status = DefinitionStatus.CANDIDATE;
        boolean readsFail;
        boolean failReadsAfterFirstCompletion;
        int claims;
        int completes;
        int releases;

        @Override
        public Future<Optional<WorkflowDefinitionRecord>> find(DefinitionKey key) {
            if (readsFail) return Future.failedFuture(new IllegalStateException("read failed"));
            return Future.succeededFuture(Optional.of(row(status)));
        }

        @Override
        public Future<ActivationClaim> claim(DefinitionKey key) {
            claims++;
            return Future.succeededFuture(new ActivationClaim(key, UUID.randomUUID()));
        }

        @Override
        public Future<Optional<WorkflowDefinitionDocument>> findDocument(DefinitionKey key) {
            return Future.succeededFuture(Optional.of(new WorkflowDefinitionDocument(key, "{}", "YAML")));
        }

        @Override
        public Future<ActivationOutcome> complete(ActivationClaim claim) {
            completes++;
            if (completes == 1 && failReadsAfterFirstCompletion) readsFail = true;
            Future<ActivationOutcome> next = completions.poll();
            if (next == null) return Future.failedFuture(new IllegalStateException("unscripted completion"));
            if (next.succeeded()) status = DefinitionStatus.AVAILABLE;
            return next;
        }

        @Override
        public Future<Void> release(ActivationClaim claim) {
            releases++;
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
        public Future<ReconcileOutcome> upsertCode(WorkflowDefinitionRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> resetStaleActivating(String definitionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<ReconcileOutcome> upsertDocument(
                WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> recomputeActive(String scopeKey, String definitionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> retire(DefinitionKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> transition(DefinitionKey key, DefinitionStatus from, DefinitionStatus to) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Rejects a second registration of an id and version like the default registry does, creates a new
     * runtime per registration, and logs every removal.
     */
    private static final class RecordingRegistry implements WorkflowRegistry {
        private final Map<String, RuntimeWorkflow> registrations = new LinkedHashMap<>();
        final List<String> unregistered = new ArrayList<>();

        @Override
        public void register(WorkflowDefinition<?, ?> def) {
            String name = def.definitionId() + "@" + def.definitionVersion();
            if (registrations.containsKey(name))
                throw new WorkflowDefinitionException("definition '" + def.definitionId() + "' v"
                        + def.definitionVersion() + " is already registered");
            registrations.put(name, Mockito.mock(RuntimeWorkflow.class));
        }

        @Override
        public RuntimeWorkflow resolveCurrent(String definitionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuntimeWorkflow resolve(DefinitionKey key) {
            String definitionId = key.definitionId();
            long version = key.definitionVersion();
            RuntimeWorkflow found = registrations.get(definitionId + "@" + version);
            if (found == null) throw new WorkflowVersionPinUnavailableException(definitionId, version, null);
            return found;
        }

        @Override
        public Optional<RuntimeWorkflow> find(DefinitionKey key) {
            return Optional.ofNullable(registrations.get(key.definitionId() + "@" + key.definitionVersion()));
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
            return List.copyOf(registrations.values());
        }

        @Override
        public void unregister(DefinitionKey key) {
            String definitionId = key.definitionId();
            long version = key.definitionVersion();
            unregistered.add(definitionId + "@" + version);
            registrations.remove(definitionId + "@" + version);
        }
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
                    "example.Contract",
                    "example.State",
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
        public Future<ReconcileOutcome> upsertCode(WorkflowDefinitionRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> resetStaleActivating(String definitionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<ReconcileOutcome> upsertDocument(
                WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> recomputeActive(String scopeKey, String definitionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> retire(DefinitionKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> transition(DefinitionKey key, DefinitionStatus from, DefinitionStatus to) {
            throw new UnsupportedOperationException();
        }
    }
}
