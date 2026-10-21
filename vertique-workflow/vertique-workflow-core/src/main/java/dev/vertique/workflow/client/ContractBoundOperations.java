// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.ops.WorkflowView;
import io.vertx.core.Future;
import java.util.Objects;

/**
 * Hands a client's calls to the workflow operations with the contract the client was created for.
 *
 * <p>Signals and queries are forwarded to the contract-aware overloads with the bound contract name, so
 * an operations binding that checks contracts can refuse a client whose contract differs from the one
 * the addressed instance is pinned to. Every other call, and a signal or query that already names a
 * contract, is forwarded unchanged. Generated and reflective proxies receive the same wrapper, so
 * neither names a contract itself.
 */
final class ContractBoundOperations implements WorkflowOperations {

    private final WorkflowOperations delegate;
    private final String contractName;

    /**
     * Creates the wrapper.
     *
     * @param delegate the operations the calls are forwarded to
     * @param contractName logical name of the contract of the client the wrapper serves
     */
    ContractBoundOperations(WorkflowOperations delegate, String contractName) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.contractName = Objects.requireNonNull(contractName, "contractName");
    }

    @Override
    public Future<WorkflowInstanceId> start(StartCommand cmd) {
        return delegate.start(cmd);
    }

    @Override
    public Future<Void> signal(WorkflowInstanceId id, String signalName, Object payload, String signalDedupKey) {
        return delegate.signal(id, contractName, signalName, payload, signalDedupKey);
    }

    @Override
    public Future<Void> signal(
            WorkflowInstanceId id,
            String callerContractName,
            String signalName,
            Object payload,
            String signalDedupKey) {
        return delegate.signal(id, callerContractName, signalName, payload, signalDedupKey);
    }

    @Override
    public Future<Void> cancel(WorkflowInstanceId id, String reason) {
        return delegate.cancel(id, reason);
    }

    @Override
    public Future<Void> retry(WorkflowInstanceId id) {
        return delegate.retry(id);
    }

    @Override
    public Future<WorkflowView> query(WorkflowInstanceId id) {
        return delegate.query(id, contractName);
    }

    @Override
    public Future<WorkflowView> query(WorkflowInstanceId id, String callerContractName) {
        return delegate.query(id, callerContractName);
    }

    @Override
    public Future<Void> migrate(WorkflowInstanceId id, long targetVersion) {
        return delegate.migrate(id, targetVersion);
    }
}
