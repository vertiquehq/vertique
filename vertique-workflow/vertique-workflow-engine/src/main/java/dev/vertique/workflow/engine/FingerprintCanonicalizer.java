// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.vertique.workflow.actor.WorkflowActorMaps;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes canonical SHA-256 fingerprints for task-mutation commands, used by the workflow engine
 * to detect idempotent retries versus conflicting re-submissions of the same idempotency key.
 *
 * <p>A fingerprint is a lowercase 64-character hex string (256-bit SHA-256 hash). Two commands are
 * considered logically identical if and only if their fingerprints match; a same-key re-submission
 * with a different fingerprint is rejected as a conflict.
 *
 * <p>Canonical JSON is produced with {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS},
 * {@link MapperFeature#SORT_PROPERTIES_ALPHABETICALLY}, and
 * {@link SerializationFeature#WRITE_BIGDECIMAL_AS_PLAIN} so the output is deterministic
 * regardless of field declaration order or insertion order.
 *
 * <p>This class is {@link Singleton}-scoped and safe for concurrent use; the internal
 * {@link ObjectMapper} is configured once at construction time and never mutated.
 */
@Singleton
final class FingerprintCanonicalizer {

    // --- Fields ---

    private final ObjectMapper mapper;

    // --- Constructor ---

    /**
     * Creates a {@code FingerprintCanonicalizer} with a new canonical {@link ObjectMapper}.
     *
     * <p>If the caller supplies a {@code null} mapper (e.g., in tests that do not wire a full DI
     * graph), a fresh mapper is constructed. The canonical settings are always applied on top of
     * whatever base mapper is provided, so the caller's mapper is NOT mutated.
     */
    @Inject
    FingerprintCanonicalizer() {
        this.mapper = buildCanonicalMapper();
    }

    // --- Public API ---

    /**
     * Computes a canonical SHA-256 fingerprint for a {@link TaskCompletionCommand}.
     *
     * <p>The fingerprint schema version is {@code v: 2} (cycle 5). It covers: version tag, kind
     * discriminator, decision name, decision payload (as canonical JSON or JSON {@code null}), the
     * completing actor's kind and value, and the {@code reviewedSubjectVersion} field (always
     * included; serialized as JSON {@code null} when the field is {@code null} — never omitted).
     *
     * <p>Including {@code reviewedSubjectVersion} unconditionally ensures that two submissions with
     * the same idempotency key but different reviewed versions produce different fingerprints, which
     * surfaces as {@link dev.vertique.workflow.exception.WorkflowIdempotencyConflictException} rather
     * than silently masking a different logical command.
     *
     * <p>The {@code v: 1} shape (cycle 3) did not include {@code reviewedSubjectVersion}. Rows
     * written under {@code v: 1} cannot be confused for {@code v: 2} retries; a version mismatch
     * causes a fingerprint mismatch, which surfaces as
     * {@link dev.vertique.workflow.exception.WorkflowIdempotencyConflictException}.
     *
     * @param cmd the completion command; must not be null
     * @return a 64-character lowercase hex SHA-256 fingerprint
     * @throws IllegalStateException if SHA-256 is unavailable or JSON serialization fails
     */
    String fingerprintCompletion(TaskCompletionCommand cmd) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("v", 2);
            envelope.put("kind", "task-complete");
            envelope.put("decisionName", cmd.decisionName());
            // Payload: null → JSON null; non-null → canonical JSON node
            if (cmd.payload() == null) {
                envelope.put("payload", NullNode.getInstance());
            } else {
                envelope.put("payload", mapper.valueToTree(cmd.payload()));
            }
            envelope.put("completedBy", WorkflowActorMaps.toMap(cmd.completedBy()));
            // reviewedSubjectVersion: always included (null → JSON null, never omitted).
            // Omitting it would make null and absent indistinguishable, collapsing two different
            // logical commands (null version vs. absent version) into the same fingerprint.
            if (cmd.reviewedSubjectVersion() == null) {
                envelope.put("reviewedSubjectVersion", NullNode.getInstance());
            } else {
                envelope.put("reviewedSubjectVersion", cmd.reviewedSubjectVersion());
            }
            byte[] canonicalBytes = mapper.writeValueAsBytes(envelope);
            return sha256Hex(canonicalBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute completion fingerprint: " + e.getMessage(), e);
        }
    }

    /**
     * Computes a canonical SHA-256 fingerprint for a {@link TaskReassignmentCommand}.
     *
     * <p>The fingerprint covers: version tag, kind discriminator, the new assignment (kind + value),
     * the reassigning actor (kind + value), and the normalized reason ({@code null} for blank
     * strings — normalization is already applied by the command's compact constructor).
     *
     * @param cmd the reassignment command; must not be null
     * @return a 64-character lowercase hex SHA-256 fingerprint
     * @throws IllegalStateException if SHA-256 is unavailable or JSON serialization fails
     */
    String fingerprintReassignment(TaskReassignmentCommand cmd) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("v", 1);
            envelope.put("kind", "task-reassign");
            envelope.put("newAssignment", WorkflowActorMaps.toMap(cmd.newAssignment()));
            envelope.put("reassignedBy", WorkflowActorMaps.toMap(cmd.reassignedBy()));
            // reason is already normalized to null for blank strings by the command record.
            envelope.put("reason", cmd.reason());
            byte[] canonicalBytes = mapper.writeValueAsBytes(envelope);
            return sha256Hex(canonicalBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute reassignment fingerprint: " + e.getMessage(), e);
        }
    }

    // --- Internal helpers ---

    /**
     * Computes the lowercase hex SHA-256 digest of the given byte array.
     *
     * @param bytes the input bytes to hash; must not be null
     * @return a 64-character lowercase hex string
     */
    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the JVM spec (java.security.MessageDigest); unreachable.
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    /**
     * Builds a canonical {@link ObjectMapper} with deterministic key ordering and plain BigDecimal
     * output. The resulting mapper is immutable (no further configuration is applied after creation).
     *
     * @return a configured canonical {@link ObjectMapper}
     */
    private static ObjectMapper buildCanonicalMapper() {
        return new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN, true);
    }
}
