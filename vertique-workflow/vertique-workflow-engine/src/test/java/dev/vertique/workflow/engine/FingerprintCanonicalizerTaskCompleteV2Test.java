// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.tasks.TaskAssignment;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying the cycle-5 {@code task-complete} fingerprint schema {@code v: 2}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Shape: the canonical JSON carries {@code "v": 2} and {@code "reviewedSubjectVersion"}.</li>
 *   <li>Differential: different {@code reviewedSubjectVersion} values produce different
 *       fingerprints.</li>
 *   <li>Null serialization: {@code reviewedSubjectVersion=null} produces JSON {@code null} (not
 *       absent); null and non-null values are distinct fingerprints. (Empty-string
 *       reviewedSubjectVersion is rejected at the {@code TaskCompletionCommand} boundary, so
 *       the canonicalizer never observes blank values via the public API.)</li>
 *   <li>Reassignment unaffected: the {@code task-reassign} fingerprint still emits {@code v: 1}
 *       and does NOT include {@code reviewedSubjectVersion}.</li>
 * </ul>
 *
 * <p>No database required; all assertions are on {@link FingerprintCanonicalizer} output.
 */
class FingerprintCanonicalizerTaskCompleteV2Test {

    // --- Fixture setup ---

    private static final UUID TASK_ID = UUID.randomUUID();
    private static final FingerprintCanonicalizer CANON = new FingerprintCanonicalizer();

    /**
     * Canonical {@link ObjectMapper} built with the same settings as {@link FingerprintCanonicalizer}
     * so tests can reconstruct the expected canonical JSON bytes for direct field assertions.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN, true);

    private static TaskCompletionCommand completion(String reviewedSubjectVersion) {
        return new TaskCompletionCommand(
                TASK_ID,
                "approve",
                null,
                "key-" + UUID.randomUUID(),
                new WorkflowActor.User("alice"),
                reviewedSubjectVersion);
    }

    private static TaskCompletionCommand completionFixed(
            UUID taskId, String decision, Object payload, String key, WorkflowActor by, String reviewedSubjectVersion) {
        return new TaskCompletionCommand(taskId, decision, payload, key, by, reviewedSubjectVersion);
    }

    // =========================================================================
    // Shape: v2 + reviewedSubjectVersion in canonical JSON
    // =========================================================================

    /**
     * Tests verifying the schema version and field presence in the canonical JSON.
     */
    @Nested
    @DisplayName("v2 shape")
    class V2Shape {

        @Test
        @DisplayName("canonical JSON includes \"v\": 2 and \"reviewedSubjectVersion\" field")
        void canonicalJsonContainsV2AndReviewedSubjectVersion() throws Exception {
            // Build the expected canonical envelope the same way FingerprintCanonicalizer does.
            String fp = CANON.fingerprintCompletion(completion("v3"));

            // Re-derive the canonical JSON to inspect field values.
            // Build the same envelope manually and verify the canonical bytes contain the expected fields.
            var envelope = new java.util.LinkedHashMap<String, Object>();
            envelope.put("v", 2);
            envelope.put("kind", "task-complete");
            envelope.put("decisionName", "approve");
            envelope.put("payload", com.fasterxml.jackson.databind.node.NullNode.getInstance());
            var actor = new java.util.LinkedHashMap<String, Object>();
            actor.put("kind", "USER");
            actor.put("value", "alice");
            envelope.put("completedBy", actor);
            envelope.put("reviewedSubjectVersion", "v3");

            byte[] expectedBytes = MAPPER.writeValueAsBytes(envelope);
            String expectedJson = new String(expectedBytes, java.nio.charset.StandardCharsets.UTF_8);
            String expectedFp = sha256Hex(expectedBytes);

            // The canonical JSON must contain the v:2 marker and the reviewedSubjectVersion field.
            assertThat(expectedJson).contains("\"v\":2");
            assertThat(expectedJson).contains("\"reviewedSubjectVersion\":\"v3\"");

            // The fingerprint must match the independently computed digest.
            assertThat(fp).isEqualTo(expectedFp);
        }

        @Test
        @DisplayName("fingerprint is 64 lowercase hex chars for a v2 command")
        void fingerprintIs64HexChars() {
            String fp = CANON.fingerprintCompletion(completion("v3"));
            assertThat(fp).hasSize(64).matches("[0-9a-f]+");
        }
    }

    // =========================================================================
    // Differential: different reviewedSubjectVersion → different fingerprint
    // =========================================================================

    /**
     * Tests proving that different {@code reviewedSubjectVersion} values yield distinct fingerprints
     * for otherwise identical commands.
     */
    @Nested
    @DisplayName("differential — reviewedSubjectVersion participates in fingerprint")
    class DifferentialReviewedVersion {

        @Test
        @DisplayName("v3 vs v4 reviewedSubjectVersion produce different fingerprints")
        void differentReviewedVersionsProduceDifferentFingerprints() {
            UUID taskId = UUID.randomUUID();
            String idemKey = "key-diff-" + UUID.randomUUID();

            TaskCompletionCommand cmdV3 =
                    completionFixed(taskId, "approve", null, idemKey, new WorkflowActor.User("alice"), "v3");
            TaskCompletionCommand cmdV4 =
                    completionFixed(taskId, "approve", null, idemKey, new WorkflowActor.User("alice"), "v4");

            assertThat(CANON.fingerprintCompletion(cmdV3)).isNotEqualTo(CANON.fingerprintCompletion(cmdV4));
        }

        @Test
        @DisplayName("null vs non-null reviewedSubjectVersion produce different fingerprints")
        void nullVsNonNullReviewedVersionProducesDifferentFingerprints() {
            UUID taskId = UUID.randomUUID();
            String idemKey = "key-null-vs-nonnull-" + UUID.randomUUID();

            TaskCompletionCommand cmdNull =
                    completionFixed(taskId, "approve", null, idemKey, new WorkflowActor.User("alice"), null);
            TaskCompletionCommand cmdV0 =
                    completionFixed(taskId, "approve", null, idemKey, new WorkflowActor.User("alice"), "v0");

            assertThat(CANON.fingerprintCompletion(cmdNull)).isNotEqualTo(CANON.fingerprintCompletion(cmdV0));
        }

        // Note: the command-level API rejects blank reviewedSubjectVersion (see
        // TaskCompletionCommand's compact constructor). Empty-string vs. null at the command
        // boundary cannot occur; the canonicalizer treats null as JSON null deterministically.
    }

    // =========================================================================
    // Null serializes as JSON null (not absent)
    // =========================================================================

    /**
     * Tests proving that a {@code null} {@code reviewedSubjectVersion} serializes as JSON {@code null}
     * rather than being omitted, so two commands with everything else equal but different
     * {@code reviewedSubjectVersion} values never produce the same fingerprint.
     */
    @Nested
    @DisplayName("null serialization — reviewedSubjectVersion=null → JSON null, not absent")
    class NullSerialization {

        @Test
        @DisplayName("reviewedSubjectVersion=null produces canonical JSON with \"reviewedSubjectVersion\":null")
        void nullReviewedVersionSerializesAsJsonNull() throws Exception {
            // Build the expected envelope with null → NullNode (never omitted).
            var envelope = new java.util.LinkedHashMap<String, Object>();
            envelope.put("v", 2);
            envelope.put("kind", "task-complete");
            envelope.put("decisionName", "approve");
            envelope.put("payload", com.fasterxml.jackson.databind.node.NullNode.getInstance());
            var actor = new java.util.LinkedHashMap<String, Object>();
            actor.put("kind", "USER");
            actor.put("value", "alice");
            envelope.put("completedBy", actor);
            envelope.put("reviewedSubjectVersion", com.fasterxml.jackson.databind.node.NullNode.getInstance());

            byte[] expectedBytes = MAPPER.writeValueAsBytes(envelope);
            String expectedJson = new String(expectedBytes, java.nio.charset.StandardCharsets.UTF_8);
            String expectedFp = sha256Hex(expectedBytes);

            // Verify the canonical JSON for null contains "reviewedSubjectVersion":null (not absent).
            assertThat(expectedJson).contains("\"reviewedSubjectVersion\":null");

            // Fingerprint for null command must match the independently computed digest.
            // Use a fixed idempotency key to match the actor/decision in the envelope.
            UUID taskId = UUID.fromString(TASK_ID.toString());
            // We cannot directly verify the fingerprint byte-for-byte since the idemKey is not in
            // the envelope; instead we verify via the round-trip property: the fingerprint of two
            // identical null commands must match each other.
            TaskCompletionCommand cmdNull1 = completionFixed(
                    taskId, "approve", null, "fixed-key-null-ser", new WorkflowActor.User("alice"), null);
            TaskCompletionCommand cmdNull2 = completionFixed(
                    taskId, "approve", null, "fixed-key-null-ser", new WorkflowActor.User("alice"), null);

            assertThat(CANON.fingerprintCompletion(cmdNull1)).isEqualTo(CANON.fingerprintCompletion(cmdNull2));

            // And verify the computed expected fingerprint matches what CANON produces for the
            // same logical content. Build a TaskCompletionCommand with a FIXED idempotency key so
            // the envelope matches exactly — but note the idemKey is NOT part of the envelope.
            // The fingerprint covers: v, kind, decisionName, payload, completedBy, reviewedSubjectVersion.
            // (idempotencyKey is intentionally excluded — it is the dedup row's primary key, not
            //  part of command identity.)
            assertThat(CANON.fingerprintCompletion(cmdNull1)).isEqualTo(expectedFp);
        }

        @Test
        @DisplayName("null vs non-null reviewedSubjectVersion produce different fingerprints")
        void nullVsNonNullProduceDifferentFingerprints() {
            UUID taskId = UUID.randomUUID();
            String idemKey = "key-null-vs-v0-" + UUID.randomUUID();

            TaskCompletionCommand cmdNull =
                    completionFixed(taskId, "approve", null, idemKey, new WorkflowActor.User("alice"), null);
            TaskCompletionCommand cmdV0 =
                    completionFixed(taskId, "approve", null, idemKey, new WorkflowActor.User("alice"), "v0");

            // Empty-string and other blanks are rejected at the command boundary
            // (TaskCompletionCommand compact constructor). The canonicalizer therefore
            // sees only null or non-blank strings; null and "v0" must hash differently.
            assertThat(CANON.fingerprintCompletion(cmdNull)).isNotEqualTo(CANON.fingerprintCompletion(cmdV0));
        }
    }

    // =========================================================================
    // Reassignment fingerprint unaffected: stays at v: 1, no reviewedSubjectVersion
    // =========================================================================

    /**
     * Tests proving that the reassignment fingerprint was NOT changed by cycle 5 — it still emits
     * {@code v: 1} and does not include {@code reviewedSubjectVersion}.
     */
    @Nested
    @DisplayName("reassignment fingerprint unaffected — stays v1, no reviewedSubjectVersion")
    class ReassignmentUnaffected {

        @Test
        @DisplayName("reassignment fingerprint canonical JSON contains \"v\":1 and no reviewedSubjectVersion")
        void reassignmentFingerprintIsV1AndHasNoReviewedVersion() throws Exception {
            TaskReassignmentCommand cmd = new TaskReassignmentCommand(
                    TASK_ID,
                    new TaskAssignment.Role("compliance"),
                    new WorkflowActor.User("manager"),
                    "key-reassign-" + UUID.randomUUID(),
                    "needs specialist");

            // Build the expected envelope for the reassignment fingerprint.
            var envelope = new java.util.LinkedHashMap<String, Object>();
            envelope.put("v", 1);
            envelope.put("kind", "task-reassign");
            var assignment = new java.util.LinkedHashMap<String, Object>();
            assignment.put("kind", "ROLE");
            assignment.put("value", "compliance");
            envelope.put("newAssignment", assignment);
            var actor = new java.util.LinkedHashMap<String, Object>();
            actor.put("kind", "USER");
            actor.put("value", "manager");
            envelope.put("reassignedBy", actor);
            envelope.put("reason", "needs specialist");

            byte[] expectedBytes = MAPPER.writeValueAsBytes(envelope);
            String expectedJson = new String(expectedBytes, java.nio.charset.StandardCharsets.UTF_8);
            String expectedFp = sha256Hex(expectedBytes);

            // v: 1 must be present.
            assertThat(expectedJson).contains("\"v\":1");

            // reviewedSubjectVersion must NOT appear in the reassignment envelope.
            assertThat(expectedJson).doesNotContain("reviewedSubjectVersion");

            // Fingerprint must match independently computed digest.
            assertThat(CANON.fingerprintReassignment(cmd)).isEqualTo(expectedFp);
        }

        @Test
        @DisplayName("reassignment fingerprint is 64 hex chars and deterministic")
        void reassignmentFingerprintIsDeterministic() {
            TaskReassignmentCommand cmd = new TaskReassignmentCommand(
                    TASK_ID,
                    new TaskAssignment.Queue("q1"),
                    new WorkflowActor.Service("svc"),
                    "key-rdet-" + UUID.randomUUID(),
                    null);

            String fp1 = CANON.fingerprintReassignment(cmd);
            String fp2 = CANON.fingerprintReassignment(cmd);

            assertThat(fp1).hasSize(64).matches("[0-9a-f]+");
            assertThat(fp1).isEqualTo(fp2);
        }
    }

    // --- Private helpers ---

    /**
     * Computes lowercase hex SHA-256 of the given byte array.
     *
     * @param bytes input bytes
     * @return 64-character lowercase hex string
     * @throws Exception if SHA-256 is unavailable
     */
    private static String sha256Hex(byte[] bytes) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
