// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dedup;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Canonical, length-bounded encoders for {@code workflow_dedup} scopes/keys (PRD-WF-002 §A.4.4
 * and §D6). Lives in {@code workflow-core} so both {@code workflow-postgresql} (engine + repos)
 * and {@code workflow-services} ({@code WorkflowSignalContributor}) can call the same encoders
 * without crossing module boundaries.
 *
 * <p>Encodings:
 * <ul>
 *   <li><b>signal</b> — instance scope is {@code workflowId.toString()} (UUID, 36 chars). The
 *       key is the caller's {@code dedupKey} for non-branch signals (back-compat with the
 *       cycle-2 form), or a 64-char SHA-256 hex digest of the
 *       {@code (forkStepId, branchId, dedupKey)} tuple for branch signals (FR-WF-PAR-026).</li>
 *   <li><b>dispatch</b> (PRD-WF-002 D6) — kind {@code 'dispatch'} (8 chars; fits the existing
 *       {@code workflow_dedup.kind VARCHAR(16)} column). Scope is {@code workflowId.toString()}
 *       (36 chars). Key is a 64-char SHA-256 hex digest of
 *       {@code (forkStepId, branchId, stepId, targetId, operation)}. The engine inserts the
 *       dedup row in {@code BranchTransitionEngine} BEFORE invoking {@code RecorderRouter}; on
 *       conflict the dispatch is treated as already-recorded and not re-emitted
 *       (FR-WF-PAR-038).</li>
 *   <li><b>correlated</b> — kind {@code 'correlated'}. The namespace is a 64-char SHA-256 hex digest of
 *       {@code (contract name, signal name, scope or empty)} and the key a 64-char digest of the caller's
 *       dedup key, so one event is unique across instances and no name, scope or key is stored in clear.</li>
 *   <li><b>sourceInboxId</b> — the inbox-message-id of one target of a signal source: the 64-char SHA-256 hex
 *       digest of {@code (contract name, signal name, scope or empty, message dedup key)}. It holds neither
 *       the resolved workflow or key, nor the declaration position of the target, nor the name of the
 *       source, so a replay of the same message meets the same identity.</li>
 *   <li><b>signalInboxId</b> — composite id used by {@code WorkflowSignalContributor} for the
 *       inbox-message-id ({@code inbox.message_id VARCHAR(255)}). For non-branch signals it
 *       reproduces today's {@code workflowId + ":" + dedupKey} string so existing inbox rows
 *       remain unique. For branch signals it concatenates {@code workflowId:forkStepId:branchId:
 *       dedupKey}, hashing the suffix when the natural composite would exceed 255 chars.</li>
 * </ul>
 *
 * <p>All encoders use ASCII unit-separator ({@code 0x1F}) between fields so payloads with embedded
 * colons cannot collide.
 */
public final class WorkflowDedupScopes {

    /** Persisted {@code workflow_dedup.kind} value for branch service-dispatch idempotency. */
    public static final String DISPATCH_KIND = "dispatch";

    /** Inbox-message-id length budget; matches {@code inbox.message_id VARCHAR(255)}. */
    public static final int INBOX_MESSAGE_ID_MAX_LENGTH = 255;

    /** ASCII unit-separator (0x1F) used between hashed fields. */
    private static final char US = (char) 0x1F;

    private WorkflowDedupScopes() {}

    // --- Signal scopes ---

    /**
     * Returns the {@code workflow_dedup.dedup_namespace} string for a signal claim.
     *
     * @param workflowId the parent workflow instance id
     * @return {@code workflowId.toString()} (36 chars)
     */
    public static String signalScope(WorkflowInstanceId workflowId) {
        return workflowId.value().toString();
    }

    /**
     * Returns the {@code workflow_dedup.key} string for a signal claim.
     *
     * <p>For non-branch signals (both {@code forkStepId} and {@code branchId} null), returns
     * {@code dedupKey} unchanged so existing single-path entries remain compatible. For branch
     * signals the key is a 64-char SHA-256 hex digest of {@code (forkStepId, branchId, dedupKey)}
     * so two sibling branches sharing a {@code dedupKey} do not collide (FR-WF-PAR-026).
     *
     * @param dedupKey caller-supplied dedup key
     * @param forkStepId optional fork step id (paired with {@code branchId})
     * @param branchId optional branch id (paired with {@code forkStepId})
     * @return the dedup-key string
     * @throws IllegalArgumentException if exactly one of {@code forkStepId}/{@code branchId} is
     *     supplied (mixing is not allowed)
     */
    public static String signalKey(String dedupKey, @Nullable String forkStepId, @Nullable String branchId) {
        requireBothOrNeither(forkStepId, branchId);
        if (forkStepId == null) {
            return dedupKey;
        }
        return sha256Hex(forkStepId, branchId, dedupKey);
    }

    /**
     * Returns the inbox-message-id used by {@code WorkflowSignalContributor}.
     *
     * <p>Reproduces today's {@code workflowId + ":" + dedupKey} for non-branch signals (so
     * existing inbox rows remain stable). For branch signals the composite is
     * {@code workflowId:forkStepId:branchId:dedupKey}; if that string would exceed
     * {@link #INBOX_MESSAGE_ID_MAX_LENGTH}, the suffix portion is replaced with a 64-char
     * SHA-256 hex digest.
     *
     * @param workflowId the parent workflow instance id
     * @param forkStepId optional fork step id
     * @param branchId optional branch id
     * @param dedupKey caller-supplied dedup key
     * @return a string that fits {@code inbox.message_id VARCHAR(255)}
     */
    public static String signalInboxId(
            WorkflowInstanceId workflowId, @Nullable String forkStepId, @Nullable String branchId, String dedupKey) {
        requireBothOrNeither(forkStepId, branchId);
        String wfPrefix = workflowId.value().toString();
        if (forkStepId == null) {
            return wfPrefix + ":" + dedupKey;
        }
        String composite = wfPrefix + ":" + forkStepId + ":" + branchId + ":" + dedupKey;
        if (composite.length() <= INBOX_MESSAGE_ID_MAX_LENGTH) {
            return composite;
        }
        // Hash the branch + dedup suffix; the workflow id remains visible for forensics.
        return wfPrefix + ":" + sha256Hex(forkStepId, branchId, dedupKey);
    }

    // --- Dispatch scopes ---

    /**
     * Returns the {@code workflow_dedup.dedup_namespace} string for a branch service-dispatch claim.
     *
     * @param workflowId the parent workflow instance id
     * @return {@code workflowId.toString()} (36 chars)
     */
    public static String dispatchScope(WorkflowInstanceId workflowId) {
        return workflowId.value().toString();
    }

    /**
     * Returns the {@code workflow_dedup.key} string for a branch service-dispatch claim. Always a
     * 64-char SHA-256 hex digest so the value fits {@code workflow_dedup.key VARCHAR(255)}
     * regardless of input lengths.
     *
     * @param forkStepId fork step id; non-null
     * @param branchId branch id; non-null
     * @param stepId branch step id; non-null
     * @param targetId service target id; non-null
     * @param operation operation name; non-null
     * @return 64-char SHA-256 hex digest
     */
    public static String dispatchKey(
            String forkStepId, String branchId, String stepId, String targetId, String operation) {
        return sha256Hex(forkStepId, branchId, stepId, targetId, operation);
    }

    // --- Correlated scopes ---

    /**
     * Returns the {@code workflow_dedup.dedup_namespace} string for a correlated signal claim: the 64-char SHA-256 hex
     * digest of {@code (contractName, signalName, scope)}, with the empty string for an absent scope. The namespace
     * holds no instance id, so one event is unique across instances, and no name or scope in clear.
     *
     * @param contractName the logical name of the contract the signal is addressed to
     * @param signalName the name of the signal
     * @param scope the scope the delivery is narrowed to, or {@code null} when it serves every scope
     * @return 64-char SHA-256 hex digest
     */
    public static String correlatedScope(String contractName, String signalName, @Nullable String scope) {
        return sha256Hex(contractName, signalName, scope == null ? "" : scope);
    }

    /**
     * Returns the {@code workflow_dedup.key} string for a correlated signal claim: the 64-char SHA-256 hex digest of
     * the dedup key, so a key of any length fits and no key is stored in clear.
     *
     * @param dedupKey the caller-supplied dedup key
     * @return 64-char SHA-256 hex digest
     */
    public static String correlatedKey(String dedupKey) {
        return sha256Hex(dedupKey);
    }

    // --- Source inbox identity ---

    /**
     * Returns the inbox-message-id of one target of a signal source: the 64-char SHA-256 hex digest of
     * {@code (contractName, signalName, scope, dedupKey)}, with the empty string for an absent scope. It fits
     * {@code inbox.message_id VARCHAR(255)} whatever the lengths, and holds no name, scope or key in clear. It
     * leaves out the instance or key the target resolved to, the declaration position of the target and the name of
     * the source, so a message replayed from a dead-letter topic, or read again after a restart, meets the same
     * identity as its first delivery.
     *
     * @param contractName the logical name of the contract the signal is addressed to
     * @param signalName the name of the signal
     * @param scope the scope the delivery is narrowed to, or {@code null} when it serves every scope
     * @param dedupKey the message's dedup key
     * @return 64-char SHA-256 hex digest
     */
    public static String sourceInboxId(
            String contractName, String signalName, @Nullable String scope, String dedupKey) {
        return sha256Hex(contractName, signalName, scope == null ? "" : scope, dedupKey);
    }

    // --- Helpers ---

    private static void requireBothOrNeither(@Nullable String forkStepId, @Nullable String branchId) {
        if ((forkStepId == null) != (branchId == null)) {
            throw new IllegalArgumentException("forkStepId and branchId must be supplied together; got forkStepId="
                    + forkStepId + ", branchId=" + branchId);
        }
    }

    private static String sha256Hex(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) {
                    digest.update((byte) US);
                }
                digest.update(fields[i].getBytes(StandardCharsets.UTF_8));
            }
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
