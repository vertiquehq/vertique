// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
 *       {@code CANDIDATE → PUBLISHING}; only one concurrent caller wins the claim.</li>
 *   <li>{@link WorkflowDefinitionStore#markActive} atomically transitions PUBLISHING → AVAILABLE and
 *       changes no other entry.</li>
 *   <li>{@link WorkflowDefinitionStore#resetToCandidate} releases a held PUBLISHING claim back to
 *       CANDIDATE.</li>
 *   <li>{@link WorkflowDefinitionStore#list} returns a snapshot that does not reflect subsequent
 *       mutations.</li>
 *   <li>Concurrent {@code markActive} calls for distinct versions all succeed: every version of a
 *       definitionId ends up AVAILABLE.</li>
 * </ul>
 */
class WorkflowDefinitionStoreTest {

    // --- Fixture types ---

    record OrderState(String id) {}

    record StartOrder(String id) {}

    @WorkflowContract
    interface OrderV1Contract {}

    @WorkflowContract
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
        @DisplayName("claim on CANDIDATE entry returns true and transitions status to PUBLISHING")
        void claimOnCandidateReturnsTrue() {
            store.storeCandidate(compiled("order", 1, OrderV1Contract.class));
            boolean won = store.tryClaimForActivation("order", 1);
            assertThat(won).isTrue();
            assertThat(store.get("order", 1).get().status().get()).isEqualTo(ActivationStatus.PUBLISHING);
        }

        @Test
        @DisplayName("claim on nonexistent key throws WorkflowDefinitionException")
        void claimOnNonexistentKeyThrows() {
            assertThatThrownBy(() -> store.tryClaimForActivation("nonexistent", 1))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("not found in the store");
        }

        @Test
        @DisplayName("second claim attempt on PUBLISHING entry returns false")
        void secondClaimOnActivatingReturnsFalse() {
            store.storeCandidate(compiled("order", 1, OrderV1Contract.class));
            store.tryClaimForActivation("order", 1); // first winner

            boolean secondResult = store.tryClaimForActivation("order", 1);
            // CAS fails (already PUBLISHING); loser gets false
            assertThat(secondResult).isFalse();
            // Status remains PUBLISHING (unchanged)
            assertThat(store.get("order", 1).get().status().get()).isEqualTo(ActivationStatus.PUBLISHING);
        }
    }

    // --- resetToCandidate ---

    @Nested
    @DisplayName("resetToCandidate")
    class ResetToCandidateTests {

        @Test
        @DisplayName("reset releases PUBLISHING claim back to CANDIDATE")
        void resetReleasesPublishingClaim() {
            store.storeCandidate(compiled("order", 1, OrderV1Contract.class));
            store.tryClaimForActivation("order", 1);
            assertThat(store.get("order", 1).get().status().get()).isEqualTo(ActivationStatus.PUBLISHING);

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

        /** Helper: store + claim (CANDIDATE → PUBLISHING) to reach the markActive precondition. */
        private void storeAndClaim(String defId, long version, Class<?> contract) {
            store.storeCandidate(compiled(defId, version, contract));
            store.tryClaimForActivation(defId, version);
        }

        @Test
        @DisplayName("markActive transitions PUBLISHING to AVAILABLE and returns true")
        void markActiveFromPublishingReturnsTrue() {
            storeAndClaim("order", 1, OrderV1Contract.class);
            boolean flipped = store.markActive("order", 1);
            assertThat(flipped).isTrue();

            Optional<WorkflowDefinitionStore.StoredEntry> entry = store.get("order", 1);
            assertThat(entry.get().status().get()).isEqualTo(ActivationStatus.AVAILABLE);
        }

        @Test
        @DisplayName("second markActive on already-AVAILABLE entry returns false (CAS misses PUBLISHING)")
        void secondMarkActiveReturnsFalse() {
            storeAndClaim("order", 1, OrderV1Contract.class);
            store.markActive("order", 1); // first publication
            boolean secondFlip = store.markActive("order", 1);
            assertThat(secondFlip).isFalse();
        }

        @Test
        @DisplayName("publishing v2 leaves the AVAILABLE v1 entry unchanged")
        void publishingHigherVersionLeavesLowerAvailable() {
            storeAndClaim("order", 1, OrderV1Contract.class);
            storeAndClaim("order", 2, OrderV2Contract.class);

            store.markActive("order", 1);
            assertThat(store.get("order", 1).get().status().get()).isEqualTo(ActivationStatus.AVAILABLE);

            store.markActive("order", 2);
            assertThat(store.get("order", 2).get().status().get()).isEqualTo(ActivationStatus.AVAILABLE);
            assertThat(store.get("order", 1).get().status().get()).isEqualTo(ActivationStatus.AVAILABLE);
        }

        @Test
        @DisplayName("markActive on unknown key throws WorkflowDefinitionException")
        void markActiveOnUnknownKeyThrows() {
            assertThatThrownBy(() -> store.markActive("nonexistent", 1))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("no entry found");
        }

        @Test
        @DisplayName("publishing a lower version when a higher one is AVAILABLE leaves both AVAILABLE")
        void publishingLowerVersionWhenHigherAlreadyAvailableLeavesBothAvailable() {
            storeAndClaim("order", 1, OrderV1Contract.class);
            storeAndClaim("order", 2, OrderV2Contract.class);

            // Publish v2 first
            store.markActive("order", 2);
            assertThat(store.get("order", 2).get().status().get()).isEqualTo(ActivationStatus.AVAILABLE);

            // Then publish v1 — a version does not depend on any other version's status
            boolean flipped = store.markActive("order", 1);
            assertThat(flipped).isTrue();
            assertThat(store.get("order", 1).get().status().get()).isEqualTo(ActivationStatus.AVAILABLE);
            assertThat(store.get("order", 2).get().status().get()).isEqualTo(ActivationStatus.AVAILABLE);
        }
    }

    // --- Concurrent publication ---

    @Nested
    @DisplayName("concurrent markActive for distinct versions")
    class ConcurrentMarkActiveTests {

        @Test
        @DisplayName("8 threads publishing distinct versions — every version AVAILABLE, no exceptions")
        @SuppressWarnings("unchecked")
        void eightDistinctVersionsConcurrentlyPublished() throws Exception {
            // Pre-load 8 distinct versions (v1..v8) and claim all for publication
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

            // After all threads complete: every version is AVAILABLE
            for (int v = 1; v <= numVersions; v++) {
                assertThat(store.get("order", v).orElseThrow().status().get())
                        .as("version %d", v)
                        .isEqualTo(ActivationStatus.AVAILABLE);
            }
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

            // The entry is in PUBLISHING state
            assertThat(store.get("order", 1).get().status().get()).isEqualTo(ActivationStatus.PUBLISHING);
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

            // Publish after the snapshot was taken — should not change the snapshot
            store.tryClaimForActivation("order", 1);
            store.markActive("order", 1);
            assertThat(snapshot.iterator().next().status()).isEqualTo(ActivationStatus.CANDIDATE);

            // A fresh list() call sees the updated status
            Collection<CompiledDefinitionRef> fresh = store.list();
            assertThat(fresh.iterator().next().status()).isEqualTo(ActivationStatus.AVAILABLE);
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

    // --- Durable metadata delivery ---

    @Nested
    @DisplayName("delivered AVAILABLE metadata")
    class DeliveredMetadataTests {

        @Test
        @DisplayName("an AVAILABLE version 2 leaves the cached AVAILABLE version 1 alone")
        void availableSuccessorLeavesCachedPredecessorAvailable() {
            // Given versions 1 and 2 of one id, version 1 delivered as AVAILABLE
            store.storeCandidate(compiled("order", 1, OrderV1Contract.class));
            store.storeCandidate(compiled("order", 2, OrderV2Contract.class));
            store.metadata(availableRecord("order", 1));
            assertThat(store.currentStatus("order", 1)).isEqualTo(ActivationStatus.AVAILABLE);

            // When version 2 is delivered as AVAILABLE
            store.metadata(availableRecord("order", 2));

            // Then both versions are AVAILABLE
            assertThat(store.currentStatus("order", 1)).isEqualTo(ActivationStatus.AVAILABLE);
            assertThat(store.currentStatus("order", 2)).isEqualTo(ActivationStatus.AVAILABLE);
        }

        private WorkflowDefinitionRecord availableRecord(String id, long version) {
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            return new WorkflowDefinitionRecord(
                    new DefinitionKey(WorkflowScopes.DEFAULT, id, version),
                    DefinitionKind.DOCUMENT,
                    "example.Contract",
                    "example.State",
                    "a".repeat(64),
                    DefinitionStatus.AVAILABLE,
                    new DefinitionProvenance("test", null, null, null, null, now),
                    "YAML",
                    now,
                    now);
        }
    }

    // --- Legacy public methods keep their pre-existing outcomes for invalid input ---

    @Nested
    @DisplayName("legacy string and long methods with ids or versions the key type rejects")
    class LegacyInvalidInputTests {

        @Test
        @DisplayName("get with a blank id is empty")
        void getWithBlankIdIsEmpty() {
            assertThat(store.get("", 1)).isEmpty();
        }

        @Test
        @DisplayName("currentStatus with version 0 is null")
        void currentStatusWithVersionZeroIsNull() {
            assertThat(store.currentStatus("x", 0)).isNull();
        }

        @Test
        @DisplayName("tryClaimForActivation with version 0 reports the definition as not found")
        void tryClaimWithVersionZeroReportsNotFound() {
            assertThatThrownBy(() -> store.tryClaimForActivation("x", 0))
                    .isExactlyInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("resetToCandidate with version 0 does nothing")
        void resetToCandidateWithVersionZeroDoesNothing() {
            assertThatCode(() -> store.resetToCandidate("x", 0)).doesNotThrowAnyException();
        }
    }
}
