// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the PRD-WF-002 persistence state types: {@link BranchToken},
 * {@link JoinState}, {@link BranchStatus}, {@link JoinStateStatus}, {@link JoinPolicyType}, and
 * {@link BranchTokenFilter}.
 *
 * <p>Verifies record invariants, the {@link BranchToken} {@code with*} updaters, and the
 * {@link BranchTokenFilter#all()} factory.
 */
class BranchTokenJoinStateTest {

    private static final WorkflowInstanceId WF = new WorkflowInstanceId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-05-10T00:00:00Z");

    private static BranchToken sampleToken() {
        return new BranchToken(
                UUID.randomUUID(),
                WF,
                "fork",
                "a",
                "step-a",
                BranchStatus.RUNNING,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                3,
                null,
                null,
                null,
                null,
                0L,
                NOW,
                NOW,
                DurableMetadata.empty());
    }

    @Nested
    @DisplayName("BranchToken")
    class BranchTokenTest {

        @Test
        @DisplayName("rejects negative attemptCount")
        void rejectsNegativeAttempts() {
            assertThatThrownBy(() -> new BranchToken(
                            UUID.randomUUID(),
                            WF,
                            "fork",
                            "a",
                            "step",
                            BranchStatus.RUNNING,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            -1,
                            3,
                            null,
                            null,
                            null,
                            null,
                            0L,
                            NOW,
                            NOW,
                            DurableMetadata.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects zero maxAttempts")
        void rejectsZeroMaxAttempts() {
            assertThatThrownBy(() -> new BranchToken(
                            UUID.randomUUID(),
                            WF,
                            "fork",
                            "a",
                            "step",
                            BranchStatus.RUNNING,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            0,
                            0,
                            null,
                            null,
                            null,
                            null,
                            0L,
                            NOW,
                            NOW,
                            DurableMetadata.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("withStep advances current step + version + updatedAt")
        void withStepAdvances() {
            BranchToken before = sampleToken();
            Instant later = NOW.plusSeconds(5);
            BranchToken after = before.withStep("step-b", before.version() + 1, later);
            assertThat(after.currentStepId()).isEqualTo("step-b");
            assertThat(after.version()).isEqualTo(before.version() + 1);
            assertThat(after.updatedAt()).isEqualTo(later);
            assertThat(after.createdAt()).isEqualTo(before.createdAt());
            assertThat(after.status()).isEqualTo(before.status());
        }

        @Test
        @DisplayName("withStatus updates status + version")
        void withStatusUpdates() {
            BranchToken before = sampleToken();
            BranchToken after = before.withStatus(BranchStatus.COMPLETED, before.version() + 1, NOW);
            assertThat(after.status()).isEqualTo(BranchStatus.COMPLETED);
            assertThat(after.version()).isEqualTo(before.version() + 1);
        }

        @Test
        @DisplayName("withWait sets WAITING status + wait fields")
        void withWaitSets() {
            BranchToken before = sampleToken();
            UUID auxId = UUID.randomUUID();
            BranchToken after =
                    before.withWait(WaitType.SIGNAL, "inventory-reserved", auxId, before.version() + 1, NOW);
            assertThat(after.status()).isEqualTo(BranchStatus.WAITING);
            assertThat(after.waitType()).isEqualTo(WaitType.SIGNAL);
            assertThat(after.waitKey()).isEqualTo("inventory-reserved");
            assertThat(after.waitAuxId()).isEqualTo(auxId);
        }

        @Test
        @DisplayName("withClearedWait sets status + currentStepId, clears wait fields and nextRetryAt, "
                + "increments version, preserves identity + retry budget + error metadata")
        void withClearedWaitClearsAndAdvances() {
            // Build a token that has every field that should be cleared by withClearedWait set.
            UUID id = UUID.randomUUID();
            UUID auxId = UUID.randomUUID();
            Instant created = NOW.minusSeconds(60);
            Instant lastError = NOW.minusSeconds(30);
            Instant nextRetry = NOW.plusSeconds(5);
            BranchToken before = new BranchToken(
                    id,
                    WF,
                    "fork",
                    "a",
                    "wait-step",
                    BranchStatus.WAITING,
                    WaitType.SIGNAL,
                    "inventory-reserved",
                    auxId,
                    "{\"branchResult\":42}",
                    "BadInput",
                    "boom",
                    1,
                    3,
                    nextRetry,
                    "TransientFailure",
                    "transient boom",
                    lastError,
                    7L,
                    created,
                    NOW.minusSeconds(10),
                    DurableMetadata.empty());

            Instant later = NOW.plusSeconds(1);
            BranchToken after = before.withClearedWait(BranchStatus.RUNNING, "next-step", later);

            // Status + step advance.
            assertThat(after.status()).isEqualTo(BranchStatus.RUNNING);
            assertThat(after.currentStepId()).isEqualTo("next-step");

            // Wait fields cleared.
            assertThat(after.waitType()).isNull();
            assertThat(after.waitKey()).isNull();
            assertThat(after.waitAuxId()).isNull();

            // nextRetryAt cleared unconditionally — once the branch is no longer
            // RETRY_SCHEDULED, retaining a retry timestamp is stale state.
            assertThat(after.nextRetryAt()).isNull();

            // Version + updatedAt advance.
            assertThat(after.version()).isEqualTo(before.version() + 1);
            assertThat(after.updatedAt()).isEqualTo(later);

            // Identity + retry budget + error history + result preserved.
            assertThat(after.id()).isEqualTo(id);
            assertThat(after.workflowId()).isEqualTo(WF);
            assertThat(after.forkStepId()).isEqualTo("fork");
            assertThat(after.branchId()).isEqualTo("a");
            assertThat(after.attemptCount()).isEqualTo(1);
            assertThat(after.maxAttempts()).isEqualTo(3);
            assertThat(after.resultJson()).isEqualTo("{\"branchResult\":42}");
            assertThat(after.errorType()).isEqualTo("BadInput");
            assertThat(after.errorMessage()).isEqualTo("boom");
            assertThat(after.lastErrorType()).isEqualTo("TransientFailure");
            assertThat(after.lastErrorMessage()).isEqualTo("transient boom");
            assertThat(after.lastErrorAt()).isEqualTo(lastError);
            assertThat(after.createdAt()).isEqualTo(created);
        }

        @Test
        @DisplayName("withClearedWait works for terminal-status transitions (CANCELLED, SUPERSEDED)")
        void withClearedWaitTerminalStatuses() {
            BranchToken before = sampleToken();
            BranchToken cancelled = before.withClearedWait(BranchStatus.CANCELLED, before.currentStepId(), NOW);
            assertThat(cancelled.status()).isEqualTo(BranchStatus.CANCELLED);
            assertThat(cancelled.waitType()).isNull();
            assertThat(cancelled.nextRetryAt()).isNull();

            BranchToken superseded = before.withClearedWait(BranchStatus.SUPERSEDED, before.currentStepId(), NOW);
            assertThat(superseded.status()).isEqualTo(BranchStatus.SUPERSEDED);
            assertThat(superseded.waitType()).isNull();
            assertThat(superseded.nextRetryAt()).isNull();
        }
    }

    @Nested
    @DisplayName("JoinState")
    class JoinStateTest {

        @Test
        @DisplayName("constructs with required fields")
        void constructsWithRequiredFields() {
            JoinState s = new JoinState(
                    WF, "fork", "join", JoinPolicyType.ALL_REQUIRED, JoinStateStatus.OPEN, null, null, 0L, NOW, NOW);
            assertThat(s.policy()).isEqualTo(JoinPolicyType.ALL_REQUIRED);
            assertThat(s.status()).isEqualTo(JoinStateStatus.OPEN);
            assertThat(s.winningBranchId()).isNull();
            assertThat(s.decidedAt()).isNull();
        }

        @Test
        @DisplayName("rejects null status")
        void rejectsNullStatus() {
            assertThatThrownBy(() -> new JoinState(
                            WF, "fork", "join", JoinPolicyType.ALL_REQUIRED, null, null, null, 0L, NOW, NOW))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("BranchTokenFilter")
    class BranchTokenFilterTest {

        @Test
        @DisplayName("all() returns an unrestricted filter")
        void allReturnsEmptyFilter() {
            BranchTokenFilter f = BranchTokenFilter.all();
            assertThat(f.status()).isNull();
            assertThat(f.waitType()).isNull();
            assertThat(f.forkStepId()).isNull();
        }

        @Test
        @DisplayName("constructed filter retains its components")
        void constructedFilterRetainsComponents() {
            BranchTokenFilter f = new BranchTokenFilter(BranchStatus.RUNNING, WaitType.SIGNAL, "fork");
            assertThat(f.status()).isEqualTo(BranchStatus.RUNNING);
            assertThat(f.waitType()).isEqualTo(WaitType.SIGNAL);
            assertThat(f.forkStepId()).isEqualTo("fork");
        }
    }
}
