// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkflowDedupScopes}.
 *
 * <p>Verifies the length-bounded encodings the persistence layer relies on (PRD-WF-002 finding
 * R2-3): dispatch keys and branch signal keys are 64-char SHA-256 hex strings; the inbox-message
 * id stays within {@link WorkflowDedupScopes#INBOX_MESSAGE_ID_MAX_LENGTH}; non-branch inbox ids
 * remain identical to today's {@code workflowId + ":" + dedupKey}; {@code dispatch} kind fits
 * {@code workflow_dedup.kind VARCHAR(16)}.
 */
class WorkflowDedupScopesTest {

    private static final WorkflowInstanceId WF =
            new WorkflowInstanceId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Nested
    @DisplayName("dispatch")
    class DispatchTests {

        @Test
        @DisplayName("kind fits the existing VARCHAR(16) column")
        void kindFitsKindColumn() {
            assertThat(WorkflowDedupScopes.DISPATCH_KIND).hasSizeLessThanOrEqualTo(16);
        }

        @Test
        @DisplayName("scope is the workflow id (UUID, 36 chars)")
        void scopeIsWorkflowId() {
            assertThat(WorkflowDedupScopes.dispatchScope(WF))
                    .isEqualTo(WF.value().toString())
                    .hasSize(36);
        }

        @Test
        @DisplayName("key is a 64-char SHA-256 hex digest")
        void keyIs64Char() {
            String k = WorkflowDedupScopes.dispatchKey("fork", "a", "step-a", "svc.foo", "reserve");
            assertThat(k).hasSize(64).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("different inputs produce different keys")
        void distinctInputsDistinctKeys() {
            String a = WorkflowDedupScopes.dispatchKey("fork", "a", "step", "svc", "op");
            String b = WorkflowDedupScopes.dispatchKey("fork", "b", "step", "svc", "op");
            String c = WorkflowDedupScopes.dispatchKey("fork", "a", "step2", "svc", "op");
            assertThat(a).isNotEqualTo(b).isNotEqualTo(c);
        }

        @Test
        @DisplayName("encoding is deterministic across runs")
        void deterministic() {
            String a = WorkflowDedupScopes.dispatchKey("fork", "a", "step", "svc", "op");
            String b = WorkflowDedupScopes.dispatchKey("fork", "a", "step", "svc", "op");
            assertThat(a).isEqualTo(b);
        }
    }

    @Nested
    @DisplayName("signalKey")
    class SignalKeyTests {

        @Test
        @DisplayName("non-branch signal returns the dedupKey unchanged (back-compat)")
        void nonBranchUnchanged() {
            assertThat(WorkflowDedupScopes.signalKey("inv-1", null, null)).isEqualTo("inv-1");
        }

        @Test
        @DisplayName("branch signal returns a 64-char SHA-256 hex digest")
        void branchReturnsHash() {
            String k = WorkflowDedupScopes.signalKey("inv-1", "fork", "a");
            assertThat(k).hasSize(64).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("siblings with the same dedupKey produce distinct branch keys")
        void siblingsDistinct() {
            String a = WorkflowDedupScopes.signalKey("payment-authorized", "fork", "a");
            String b = WorkflowDedupScopes.signalKey("payment-authorized", "fork", "b");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("mixing forkStepId/branchId is rejected")
        void mixedRejected() {
            assertThatThrownBy(() -> WorkflowDedupScopes.signalKey("k", "fork", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> WorkflowDedupScopes.signalKey("k", null, "a"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("signalInboxId")
    class SignalInboxIdTests {

        @Test
        @DisplayName("non-branch inbox id remains workflowId + ':' + dedupKey")
        void nonBranchPreservesLegacyForm() {
            String id = WorkflowDedupScopes.signalInboxId(WF, null, null, "inv-1");
            assertThat(id).isEqualTo(WF.value().toString() + ":inv-1");
        }

        @Test
        @DisplayName("branch inbox id includes branch identity when within length budget")
        void branchUnhashedWhenShort() {
            String id = WorkflowDedupScopes.signalInboxId(WF, "fork", "a", "inv-1");
            assertThat(id).isEqualTo(WF.value().toString() + ":fork:a:inv-1");
        }

        @Test
        @DisplayName("branch inbox id falls back to hashed suffix when above length budget")
        void branchHashedWhenLong() {
            String longKey = "x".repeat(300);
            String id = WorkflowDedupScopes.signalInboxId(WF, "fork", "a", longKey);
            assertThat(id).hasSizeLessThanOrEqualTo(WorkflowDedupScopes.INBOX_MESSAGE_ID_MAX_LENGTH);
            assertThat(id).startsWith(WF.value().toString() + ":");
        }

        @Test
        @DisplayName("siblings with same dedupKey produce distinct inbox ids")
        void siblingsDistinct() {
            String a = WorkflowDedupScopes.signalInboxId(WF, "fork", "a", "inv-1");
            String b = WorkflowDedupScopes.signalInboxId(WF, "fork", "b", "inv-1");
            assertThat(a).isNotEqualTo(b);
        }
    }
}
