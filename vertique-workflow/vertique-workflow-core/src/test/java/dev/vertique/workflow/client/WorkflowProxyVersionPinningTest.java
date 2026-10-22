// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowStart;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the definition version a client starts is decided by the client's binding alone:
 * a client without a version selection leaves {@link StartCommand#requestedDefinitionVersion()}
 * {@code null} so the engine resolves the current version, and a client built with
 * {@link WorkflowClientBuilder#version(long)} pins exactly that version.
 */
class WorkflowProxyVersionPinningTest {

    // --- Fixture types ---

    record VersionedPayload(String key) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return key;
        }
    }

    /** Contract whose annotation carries a definition id and version that no client may use. */
    @WorkflowContract
    interface VersionedContract {
        @WorkflowStart
        Future<WorkflowInstanceId> start(VersionedPayload payload);
    }

    private RecordingWorkflowOperations ops;
    private WorkflowClientFactory factory;

    @BeforeEach
    void setUp() {
        ops = new RecordingWorkflowOperations();
        factory = new WorkflowClientFactory(ops, new DefaultWorkflowRegistry());
    }

    // --- Tests ---

    @Test
    @DisplayName("a client without a version selection starts the current version")
    void unpinnedClientLeavesVersionOpen() {
        factory.create(VersionedContract.class, "versioned-saga").start(new VersionedPayload("key-0"));

        StartCommand cmd = ops.starts().get(0);
        assertThat(cmd.definitionId()).isEqualTo("versioned-saga");
        assertThat(cmd.requestedDefinitionVersion())
                .as("no version selection means the engine resolves the current version")
                .isNull();
    }

    @Test
    @DisplayName("a client pinned to version 1 threads requestedDefinitionVersion=1 into the StartCommand")
    void clientPinnedToVersionOneThreadsVersionOne() {
        factory.client(VersionedContract.class, "versioned-saga")
                .version(1)
                .create()
                .start(new VersionedPayload("key-1"));

        assertThat(ops.starts().get(0).requestedDefinitionVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a client pinned to version 2 threads requestedDefinitionVersion=2 into the StartCommand")
    void clientPinnedToVersionTwoThreadsVersionTwo() {
        factory.client(VersionedContract.class, "versioned-saga")
                .version(2)
                .create()
                .start(new VersionedPayload("key-2"));

        assertThat(ops.starts().get(0).requestedDefinitionVersion()).isEqualTo(2L);
    }
}
