// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository SPI for the open wait records of instances whose signal waits declare a computed correlation key.
 *
 * <p>Generic over the transaction-handle type {@code TX} so the workflow engine can depend on this interface without
 * binding to a specific database client.
 *
 * <p>A record is written in the transaction that moves the instance into the wait, so a rolled-back segment leaves
 * none. A caller that changes a record holds the instance's execution lock.
 *
 * @param <TX> the transaction-handle type threaded through transactional operations
 */
public interface WorkflowSignalWaitRepository<TX> {

    // --- Writes ---

    /**
     * Writes a record. A record already held at the same address, that is the same instance, step, fork step and
     * branch, is replaced by this one, with its new {@link SignalWaitRecord#waitId() wait id}.
     *
     * @param wait the record to write
     * @param tx   the active transaction
     * @return a {@link Future} that completes when the record is written
     */
    Future<Void> write(SignalWaitRecord wait, TX tx);

    /**
     * Deletes the record held at an address, if there is one.
     *
     * <p>Deleting a record that is not there is not an error: an older node's cancel may have left a record, and a wait
     * entered again may have replaced one.
     *
     * @param instanceId the instance that owns the wait
     * @param stepId     the step of the wait
     * @param forkStepId the fork step of the branch, or the empty string for the main wait
     * @param branchId   the branch, or the empty string for the main wait
     * @param tx         the active transaction
     * @return a {@link Future} of {@code true} when a record was deleted, {@code false} when none was held
     */
    Future<Boolean> delete(WorkflowInstanceId instanceId, String stepId, String forkStepId, String branchId, TX tx);

    /**
     * Deletes every record of an instance, whatever its step, fork step or branch.
     *
     * @param instanceId the instance whose records are deleted
     * @param tx         the active transaction
     * @return a {@link Future} that completes when the records are deleted; it completes as well when there are none
     */
    Future<Void> deleteAll(WorkflowInstanceId instanceId, TX tx);

    // --- Reads ---

    /**
     * Reads the open records of a contract, a signal name and a key, whose instance has not ended, and returns at most
     * two of them, which is enough to tell no match, one match and several matches apart.
     *
     * @param contractName the logical name of the contract
     * @param signalName   the name of the signal the wait is for
     * @param key          the correlation key the wait computed
     * @param definitionId when non-null, only records of instances pinned to this definition id
     * @param scopeKey     when non-null, only records of instances started in this scope
     * @param tx           the active transaction
     * @return a {@link Future} of at most two records, empty when none is open
     */
    Future<List<SignalWaitRecord>> findOpen(
            String contractName,
            String signalName,
            String key,
            @Nullable String definitionId,
            @Nullable String scopeKey,
            TX tx);

    /**
     * Reads a record by its identity.
     *
     * @param waitId the wait id
     * @param tx     the active transaction
     * @return a {@link Future} of the record, or {@link Optional#empty()} when it is no longer held
     */
    Future<Optional<SignalWaitRecord>> findById(UUID waitId, TX tx);

    /**
     * Reads every record of an instance, whatever its step, fork step or branch.
     *
     * @param instanceId the instance
     * @param tx         the active transaction
     * @return a {@link Future} of the records the instance holds, empty when it holds none
     */
    Future<List<SignalWaitRecord>> findByInstance(WorkflowInstanceId instanceId, TX tx);
}
