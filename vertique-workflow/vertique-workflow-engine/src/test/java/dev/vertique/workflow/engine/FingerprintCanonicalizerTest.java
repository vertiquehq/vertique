// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.tasks.TaskAssignment;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FingerprintCanonicalizer}.
 *
 * <p>Verifies determinism (same command → same fingerprint), canonical ordering (record field order
 * and map insertion order do not affect the output), and discriminator sensitivity (any meaningful
 * difference flips the fingerprint). No database required.
 */
class FingerprintCanonicalizerTest {

    // --- Fixture setup ---

    /** Two records with the same logical content but different field declaration order. */
    record AlphaFirst(int a, int b) {}

    record BetaFirst(int b, int a) {}

    private static final UUID TASK_ID = UUID.randomUUID();
    private static final FingerprintCanonicalizer CANON = new FingerprintCanonicalizer();

    private static TaskCompletionCommand completion(
            UUID taskId, String decision, Object payload, String key, WorkflowActor by) {
        return new TaskCompletionCommand(taskId, decision, payload, key, by, null);
    }

    private static TaskReassignmentCommand reassignment(
            UUID taskId, TaskAssignment assignment, WorkflowActor by, String key, String reason) {
        return new TaskReassignmentCommand(taskId, assignment, by, key, reason);
    }

    // =========================================================================
    // Completion — determinism
    // =========================================================================

    @Nested
    @DisplayName("completion determinism")
    class CompletionDeterminism {

        @Test
        @DisplayName("identical TaskCompletionCommand produces identical fingerprint on two calls")
        void identicalCompletionCommandProducesIdenticalFingerprint() {
            TaskCompletionCommand cmd =
                    completion(TASK_ID, "approve", "some-payload", "idem-1", new WorkflowActor.User("alice"));
            String fp1 = CANON.fingerprintCompletion(cmd);
            String fp2 = CANON.fingerprintCompletion(cmd);
            assertThat(fp1).isEqualTo(fp2);
        }

        @Test
        @DisplayName("completion fingerprint is 64 hex characters")
        void completionFingerprintIs64HexChars() {
            TaskCompletionCommand cmd = completion(TASK_ID, "approve", "v", "k", new WorkflowActor.User("alice"));
            String fp = CANON.fingerprintCompletion(cmd);
            assertThat(fp).hasSize(64).matches("[0-9a-f]+");
        }
    }

    // =========================================================================
    // Reassignment — determinism
    // =========================================================================

    @Nested
    @DisplayName("reassignment determinism")
    class ReassignmentDeterminism {

        @Test
        @DisplayName("identical TaskReassignmentCommand produces identical fingerprint on two calls")
        void identicalReassignmentCommandProducesIdenticalFingerprint() {
            TaskReassignmentCommand cmd = reassignment(
                    TASK_ID, new TaskAssignment.Role("compliance"), new WorkflowActor.User("mgr"), "idem-r", "reason");
            String fp1 = CANON.fingerprintReassignment(cmd);
            String fp2 = CANON.fingerprintReassignment(cmd);
            assertThat(fp1).isEqualTo(fp2);
        }

        @Test
        @DisplayName("reassignment fingerprint is 64 hex characters")
        void reassignmentFingerprintIs64HexChars() {
            TaskReassignmentCommand cmd =
                    reassignment(TASK_ID, new TaskAssignment.Queue("q"), new WorkflowActor.Service("svc"), "k", null);
            String fp = CANON.fingerprintReassignment(cmd);
            assertThat(fp).hasSize(64).matches("[0-9a-f]+");
        }
    }

    // =========================================================================
    // Canonical ordering — record field order is irrelevant
    // =========================================================================

    @Nested
    @DisplayName("canonical ordering — record field order")
    class RecordFieldOrderCanonical {

        @Test
        @DisplayName("record payloads with same content but different field order produce same fingerprint")
        void recordPayloadFieldOrderDoesNotAffectFingerprint() {
            // AlphaFirst(a=1, b=2) vs BetaFirst(b=2, a=1) — logically identical after
            // alphabetical-property sorting.
            AlphaFirst payloadA = new AlphaFirst(1, 2);
            BetaFirst payloadB = new BetaFirst(2, 1);

            TaskCompletionCommand cmdA =
                    completion(TASK_ID, "decide", payloadA, "k-rec-a", new WorkflowActor.User("alice"));
            TaskCompletionCommand cmdB =
                    completion(TASK_ID, "decide", payloadB, "k-rec-b", new WorkflowActor.User("alice"));

            assertThat(CANON.fingerprintCompletion(cmdA)).isEqualTo(CANON.fingerprintCompletion(cmdB));
        }
    }

    // =========================================================================
    // Canonical ordering — Map key insertion order is irrelevant
    // =========================================================================

    @Nested
    @DisplayName("canonical ordering — map insertion order")
    class MapKeyOrderCanonical {

        @Test
        @DisplayName("Map payload with insertion order [a,b,c] vs [c,b,a] produces same fingerprint")
        void mapInsertionOrderDoesNotAffectFingerprint() {
            Map<String, String> mapABC = new LinkedHashMap<>();
            mapABC.put("a", "1");
            mapABC.put("b", "2");
            mapABC.put("c", "3");

            Map<String, String> mapCBA = new LinkedHashMap<>();
            mapCBA.put("c", "3");
            mapCBA.put("b", "2");
            mapCBA.put("a", "1");

            TaskCompletionCommand cmdABC =
                    completion(TASK_ID, "decide", mapABC, "k-map-abc", new WorkflowActor.User("alice"));
            TaskCompletionCommand cmdCBA =
                    completion(TASK_ID, "decide", mapCBA, "k-map-cba", new WorkflowActor.User("alice"));

            assertThat(CANON.fingerprintCompletion(cmdABC)).isEqualTo(CANON.fingerprintCompletion(cmdCBA));
        }
    }

    // =========================================================================
    // Completion discriminator sensitivity
    // =========================================================================

    @Nested
    @DisplayName("completion discriminator sensitivity")
    class CompletionDiscriminatorSensitivity {

        @Test
        @DisplayName("different decisionName flips completion fingerprint")
        void differentDecisionNameFlipsFingerprint() {
            TaskCompletionCommand cmd1 = completion(TASK_ID, "approve", "p", "k", new WorkflowActor.User("alice"));
            TaskCompletionCommand cmd2 = completion(TASK_ID, "reject", "p", "k", new WorkflowActor.User("alice"));
            assertThat(CANON.fingerprintCompletion(cmd1)).isNotEqualTo(CANON.fingerprintCompletion(cmd2));
        }

        @Test
        @DisplayName("different payload value flips completion fingerprint")
        void differentPayloadValueFlipsFingerprint() {
            TaskCompletionCommand cmd1 = completion(TASK_ID, "approve", "payload-a", "k", new WorkflowActor.User("u"));
            TaskCompletionCommand cmd2 = completion(TASK_ID, "approve", "payload-b", "k", new WorkflowActor.User("u"));
            assertThat(CANON.fingerprintCompletion(cmd1)).isNotEqualTo(CANON.fingerprintCompletion(cmd2));
        }

        @Test
        @DisplayName("completedBy User vs Service (same value) flips completion fingerprint")
        void differentActorKindFlipsCompletionFingerprint() {
            TaskCompletionCommand cmd1 = completion(TASK_ID, "approve", "p", "k", new WorkflowActor.User("alice"));
            TaskCompletionCommand cmd2 = completion(TASK_ID, "approve", "p", "k", new WorkflowActor.Service("alice"));
            assertThat(CANON.fingerprintCompletion(cmd1)).isNotEqualTo(CANON.fingerprintCompletion(cmd2));
        }

        @Test
        @DisplayName("different completedBy value flips completion fingerprint")
        void differentActorValueFlipsCompletionFingerprint() {
            TaskCompletionCommand cmd1 = completion(TASK_ID, "approve", "p", "k", new WorkflowActor.User("alice"));
            TaskCompletionCommand cmd2 = completion(TASK_ID, "approve", "p", "k", new WorkflowActor.User("bob"));
            assertThat(CANON.fingerprintCompletion(cmd1)).isNotEqualTo(CANON.fingerprintCompletion(cmd2));
        }
    }

    // =========================================================================
    // Reassignment discriminator sensitivity
    // =========================================================================

    @Nested
    @DisplayName("reassignment discriminator sensitivity")
    class ReassignmentDiscriminatorSensitivity {

        @Test
        @DisplayName("different newAssignment.kind flips reassignment fingerprint")
        void differentNewAssignmentKindFlipsFingerprint() {
            TaskReassignmentCommand cmd1 =
                    reassignment(TASK_ID, new TaskAssignment.User("alice"), new WorkflowActor.User("mgr"), "k", null);
            TaskReassignmentCommand cmd2 =
                    reassignment(TASK_ID, new TaskAssignment.Role("alice"), new WorkflowActor.User("mgr"), "k", null);
            assertThat(CANON.fingerprintReassignment(cmd1)).isNotEqualTo(CANON.fingerprintReassignment(cmd2));
        }

        @Test
        @DisplayName("different newAssignment.value flips reassignment fingerprint")
        void differentNewAssignmentValueFlipsFingerprint() {
            TaskReassignmentCommand cmd1 = reassignment(
                    TASK_ID, new TaskAssignment.Role("compliance"), new WorkflowActor.User("mgr"), "k", null);
            TaskReassignmentCommand cmd2 =
                    reassignment(TASK_ID, new TaskAssignment.Role("legal"), new WorkflowActor.User("mgr"), "k", null);
            assertThat(CANON.fingerprintReassignment(cmd1)).isNotEqualTo(CANON.fingerprintReassignment(cmd2));
        }

        @Test
        @DisplayName("different reassignedBy flips reassignment fingerprint")
        void differentReassignedByFlipsFingerprint() {
            TaskReassignmentCommand cmd1 =
                    reassignment(TASK_ID, new TaskAssignment.Role("r"), new WorkflowActor.User("mgr1"), "k", null);
            TaskReassignmentCommand cmd2 =
                    reassignment(TASK_ID, new TaskAssignment.Role("r"), new WorkflowActor.User("mgr2"), "k", null);
            assertThat(CANON.fingerprintReassignment(cmd1)).isNotEqualTo(CANON.fingerprintReassignment(cmd2));
        }
    }

    // =========================================================================
    // Reason normalization
    // =========================================================================

    @Nested
    @DisplayName("reason normalization")
    class ReasonNormalization {

        @Test
        @DisplayName("reason=null, reason='', and reason='   ' all produce the SAME reassignment fingerprint")
        void blankReasonNormalizedToNull() {
            // The TaskReassignmentCommand compact constructor normalizes blank reason to null.
            TaskReassignmentCommand cmdNull =
                    reassignment(TASK_ID, new TaskAssignment.Role("r"), new WorkflowActor.User("mgr"), "k", null);
            TaskReassignmentCommand cmdEmpty =
                    reassignment(TASK_ID, new TaskAssignment.Role("r"), new WorkflowActor.User("mgr"), "k", "");
            TaskReassignmentCommand cmdBlanks =
                    reassignment(TASK_ID, new TaskAssignment.Role("r"), new WorkflowActor.User("mgr"), "k", "   ");

            String fpNull = CANON.fingerprintReassignment(cmdNull);
            String fpEmpty = CANON.fingerprintReassignment(cmdEmpty);
            String fpBlanks = CANON.fingerprintReassignment(cmdBlanks);

            assertThat(fpEmpty).isEqualTo(fpNull);
            assertThat(fpBlanks).isEqualTo(fpNull);
        }

        @Test
        @DisplayName("reason='needs work' produces a DIFFERENT fingerprint than the null/blank cases")
        void nonBlankReasonDiffersFromNull() {
            TaskReassignmentCommand cmdNull =
                    reassignment(TASK_ID, new TaskAssignment.Role("r"), new WorkflowActor.User("mgr"), "k", null);
            TaskReassignmentCommand cmdReal = reassignment(
                    TASK_ID, new TaskAssignment.Role("r"), new WorkflowActor.User("mgr"), "k", "needs work");

            assertThat(CANON.fingerprintReassignment(cmdReal)).isNotEqualTo(CANON.fingerprintReassignment(cmdNull));
        }
    }
}
