// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link WorkflowDefinitionStore}:
 * <ul>
 *   <li>{@link WorkflowDefinitionStore#storeCandidate} stores a definition as CANDIDATE and rejects
 *       duplicates.</li>
 *   <li>{@link WorkflowDefinitionStore#tryClaimForActivation} atomically CAS-flips
 *       {@code CANDIDATE → ACTIVATING}; only one concurrent caller wins the claim.</li>
 *   <li>{@link WorkflowDefinitionStore#markActive} atomically transitions ACTIVATING → ACTIVE and
 *       marks lower-version ACTIVE entries as SUPERSEDED.</li>
 *   <li>{@link WorkflowDefinitionStore#resetToCandidate} releases a held ACTIVATING claim back to
 *       CANDIDATE.</li>
 *   <li>{@link WorkflowDefinitionStore#list} returns a snapshot that does not reflect subsequent
 *       mutations.</li>
 *   <li>Concurrent {@code markActive} calls for distinct versions converge: exactly one winner
 *       per definitionId ends up ACTIVE; all others are SUPERSEDED.</li>
 * </ul>
 */
class WorkflowDefinitionStoreTest {

    // --- Fixture types ---

    record OrderState(String id) {}

    record StartOrder(String id) {}

    @WorkflowContract(definitionId = "order", definitionVersion = 1)
    interface OrderV1Contract {}

    @WorkflowContract(definitionId = "order", definitionVersion = 2)
    interface OrderV2Contract {}

    // --- Helpers ---

    private static WorkflowDefinition<OrderState, ?> minimalDef(String defId, long version, Class<?> contract) {
        return new WorkflowDefinition<>() {
            @Override
            @SuppressWarnings("unchecked")
            public Class contract() {
                return contract;
            }

            @Override
            public Class<OrderState> stateType() {
                return OrderState.class;
            }

            @Override
            public String definitionId() {
                return defId;
            }

            @Override
            public long definitionVersion() {
                return version;
            }

            @Override
            public void define(WorkflowBuilder<OrderState> wf) {
                wf.init(StartOrder.class, cmd -> new OrderState(cmd.id()))
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    private static CompiledDefinition compiled(String defId, long version, Class<?> contract) {
        WorkflowDefinition<OrderState, ?> def = minimalDef(defId, version, contract);
        SourceMetadata meta = new SourceMetadata("test", null, null, null, null, Instant.now());
        return new CompiledDefinition(
                def, "hash-" + defId + "-v" + version, meta, OrderState.class.getName(), contract.getName());
    }

    private WorkflowDefinitionStore store;

    @BeforeEach
    void setUp() {
        store = new WorkflowDefinitionStore();
    }

    // --- storeCandidate ---

    @Nested
    @DisplayName("storeCandidate")
    class StoreCandidateTests {

        @Test
        @DisplayName("stored definition is retrievable with status CANDIDATE")
        void storeCandidateThenGetReturnsCandidateStatus() {
            CompiledDefinition c = compiled("order", 1, OrderV1Contract.class);
            store.storeCandidate(c);

            Optional<WorkflowDefinitionStore.StoredEntry> entry = store.get("order", 1);
            assertThat(entry).isPresent();
            assertThat(entry.get().status().get()).isEqualTo(ActivationStatus.CANDIDATE);
            assertThat(entry.get().compiled().definition().definitionId()).isEqualTo("order");
        }

        @Test
        @DisplayName("duplicate key throws WorkflowDefinitionException")
        void duplicateKeyThrows() {
            store.storeCandidate(compiled("order", 1, OrderV1Contract.class));
            assertThatThrownBy(() -> store.storeCandidate(compiled("order", 1, OrderV1Contract.class)))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("already stored");
        }
    }

    // --- tryClaimForActivation ---

    @Nested
    @DisplayName("tryClaimForActivation")
    class TryClaimTests {

        @Test
        @DisplayName("claim on CANDIDATE entry returns true and transitions status to ACTIVATING")
        void claimOnCandidateReturnsTrue() {
            store.storeCandidate(compiled("order", 1, OrderV1Contract.class));
            boolean won = store.tryClaimForActivation("order", 1);
            assertThat(won).isTrue();
            assertThat(store.get("order", 1).get().status().get()).isEqualTo(ActivationStatus.ACTIVATING);
        }

        @Test
        @DisplayName("claim on nonexistent key throws WorkflowDefinitionException")
        void claimOnNonexistentKeyThrows() {
            assertThatThrownBy(() -> store.tryClaimForActivation("nonexistent", 1))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("not found in the store");
        }

        @Test
        @DisplayName("second claim attempt on ACTIVATING entry returns false")
        void secondClaimOnActivatingReturnsFalse() {
            store.storeCandidate(compiled("order", 1, OrderV1Contract.class));
            store.tryClaimForActivation("order", 1); // first winner

            boolean secondResult = store.tryClaimForActivation("order", 1);
            // CAS fails (already ACTIVATING); loser gets false
            assertThat(secondResult).isFalse();
            // Status remains ACTIVATING (unchanged)
            assertThat(store.get("order", 1).get().status().get()).isEqualTo(ActivationStatus.ACTIVATING);
        }
    }

    // --- resetToCandidate ---

    @Nested
    @DisplayName("resetToCandidate")
    class ResetToCandidateTests {

        @Test
        @DisplayName("reset releases ACTIVATING claim back to CANDIDATE")
        void resetReleasesActivatingClaim() {
            store.storeCandidate(compiled("order", 1, OrderV1Contract.class));
            store.tryClaimForActivation("order", 1);
            assertThat(store.get("order", 1).get().status().get()).isEqualTo(ActivationStatus.ACTIVATING);

            store.resetToCandidate("order", 1);
            assertThat(store.get("order", 1).get().status().get()).isEqualTo(ActivationStatus.CANDIDATE);
        }

        @Test
        @DisplayName("reset is a no-op on an unknown entry")
        void resetOnNonexistentIsNoOp() {
            // Must not throw
            store.resetToCandidate("nonexistent", 99);
        }
    }

    // --- markActive ---

    @Nested
    @DisplayName("markActive")
    class MarkActiveTests {

        /** Helper: store + claim (CANDIDATE → ACTIVATING) to reach the markActive precondition. */
        private void storeAndClaim(String defId, long version, Class<?> contract) {
            store.storeCandidate(compiled(defId, version, contract));
            store.tryClaimForActivation(defId, version);
        }

        @Test
        @DisplayName("markActive transitions ACTIVATING to ACTIVE and returns true")
        void markActiveFromActivatingReturnsTrue() {
            storeAndClaim("order", 1, OrderV1Contract.class);
            boolean flipped = store.markActive("order", 1);
            assertThat(flipped).isTrue();

            Optional<WorkflowDefinitionStore.StoredEntry> entry = store.get("order", 1);
            assertThat(entry.get().status().get()).isEqualTo(ActivationStatus.ACTIVE);
        }

        @Test
        @DisplayName("second markActive on already-ACTIVE entry returns false (CAS misses ACTIVATING)")
        void secondMarkActiveReturnsFalse() {
            storeAndClaim("order", 1, OrderV1Contract.class);
            store.markActive("order", 1); // first activation
            boolean secondFlip = store.markActive("order", 1);
            assertThat(secondFlip).isFalse();
        }

        @Test
        @DisplayName("activating v2 marks prior v1 ACTIVE entry as SUPERSEDED")
        void activatingHigherVersionSupersedesLowerActive() {
            storeAndClaim("order", 1, OrderV1Contract.class);
            storeAndClaim("order", 2, OrderV2Contract.class);

            store.markActive("order", 1);
            assertThat(store.get("order", 1).get().status().get()).isEqualTo(ActivationStatus.ACTIVE);

            store.markActive("order", 2);
            assertThat(store.get("order", 2).get().status().get()).isEqualTo(ActivationStatus.ACTIVE);
            assertThat(store.get("order", 1).get().status().get()).isEqualTo(ActivationStatus.SUPERSEDED);
        }

        @Test
        @DisplayName("markActive on unknown key throws WorkflowDefinitionException")
        void markActiveOnUnknownKeyThrows() {
            assertThatThrownBy(() -> store.markActive("nonexistent", 1))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("no entry found");
        }

        @Test
        @DisplayName("activating lower version when higher already ACTIVE results in SUPERSEDED status")
        void activatingLowerVersionWhenHigherAlreadyActiveIsSuperseded() {
            storeAndClaim("order", 1, OrderV1Contract.class);
            storeAndClaim("order", 2, OrderV2Contract.class);

            // Activate v2 first
            store.markActive("order", 2);
            assertThat(store.get("order", 2).get().status().get()).isEqualTo(ActivationStatus.ACTIVE);

            // Then activate v1 — v2 is already ACTIVE with higher version, so v1 is superseded
            boolean flipped = store.markActive("order", 1);
            assertThat(flipped).isFalse();
            assertThat(store.get("order", 1).get().status().get()).isEqualTo(ActivationStatus.SUPERSEDED);
            // v2 must remain ACTIVE
            assertThat(store.get("order", 2).get().status().get()).isEqualTo(ActivationStatus.ACTIVE);
        }
    }

    // --- Concurrent activation (A5) ---

    @Nested
    @DisplayName("concurrent markActive for distinct versions")
    class ConcurrentMarkActiveTests {

        @Test
        @DisplayName("8 threads activating distinct versions — exactly one ACTIVE, rest SUPERSEDED, no exceptions")
        @SuppressWarnings("unchecked")
        void eightDistinctVersionsConcurrentlyActivated() throws Exception {
            // Pre-load 8 distinct versions (v1..v8) and claim all for activation
            int numVersions = 8;
            for (int v = 1; v <= numVersions; v++) {
                store.storeCandidate(compiled("order", v, v == 1 ? OrderV1Contract.class : OrderV2Contract.class));
                store.tryClaimForActivation("order", v);
            }

            ExecutorService pool = Executors.newFixedThreadPool(numVersions);
            List<Future<Void>> futures = new ArrayList<>();
            AtomicInteger exceptionCount = new AtomicInteger(0);

            for (int v = 1; v <= numVersions; v++) {
                final long version = v;
                futures.add(pool.submit(() -> {
                    try {
                        store.markActive("order", version);
                    } catch (RuntimeException ex) {
                        exceptionCount.incrementAndGet();
                    }
                    return null;
                }));
            }

            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);

            // Collect results (rethrow any unexpected exceptions)
            for (Future<Void> f : futures) {
                f.get();
            }

            // No thread should have thrown
            assertThat(exceptionCount.get()).isZero();

            // After all threads complete: exactly one version is ACTIVE, rest are SUPERSEDED
            long activeCount = 0;
            long supersededCount = 0;
            for (int v = 1; v <= numVersions; v++) {
                ActivationStatus st =
                        store.get("order", v).orElseThrow().status().get();
                if (st == ActivationStatus.ACTIVE) {
                    activeCount++;
                } else if (st == ActivationStatus.SUPERSEDED) {
                    supersededCount++;
                }
            }
            assertThat(activeCount).isEqualTo(1);
            assertThat(supersededCount).isEqualTo(numVersions - 1L);
        }

        /**
         * Deterministic hook test for the post-CAS re-scan fix (W2).
         *
         * <p>Both threads (v=1 and v=2) are pinned by {@code preCasHookForTesting} after their
         * pre-CAS scan (which sees "no higher ACTIVE") but before the ACTIVATING→ACTIVE CAS. The
         * hook ensures both threads have independently concluded "I can become ACTIVE" before
         * either executes the CAS. Without the post-CAS re-scan both would flip to ACTIVE,
         * violating the single-ACTIVE invariant. With the fix, the lower-version thread detects
         * the winner in the post-CAS re-scan and CAS-flips itself to SUPERSEDED.
         *
         * <p>The race is pinned as follows:
         * <ol>
         *   <li>Hook installed: each invocation (one per thread) signals an arrival latch, then
         *       blocks on a per-thread release latch.</li>
         *   <li>Both threads are spawned. Each reaches the hook after its pre-CAS scan and pins
         *       (the hook blocks).</li>
         *   <li>Main thread waits until both are pinned (arrival latch reaches 0).</li>
         *   <li>Main thread releases v2's thread first, waits for it to complete (v2 becomes
         *       ACTIVE), then releases v1's thread.</li>
         *   <li>v1's thread CAS-flips to ACTIVE (stale pre-CAS scan), then post-CAS re-scan
         *       sees v2 is ACTIVE (higher version) and self-supersedes.</li>
         * </ol>
         *
         * <p>The {@code preCasHookForTesting} field is volatile and package-private. It is
         * {@code null} in production; only tests in this package can set it.
         */
        @Test
        @DisplayName("post-CAS re-scan (W2): preCasHook-pinned race — v2 wins, v1 is SUPERSEDED")
        void postCasRescanEnsuresSingleActiveUnderPinnedConcurrentRace() throws Exception {
            // Pre-load v1 and v2; claim both (CANDIDATE → ACTIVATING)
            store.storeCandidate(compiled("order", 1, OrderV1Contract.class));
            store.storeCandidate(compiled("order", 2, OrderV2Contract.class));
            store.tryClaimForActivation("order", 1);
            store.tryClaimForActivation("order", 2);

            // Latches used by the hook to pin both threads between pre-CAS scan and the CAS.
            // arrivalLatch: counts down each time a thread reaches the hook (initially 2).
            // releaseV1, releaseV2: each thread waits on its own release latch.
            CountDownLatch arrivalLatch = new CountDownLatch(2);
            CountDownLatch releaseV1 = new CountDownLatch(1);
            CountDownLatch releaseV2 = new CountDownLatch(1);

            // The hook is called by each thread after it has passed the pre-CAS scan but before
            // it executes the ACTIVATING→ACTIVE CAS. We need to distinguish which thread is which;
            // we use the version number embedded in the thread name.
            store.preCasHookForTesting = () -> {
                arrivalLatch.countDown();
                // Determine which release latch to block on by inspecting the definitionVersion
                // being activated. We use the thread name set below for coordination.
                String threadName = Thread.currentThread().getName();
                CountDownLatch release = threadName.contains("v2") ? releaseV2 : releaseV1;
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            };

            ExecutorService pool = Executors.newFixedThreadPool(2);

            Future<Void> t1 = pool.submit(() -> {
                Thread.currentThread().setName("markActive-v1");
                store.markActive("order", 1);
                return null;
            });
            Future<Void> t2 = pool.submit(() -> {
                Thread.currentThread().setName("markActive-v2");
                store.markActive("order", 2);
                return null;
            });

            // Wait until both threads are pinned inside the hook (past the pre-CAS scan)
            assertThat(arrivalLatch.await(5, TimeUnit.SECONDS))
                    .as("both threads should reach the preCasHook within 5 s")
                    .isTrue();

            // Release v2 first and wait for it to complete fully (v2 becomes ACTIVE, sweeps
            // any lower ACTIVE in its post-CAS re-scan)
            releaseV2.countDown();
            t2.get(5, TimeUnit.SECONDS);

            // Now release v1. Its pre-CAS scan was stale (saw no ACTIVE at that point). Its CAS
            // flips to ACTIVE, then the post-CAS re-scan sees v2 ACTIVE (higher) and self-supersedes.
            releaseV1.countDown();
            t1.get(5, TimeUnit.SECONDS);

            pool.shutdown();

            // Invariant: v2 ACTIVE, v1 SUPERSEDED (higher version wins)
            ActivationStatus st1 = store.get("order", 1).orElseThrow().status().get();
            ActivationStatus st2 = store.get("order", 2).orElseThrow().status().get();

            assertThat(st2).as("v2 (higher version) must be ACTIVE").isEqualTo(ActivationStatus.ACTIVE);
            assertThat(st1).as("v1 (lower version) must be SUPERSEDED").isEqualTo(ActivationStatus.SUPERSEDED);
        }

        @Test
        @DisplayName("duplicate-version threads: exactly one wins the CAS; others return false")
        void duplicateVersionConcurrentClaimOnlyOneWins() throws Exception {
            store.storeCandidate(compiled("order", 1, OrderV1Contract.class));

            int numThreads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(numThreads);
            List<Future<Boolean>> futures = new ArrayList<>();

            for (int i = 0; i < numThreads; i++) {
                futures.add(pool.submit(() -> store.tryClaimForActivation("order", 1)));
            }
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);

            // Exactly one caller must have won the CAS (returned true)
            long winners = futures.stream()
                    .mapToLong(f -> {
                        try {
                            return Boolean.TRUE.equals(f.get()) ? 1 : 0;
                        } catch (Exception e) {
                            return 0;
                        }
                    })
                    .sum();
            assertThat(winners).isEqualTo(1);

            // The entry is in ACTIVATING state
            assertThat(store.get("order", 1).get().status().get()).isEqualTo(ActivationStatus.ACTIVATING);
        }
    }

    // --- list ---

    @Nested
    @DisplayName("list")
    class ListTests {

        @Test
        @DisplayName("list returns snapshot — mutations after the call are not reflected")
        void listReturnsSnapshot() {
            store.storeCandidate(compiled("order", 1, OrderV1Contract.class));
            Collection<CompiledDefinitionRef> snapshot = store.list();
            assertThat(snapshot).hasSize(1);
            assertThat(snapshot.iterator().next().status()).isEqualTo(ActivationStatus.CANDIDATE);

            // Activate after the snapshot was taken — should not change the snapshot
            store.tryClaimForActivation("order", 1);
            store.markActive("order", 1);
            assertThat(snapshot.iterator().next().status()).isEqualTo(ActivationStatus.CANDIDATE);

            // A fresh list() call sees the updated status
            Collection<CompiledDefinitionRef> fresh = store.list();
            assertThat(fresh.iterator().next().status()).isEqualTo(ActivationStatus.ACTIVE);
        }

        @Test
        @DisplayName("empty store returns empty list")
        void emptyStoreReturnsEmptyList() {
            assertThat(store.list()).isEmpty();
        }
    }

    // --- sourceMetadata ---

    @Nested
    @DisplayName("sourceMetadata")
    class SourceMetadataTests {

        @Test
        @DisplayName("sourceMetadata returns metadata for stored definition")
        void sourceMetadataReturnsMeta() {
            SourceMetadata meta =
                    new SourceMetadata("upload", "s3://bucket/key", "abc123", "alice", "up-1", Instant.now());
            WorkflowDefinition<OrderState, ?> def = minimalDef("order", 1, OrderV1Contract.class);
            CompiledDefinition c = new CompiledDefinition(
                    def, "hash-order-v1", meta, OrderState.class.getName(), OrderV1Contract.class.getName());
            store.storeCandidate(c);

            Optional<SourceMetadata> found = store.sourceMetadata("order", 1);
            assertThat(found).isPresent();
            assertThat(found.get().sourceType()).isEqualTo("upload");
            assertThat(found.get().uri()).isEqualTo("s3://bucket/key");
        }

        @Test
        @DisplayName("sourceMetadata returns empty for unknown key")
        void sourceMetadataReturnsEmptyForUnknown() {
            assertThat(store.sourceMetadata("nonexistent", 99)).isEmpty();
        }
    }
}
