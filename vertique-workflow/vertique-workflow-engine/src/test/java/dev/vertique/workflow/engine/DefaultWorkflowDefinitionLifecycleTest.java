// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionFailure;
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
import java.util.Deque;
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

/**
 * Verifies how {@link DefaultWorkflowDefinitionLifecycle} moves a definition row through its lifecycle: the claim of
 * a publication and its release, the read-back that decides a change whose outcome is unknown, and the transitions
 * that deprecate, retire and reinstate a row. The lifecycle changes only the row; it keeps no copy of it.
 */
class DefaultWorkflowDefinitionLifecycleTest {

    private static final DefinitionKey KEY = new DefinitionKey(WorkflowScopes.DEFAULT, "wf", 1);
    private static final String PLAN_HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    /** The contract of the definition the stored documents of these tests compile to. */
    @WorkflowContract("lifecycle-contract")
    interface LifecycleContract {}

    record LifecycleState(String id) {}

    record LifecycleStart(String id) {}

    /** A definition that completes at once, with the identity of {@link #KEY}. */
    private static final class CompletingDefinition implements WorkflowDefinition<LifecycleState, LifecycleContract> {
        @Override
        public Class<LifecycleContract> contract() {
            return LifecycleContract.class;
        }

        @Override
        public Class<LifecycleState> stateType() {
            return LifecycleState.class;
        }

        @Override
        public String definitionId() {
            return "wf";
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<LifecycleState> wf) {
            wf.init(LifecycleStart.class, start -> new LifecycleState(start.id()))
                    .initialStep("done")
                    .complete("done");
        }
    }

    /** A definition that never calls {@code init}, which the plan build refuses. */
    private static final class InvalidDefinition implements WorkflowDefinition<LifecycleState, LifecycleContract> {
        @Override
        public Class<LifecycleContract> contract() {
            return LifecycleContract.class;
        }

        @Override
        public Class<LifecycleState> stateType() {
            return LifecycleState.class;
        }

        @Override
        public String definitionId() {
            return "wf";
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<LifecycleState> wf) {}
    }

    // --- Publishing a candidate ---

    @Test
    @DisplayName("a publication that fails before its completion still releases the claim once")
    void releasesClaimWhenTheFailureComesBeforeTheCompletion() {
        // Given a CANDIDATE document row, a claim that succeeds, and no loader bound
        FakeStore store = new FakeStore();
        DefaultWorkflowDefinitionLifecycle lifecycle = lifecycle(store, Set.of());

        // When the publication fails with LOADER_MISSING and the reread of the row would fail as well
        Future<Void> result = lifecycle.publish(KEY);

        // Then the publication failed with the original LOADER_MISSING refusal ...
        assertThat(result.failed()).isTrue();
        assertThat(result.cause())
                .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.LOADER_MISSING));
        // ... and the claim was released exactly once
        assertThat(store.releases.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed reread after a completion of unknown outcome does not release the claim")
    void keepsClaimWhenTheCompletionOutcomeIsUnknownAndRereadFails() {
        // Given a loader whose definition builds, and a completion that fails with an unknown outcome
        FakeStore store = new FakeStore();
        store.completion = Future.failedFuture(new IllegalStateException("commit outcome unknown"));
        DefaultWorkflowDefinitionLifecycle lifecycle = lifecycle(store, Set.of(publishingLoader()));

        // When the reread of the row fails after the completion
        Future<Void> result = lifecycle.publish(KEY);

        // Then the publication failed and the claim stays for the reset of stale claims
        assertThat(result.failed()).isTrue();
        assertThat(store.releases.get()).isZero();
    }

    @Test
    @DisplayName("a publication builds the plan, completes the claim once and changes nothing when repeated")
    void publicationCompletesOnceAndARepeatChangesNothing() {
        // Given a candidate row whose document builds
        ActivationStore store = new ActivationStore();
        store.completions.add(Future.succeededFuture());
        DefaultWorkflowDefinitionLifecycle lifecycle = lifecycle(store, Set.of(publishingLoader()));

        // When it is published, and published again
        Future<Void> first = lifecycle.publish(KEY);
        Future<Void> again = lifecycle.publish(KEY);

        // Then the first publication completed the claim, and the repeat saw an available row and did nothing
        assertThat(first.succeeded()).as("first publication: %s", first.cause()).isTrue();
        assertThat(again.succeeded()).isTrue();
        assertThat(store.claims).isEqualTo(1);
        assertThat(store.completes).isEqualTo(1);
        assertThat(store.releases).isZero();
    }

    @Test
    @DisplayName("a document that does not build is refused before its completion and its claim is released")
    void documentThatDoesNotBuildIsRefusedBeforeCompletion() {
        // Given a candidate row whose compiled definition fails the plan rules
        ActivationStore store = new ActivationStore();
        store.completions.add(Future.succeededFuture());
        DefaultWorkflowDefinitionLifecycle lifecycle =
                lifecycle(store, Set.of(publishingLoader(new InvalidDefinition())));

        // When it is published
        Future<Void> result = lifecycle.publish(KEY);

        // Then the plan rule refusal is reported, the claim was released and nothing was completed
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isInstanceOf(WorkflowDefinitionException.class);
        assertThat(store.completes).isZero();
        assertThat(store.releases).isEqualTo(1);
    }

    @Test
    @DisplayName("a rebuilt plan hash that differs from the row's is refused before the completion")
    void hashDriftIsRefusedBeforeCompletion() {
        // Given a candidate row and a loader whose definition hashes to another value than the row's
        ActivationStore store = new ActivationStore();
        store.planHash = OTHER_HASH;
        DefaultWorkflowDefinitionLifecycle lifecycle = lifecycle(store, Set.of(publishingLoader()));

        // When it is published
        Future<Void> result = lifecycle.publish(KEY);

        // Then it fails as drift, with the claim released and nothing completed
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
            assertThat(failure.key()).isEqualTo(KEY);
        });
        assertThat(store.completes).isZero();
        assertThat(store.releases).isEqualTo(1);
    }

    static Stream<Arguments> rereadsThatAreNotTheCommit() {
        return Stream.of(
                Arguments.of(DefinitionStatus.PUBLISHING, PLAN_HASH),
                Arguments.of(DefinitionStatus.AVAILABLE, OTHER_HASH));
    }

    @ParameterizedTest(name = "a failed completion whose reread shows {0} with hash {1} is not a commit")
    @MethodSource("rereadsThatAreNotTheCommit")
    void aRereadThatIsNotTheCommitReleasesTheClaim(DefinitionStatus reread, String hash) {
        // Given a completion that fails, after which the row reads as something other than this publication's commit
        ActivationStore store = new ActivationStore();
        IllegalStateException failure = new IllegalStateException("completion failed");
        store.completionOutcome = () -> {
            store.status = reread;
            store.planHash = hash;
            return Future.failedFuture(failure);
        };
        DefaultWorkflowDefinitionLifecycle lifecycle = lifecycle(store, Set.of(publishingLoader()));

        // When the publication runs
        Future<Void> result = lifecycle.publish(KEY);

        // Then the failure is reported and the claim is released
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(failure);
        assertThat(store.releases).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed completion whose reread shows the row available with the row's hash is a commit")
    void aRereadWithTheRowsHashIsTheCommit() {
        // Given a completion whose response is lost after the row was committed
        ActivationStore store = new ActivationStore();
        store.completionOutcome = () -> {
            store.status = DefinitionStatus.AVAILABLE;
            return Future.failedFuture(new IllegalStateException("response lost"));
        };
        DefaultWorkflowDefinitionLifecycle lifecycle = lifecycle(store, Set.of(publishingLoader()));

        // When the publication runs
        Future<Void> result = lifecycle.publish(KEY);

        // Then it succeeds and the claim is not released
        assertThat(result.succeeded()).as("result: %s", result.cause()).isTrue();
        assertThat(store.releases).isZero();
    }

    @Test
    @DisplayName("a completion refused as a stale claim is definitive and releases the claim without a reread")
    void staleClaimRefusalReleasesTheClaim() {
        // Given a completion that the store refuses because the claim went stale
        ActivationStore store = new ActivationStore();
        WorkflowDefinitionLifecycleException stale = new WorkflowDefinitionLifecycleException(
                Reason.STALE_CLAIM, KEY, "the claim is not the row's any more");
        store.completionOutcome = () -> Future.failedFuture(stale);
        DefaultWorkflowDefinitionLifecycle lifecycle = lifecycle(store, Set.of(publishingLoader()));

        // When the publication runs
        Future<Void> result = lifecycle.publish(KEY);

        // Then the refusal is reported, the claim is released and the row was not read again
        assertThat(result.cause()).isSameAs(stale);
        assertThat(store.releases).isEqualTo(1);
        assertThat(store.finds).isEqualTo(2);
    }

    // --- Deprecating ---

    @Test
    @DisplayName("an available row is deprecated with one transition from its actual status")
    void deprecatesCommittedRow() {
        // Given a row committed as available and a transition that succeeds
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        scenario.transitions.add(Future.succeededFuture());

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then the transition ran once with the actual status
        assertThat(result.succeeded()).isTrue();
        assertThat(scenario.transitionKeys).containsExactly(KEY);
        assertThat(scenario.events).containsExactly("find", "transition AVAILABLE->DEPRECATED");
    }

    @Test
    @DisplayName("a lost transition response whose reread shows DEPRECATED still succeeds")
    void acceptsLostResponseWhenRereadShowsDeprecated() {
        // Given a transition that fails after the status was committed
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.DEPRECATED))));
        scenario.transitions.add(Future.failedFuture(new IllegalStateException("response lost")));

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then the committed status is accepted without a second transition
        assertThat(result.succeeded()).isTrue();
        assertThat(scenario.events).containsExactly("find", "transition AVAILABLE->DEPRECATED", "find");
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

        // Then the second attempt succeeded
        assertThat(result.succeeded()).isTrue();
        assertThat(scenario.events)
                .containsExactly(
                        "find", "transition AVAILABLE->DEPRECATED", "find", "find", "transition AVAILABLE->DEPRECATED");
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

        // Then there are exactly two transition attempts, and the refusal of the last one is reported
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(secondRefusal);
        assertThat(scenario.events)
                .filteredOn(event -> event.startsWith("transition"))
                .hasSize(2);
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

        // Then the original failure is reported carrying the read failure
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(transitionFailure);
        assertThat(result.cause().getSuppressed()).containsExactly(readFailure);
    }

    @Test
    @DisplayName("a JVM error from the transition is passed through without a reread or a retry")
    void passesThroughJvmErrors() {
        // Given a transition that fails with an Error
        DeprecationScenario scenario = new DeprecationScenario();
        FatalProbe fatal = new FatalProbe();
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        scenario.transitions.add(Future.failedFuture(fatal));

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then that same error is reported after a single read and a single transition
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(fatal);
        assertThat(scenario.events).containsExactly("find", "transition AVAILABLE->DEPRECATED");
    }

    @Test
    @DisplayName("an already deprecated row issues no transition")
    void issuesNoTransitionForAnAlreadyDeprecatedRow() {
        // Given a row that is already DEPRECATED, for instance after a restart or a lost response
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.DEPRECATED))));

        // When the definition is deprecated
        Future<Void> result = scenario.lifecycle().deprecate(KEY);

        // Then no transition ran
        assertThat(result.succeeded()).isTrue();
        assertThat(scenario.events).containsExactly("find");
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

        // Then it fails as an invalid transition naming the key, with no transition
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

        // Then it fails as an invalid transition naming the key, with no transition
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

        // Then the failure is reported after one transition
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

        // Then each reports its own failure after a single transition
        assertThat(plainResult.cause()).isSameAs(failure);
        assertThat(otherResult.cause()).isSameAs(staleClaim);
        for (DeprecationScenario scenario : List.of(plain, otherReason)) {
            assertThat(scenario.events)
                    .filteredOn(event -> event.startsWith("transition"))
                    .hasSize(1);
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

        // Then the refusal is reported after one transition
        assertThat(result.cause()).isSameAs(refused);
        assertThat(scenario.events)
                .filteredOn(event -> event.startsWith("transition"))
                .hasSize(1);
    }

    @Test
    @DisplayName("a null key fails the returned future instead of throwing")
    void failsFutureForNullKey() {
        // Given a lifecycle with a store
        DeprecationScenario scenario = new DeprecationScenario();

        // When deprecate is called with a null key
        Future<Void> result = scenario.lifecycle().deprecate(null);

        // Then the future fails and the store is untouched
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isInstanceOf(NullPointerException.class);
        assertThat(scenario.events).isEmpty();
    }

    // --- Retiring ---

    @Test
    @DisplayName("retiring asks the store to retire the row and nothing else")
    void retireAsksTheStoreToRetire() {
        // Given a store that retires the row
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.retirements.add(Future.succeededFuture());

        // When the row is retired
        Future<Void> result = scenario.lifecycle().retire(KEY);

        // Then the store retired the row and was not read
        assertThat(result.succeeded()).isTrue();
        assertThat(scenario.events).containsExactly("retire");
    }

    @Test
    @DisplayName("a retirement whose response was lost but whose row is retired succeeds")
    void retireWithLostResponseSucceedsWhenTheRowIsRetired() {
        // Given a retirement that fails after it committed
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.retirements.add(Future.failedFuture(new IllegalStateException("commit outcome unknown")));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.RETIRED))));

        // When the row is retired
        Future<Void> result = scenario.lifecycle().retire(KEY);

        // Then the read-back found the retirement committed and the call succeeded
        assertThat(result.succeeded()).isTrue();
        assertThat(scenario.events).containsExactly("retire", "find");
    }

    @Test
    @DisplayName("a failed retirement whose row is not retired reports the original failure")
    void retireThatDidNotCommitFailsWithTheOriginalError() {
        // Given a retirement that failed without committing
        DeprecationScenario scenario = new DeprecationScenario();
        IllegalStateException original = new IllegalStateException("pins exist");
        scenario.retirements.add(Future.failedFuture(original));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.DEPRECATED))));

        // When the row is retired
        Future<Void> result = scenario.lifecycle().retire(KEY);

        // Then the original failure is reported
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(original);
        assertThat(scenario.events).containsExactly("retire", "find");
    }

    // --- Reinstating ---

    @Test
    @DisplayName("a retired document is restored from its stored text, built and then committed as available")
    void reinstatingADocumentRestoresItsSourceBeforeTheChange() {
        // Given a retired document and a change that succeeds
        ReinstateStore store = new ReinstateStore();
        store.transitions.add(() -> {
            store.status = DefinitionStatus.AVAILABLE;
            return Future.succeededFuture();
        });
        ReinstateWorld world = new ReinstateWorld(store);

        // When it is reinstated
        Future<Void> result = world.lifecycle.reinstate(KEY);

        // Then the source was read before the change, and nothing is read after it
        assertThat(result.succeeded()).as("result: %s", result.cause()).isTrue();
        assertThat(store.events).containsExactly("find", "findDocument", "transition RETIRED->AVAILABLE");
    }

    @Test
    @DisplayName(
            "a retired document whose stored text hashes to another value than the row's is refused before a change")
    void reinstatementIsRefusedOnDriftBeforeAnyChange() {
        // Given a retired row whose hash is not the one its stored text compiles to
        ReinstateStore store = new ReinstateStore();
        store.planHash = OTHER_HASH;
        ReinstateWorld world = new ReinstateWorld(store);

        // When the reinstatement runs
        Future<Void> result = world.lifecycle.reinstate(KEY);

        // Then the refusal is reported as drift before any change was attempted
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
            assertThat(failure.key()).isEqualTo(KEY);
        });
        assertThat(store.events).containsExactly("find", "findDocument");
    }

    @Test
    @DisplayName("a retired document without its stored text is refused before a change")
    void reinstatementIsRefusedWithoutTheStoredText() {
        // Given a retired row whose text is gone
        ReinstateStore store = new ReinstateStore();
        store.textMissing = true;
        ReinstateWorld world = new ReinstateWorld(store);

        // When the reinstatement runs
        Future<Void> result = world.lifecycle.reinstate(KEY);

        // Then it fails as a missing loader source, and no change was attempted
        assertThat(result.failed()).isTrue();
        assertThat(result.cause())
                .isInstanceOfSatisfying(
                        WorkflowDefinitionLifecycleException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.LOADER_MISSING));
        assertThat(store.events).containsExactly("find", "findDocument");
    }

    @Test
    @DisplayName("a change that fails definitively is reported")
    void aDefinitiveFailureIsReported() {
        // Given a document whose change is refused for good
        ReinstateStore store = new ReinstateStore();
        WorkflowDefinitionLifecycleException conflict = refusal(Reason.INVALID_TRANSITION);
        store.transitions.add(() -> Future.failedFuture(conflict));
        ReinstateWorld world = new ReinstateWorld(store);

        // When the reinstatement runs
        Future<Void> result = world.lifecycle.reinstate(KEY);

        // Then the refusal is reported
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(conflict);
    }

    static Stream<Arguments> reinstatementRereadsThatAreNotTheCommit() {
        return Stream.of(
                Arguments.of(DefinitionStatus.DEPRECATED, PLAN_HASH),
                Arguments.of(DefinitionStatus.AVAILABLE, OTHER_HASH));
    }

    @ParameterizedTest(name = "a failed change whose reread shows {0} with hash {1} is not a commit")
    @MethodSource("reinstatementRereadsThatAreNotTheCommit")
    void aRereadThatIsNotTheReinstatementCommitFails(DefinitionStatus reread, String hash) {
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

        // Then the failure is reported: a row that is not this reinstatement's commit does not count as committed
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(failure);
    }

    @Test
    @DisplayName("a failed change whose reread shows the row available with the row's hash is a commit")
    void aRereadWithTheRowsHashIsTheReinstatementCommit() {
        // Given a change whose response is lost after the row was committed
        ReinstateStore store = new ReinstateStore();
        store.transitions.add(() -> {
            store.status = DefinitionStatus.AVAILABLE;
            return Future.failedFuture(new IllegalStateException("response lost"));
        });
        ReinstateWorld world = new ReinstateWorld(store);

        // When the reinstatement runs
        Future<Void> result = world.lifecycle.reinstate(KEY);

        // Then it succeeds
        assertThat(result.succeeded()).as("result: %s", result.cause()).isTrue();
    }

    @Test
    @DisplayName("a retired code row is reinstated when the deployed code still provides its hash")
    void reinstatingACodeRowChecksTheDeployedCode() {
        // Given a retired code row and deployed code with the row's plan hash
        DefaultWorkflowRegistry deployed = new DefaultWorkflowRegistry();
        deployed.register(new CompletingDefinition());
        WorkflowCodeDefinitionCatalog catalog = new WorkflowCodeDefinitionCatalog();
        catalog.capture(deployed);
        String deployedHash =
                deployed.codeDefinitions().iterator().next().plan().planHash();
        ReinstateStore store = new ReinstateStore();
        store.kind = DefinitionKind.CODE;
        store.planHash = deployedHash;
        store.transitions.add(() -> Future.succeededFuture());
        DefaultWorkflowDefinitionLifecycle lifecycle = new DefaultWorkflowDefinitionLifecycle(
                store, () -> deployed, Set.of(), new DefinitionApplyQueue(), catalog);

        // When it is reinstated
        Future<Void> result = lifecycle.reinstate(KEY);

        // Then the change was committed without reading a document
        assertThat(result.succeeded()).as("result: %s", result.cause()).isTrue();
        assertThat(store.events).containsExactly("find", "transition RETIRED->AVAILABLE");
    }

    @ParameterizedTest(name = "a retired code row is refused as {0} when the deployed code {1}")
    @MethodSource("codeReinstatementsThatAreRefused")
    void reinstatingACodeRowIsRefusedWithoutMatchingDeployedCode(Reason reason, String situation, boolean provided) {
        // Given a retired code row, and deployed code that is missing or has another plan hash
        DefaultWorkflowRegistry deployed = new DefaultWorkflowRegistry();
        WorkflowCodeDefinitionCatalog catalog = new WorkflowCodeDefinitionCatalog();
        if (provided) deployed.register(new CompletingDefinition());
        catalog.capture(deployed);
        ReinstateStore store = new ReinstateStore();
        store.kind = DefinitionKind.CODE;
        DefaultWorkflowDefinitionLifecycle lifecycle = new DefaultWorkflowDefinitionLifecycle(
                store, () -> deployed, Set.of(), new DefinitionApplyQueue(), catalog);

        // When it is reinstated
        Future<Void> result = lifecycle.reinstate(KEY);

        // Then it is refused before any change
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.key()).isEqualTo(KEY);
        });
        assertThat(store.events).containsExactly("find");
    }

    static Stream<Arguments> codeReinstatementsThatAreRefused() {
        return Stream.of(
                Arguments.of(Reason.CODE_UNAVAILABLE, "no longer provides it", false),
                Arguments.of(Reason.DRIFT, "provides another plan hash", true));
    }

    @Test
    @DisplayName("a deprecated row is committed as available without restoring its source")
    void reinstatingADeprecatedRowCommitsAtOnce() {
        // Given a deprecated row
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.DEPRECATED))));
        scenario.transitions.add(Future.succeededFuture());

        // When the row is reinstated
        Future<Void> result = scenario.lifecycle().reinstate(KEY);

        // Then the durable change ran once from deprecated to available
        assertThat(result.succeeded()).as("result: %s", result.cause()).isTrue();
        assertThat(scenario.events).containsExactly("find", "transition DEPRECATED->AVAILABLE");
    }

    @Test
    @DisplayName("a lost response whose reread shows the deprecated row available is accepted")
    void reinstatingADeprecatedRowAcceptsALostResponseThatCommitted() {
        // Given a transition that fails after the row was committed as available
        DeprecationScenario scenario = new DeprecationScenario();
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.DEPRECATED))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.AVAILABLE))));
        scenario.transitions.add(Future.failedFuture(new IllegalStateException("response lost")));

        // When the row is reinstated
        Future<Void> result = scenario.lifecycle().reinstate(KEY);

        // Then the committed row is accepted
        assertThat(result.succeeded()).as("result: %s", result.cause()).isTrue();
        assertThat(scenario.events).containsExactly("find", "transition DEPRECATED->AVAILABLE", "find");
    }

    @Test
    @DisplayName("a failed change that did not commit leaves the deprecated row alone")
    void reinstatingADeprecatedRowThatDidNotCommitFails() {
        // Given a transition that fails while the row still reads as deprecated
        DeprecationScenario scenario = new DeprecationScenario();
        IllegalStateException failure = new IllegalStateException("database unavailable");
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.DEPRECATED))));
        scenario.reads.add(Future.succeededFuture(Optional.of(row(DefinitionStatus.DEPRECATED))));
        scenario.transitions.add(Future.failedFuture(failure));

        // When the row is reinstated
        Future<Void> result = scenario.lifecycle().reinstate(KEY);

        // Then the original failure is reported
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(failure);
        assertThat(scenario.events).containsExactly("find", "transition DEPRECATED->AVAILABLE", "find");
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

        // Then it fails as an invalid transition after the single read, with nothing changed
        assertInvalidTransition(result);
        assertThat(store.events).containsExactly("find");
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

        // Then it fails as an invalid transition after the single read, with nothing changed
        assertInvalidTransition(result);
        assertThat(store.events).containsExactly("find");
    }

    // --- Support ---

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
        return row(status, PLAN_HASH, DefinitionKind.DOCUMENT);
    }

    private static WorkflowDefinitionRecord row(DefinitionStatus status, String planHash, DefinitionKind kind) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new WorkflowDefinitionRecord(
                KEY,
                kind,
                "example.Contract",
                "example.State",
                planHash,
                status,
                new DefinitionProvenance("test", null, null, null, null, now),
                kind == DefinitionKind.DOCUMENT ? "YAML" : null,
                now,
                now,
                Map.of(),
                null);
    }

    private static DefaultWorkflowDefinitionLifecycle lifecycle(
            WorkflowDefinitionRegistryStore store, Set<PersistedDefinitionLoader> loaders) {
        WorkflowRegistry registry = new DefaultWorkflowRegistry();
        return new DefaultWorkflowDefinitionLifecycle(store, () -> registry, loaders, new DefinitionApplyQueue());
    }

    private static PersistedDefinitionLoader publishingLoader() {
        return publishingLoader(new CompletingDefinition());
    }

    /** A loader that compiles every stored text to the given definition and hashes it to the plan hash of the tests. */
    private static PersistedDefinitionLoader publishingLoader(WorkflowDefinition<?, ?> definition) {
        return new HashingLoader(definition);
    }

    /** Hands out the given definition for every stored text, hashed to {@code PLAN_HASH}. */
    private static final class HashingLoader implements PersistedDefinitionLoader {
        private final WorkflowDefinition<?, ?> definition;

        HashingLoader(WorkflowDefinition<?, ?> definition) {
            this.definition = definition;
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
    }

    /** A JVM-level failure that must never be retried or swallowed. */
    private static final class FatalProbe extends Error {}

    /** Scripts the reads and transitions of one change of a row and logs them in order. */
    private static final class DeprecationScenario {
        final List<String> events = new ArrayList<>();
        final Deque<Future<Optional<WorkflowDefinitionRecord>>> reads = new ArrayDeque<>();
        final Deque<Future<Void>> transitions = new ArrayDeque<>();
        final Deque<Future<Void>> retirements = new ArrayDeque<>();
        final List<DefinitionKey> transitionKeys = new ArrayList<>();
        private final WorkflowRegistry registry = new DefaultWorkflowRegistry();

        DefaultWorkflowDefinitionLifecycle lifecycle() {
            return new DefaultWorkflowDefinitionLifecycle(
                    new ScriptedStore(this), () -> registry, Set.of(), new DefinitionApplyQueue());
        }
    }

    /** A lifecycle over a {@link ReinstateStore}, with the loader it works against. */
    private static final class ReinstateWorld {
        final DefaultWorkflowDefinitionLifecycle lifecycle;

        ReinstateWorld(ReinstateStore store) {
            WorkflowRegistry registry = new DefaultWorkflowRegistry();
            lifecycle = new DefaultWorkflowDefinitionLifecycle(
                    store, () -> registry, Set.of(publishingLoader()), new DefinitionApplyQueue());
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
        public Future<Void> complete(ActivationClaim claim) {
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
        public Future<ReconcileOutcome> upsertCode(WorkflowDefinitionRecord record, boolean activateDefault) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<ReconcileOutcome> upsertDocument(
                WorkflowDefinitionRecord record, WorkflowDefinitionDocument document, boolean activateDefault) {
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
        public Future<Void> recordFailure(DefinitionKey key, DefinitionFailure failure) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> retire(DefinitionKey key) {
            scenario.events.add("retire");
            Future<Void> next = scenario.retirements.poll();
            return next != null ? next : Future.failedFuture(new IllegalStateException("unscripted retirement"));
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
        DefinitionKind kind = DefinitionKind.DOCUMENT;
        String planHash = PLAN_HASH;
        boolean readsFail;
        boolean missing;
        boolean textMissing;

        @Override
        public Future<Optional<WorkflowDefinitionRecord>> find(DefinitionKey key) {
            events.add("find");
            if (readsFail) return Future.failedFuture(new IllegalStateException("read failed"));
            return Future.succeededFuture(missing ? Optional.empty() : Optional.of(row(status, planHash, kind)));
        }

        @Override
        public Future<List<WorkflowDefinitionRecord>> list(DefinitionFilter filter) {
            events.add("list");
            return Future.succeededFuture(List.of());
        }

        @Override
        public Future<Optional<WorkflowDefinitionDocument>> findDocument(DefinitionKey key) {
            events.add("findDocument");
            if (textMissing) return Future.succeededFuture(Optional.empty());
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
        public Future<Void> complete(ActivationClaim claim) {
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
        public Future<ReconcileOutcome> upsertCode(WorkflowDefinitionRecord record, boolean activateDefault) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<ReconcileOutcome> upsertDocument(
                WorkflowDefinitionRecord record, WorkflowDefinitionDocument document, boolean activateDefault) {
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
        public Future<Void> recordFailure(DefinitionKey key, DefinitionFailure failure) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> retire(DefinitionKey key) {
            throw new UnsupportedOperationException();
        }
    }

    /** Behaves like a registry table for one key: reads reflect the committed status and can be made to fail. */
    private static final class ActivationStore implements WorkflowDefinitionRegistryStore {
        final Deque<Future<Void>> completions = new ArrayDeque<>();
        Supplier<Future<Void>> completionOutcome;
        DefinitionStatus status = DefinitionStatus.CANDIDATE;
        String planHash = PLAN_HASH;
        int finds;
        int claims;
        int completes;
        int releases;

        @Override
        public Future<Optional<WorkflowDefinitionRecord>> find(DefinitionKey key) {
            finds++;
            return Future.succeededFuture(Optional.of(row(status, planHash, DefinitionKind.DOCUMENT)));
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
        public Future<Void> complete(ActivationClaim claim) {
            completes++;
            if (completionOutcome != null) return completionOutcome.get();
            Future<Void> next = completions.poll();
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
        public Future<ReconcileOutcome> upsertCode(WorkflowDefinitionRecord record, boolean activateDefault) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<ReconcileOutcome> upsertDocument(
                WorkflowDefinitionRecord record, WorkflowDefinitionDocument document, boolean activateDefault) {
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
        public Future<Void> recordFailure(DefinitionKey key, DefinitionFailure failure) {
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

    /** Serves the candidate row for the first two reads, fails every later read, counts releases. */
    private static final class FakeStore implements WorkflowDefinitionRegistryStore {
        final AtomicInteger releases = new AtomicInteger();
        private final AtomicInteger finds = new AtomicInteger();
        Future<Void> completion = Future.succeededFuture();

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
                    now,
                    Map.of(),
                    null);
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
        public Future<Void> complete(ActivationClaim claim) {
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
        public Future<ReconcileOutcome> upsertCode(WorkflowDefinitionRecord record, boolean activateDefault) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<ReconcileOutcome> upsertDocument(
                WorkflowDefinitionRecord record, WorkflowDefinitionDocument document, boolean activateDefault) {
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
        public Future<Void> recordFailure(DefinitionKey key, DefinitionFailure failure) {
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
