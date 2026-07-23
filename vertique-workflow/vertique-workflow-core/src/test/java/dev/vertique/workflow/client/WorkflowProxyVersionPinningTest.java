// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowStart;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import io.vertx.core.Future;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies that {@link WorkflowClientFactory} proxies thread the contract's
 * {@link WorkflowContract#definitionVersion()} through as
 * {@link StartCommand#requestedDefinitionVersion()} (Slice L / FR-WF-DEF-069).
 *
 * <p>Both v1 and v2 of the same definition are registered so that a v2-annotated proxy can be
 * created. After activating (registering) v2, a proxy bound to the v1 contract must still
 * produce a {@link StartCommand} carrying {@code requestedDefinitionVersion=1}, not {@code 2}.
 * Without the {@link WorkflowProxyValidator} fix, the proxy would pass {@code null} and the
 * engine would silently start the current (v2) version instead.
 */
class WorkflowProxyVersionPinningTest {

    // --- Fixture types ---

    record VersionedPayload(String key) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return key;
        }
    }

    record VersionedState(String key) {}

    /** Contract bound to v1 of the definition. */
    @WorkflowContract(definitionId = "versioned-saga", definitionVersion = 1)
    interface VersionedContractV1 {
        @WorkflowStart
        Future<WorkflowInstanceId> start(VersionedPayload payload);
    }

    /** Contract bound to v2 of the definition — distinct class required by the registry. */
    @WorkflowContract(definitionId = "versioned-saga", definitionVersion = 2)
    interface VersionedContractV2 {
        @WorkflowStart
        Future<WorkflowInstanceId> start(VersionedPayload payload);
    }

    // --- Registry helpers ---

    /** Registers v1 of the versioned-saga definition. */
    private static void registerV1(DefaultWorkflowRegistry registry) {
        registry.register(new WorkflowDefinition<VersionedState, VersionedContractV1>() {
            @Override
            public Class<VersionedContractV1> contract() {
                return VersionedContractV1.class;
            }

            @Override
            public Class<VersionedState> stateType() {
                return VersionedState.class;
            }

            @Override
            public String definitionId() {
                return "versioned-saga";
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<VersionedState> wf) {
                wf.init(VersionedPayload.class, p -> new VersionedState(p.key()))
                        .initialStep("done")
                        .complete("done");
            }
        });
    }

    /** Registers v2 of the versioned-saga definition. */
    private static void registerV2(DefaultWorkflowRegistry registry) {
        registry.register(new WorkflowDefinition<VersionedState, VersionedContractV2>() {
            @Override
            public Class<VersionedContractV2> contract() {
                return VersionedContractV2.class;
            }

            @Override
            public Class<VersionedState> stateType() {
                return VersionedState.class;
            }

            @Override
            public String definitionId() {
                return "versioned-saga";
            }

            @Override
            public long definitionVersion() {
                return 2L;
            }

            @Override
            public void define(WorkflowBuilder<VersionedState> wf) {
                wf.init(VersionedPayload.class, p -> new VersionedState(p.key()))
                        .initialStep("done")
                        .complete("done");
            }
        });
    }

    private DefaultWorkflowRegistry registry;
    private WorkflowOperations ops;

    @BeforeEach
    void setUp() {
        registry = new DefaultWorkflowRegistry();
        registerV1(registry);
        registerV2(registry);
        ops = mock(WorkflowOperations.class);
    }

    // --- Tests ---

    /**
     * A proxy bound to v1 must produce {@code requestedDefinitionVersion=1} even when v2 is also
     * registered (and therefore the registry's current version). Without the
     * {@link WorkflowProxyValidator} fix the proxy would pass {@code null} and the engine would
     * silently start v2.
     */
    @Test
    @DisplayName("proxy for v1 contract threads requestedDefinitionVersion=1 into StartCommand")
    void proxyV1ThreadsVersionOneIntoStartCommand() {
        WorkflowInstanceId fakeId = new WorkflowInstanceId(UUID.randomUUID());
        when(ops.start(any())).thenReturn(Future.succeededFuture(fakeId));

        WorkflowClientFactory factory = new WorkflowClientFactory(ops, registry);
        VersionedContractV1 proxy = factory.create(VersionedContractV1.class);
        proxy.start(new VersionedPayload("key-1"));

        ArgumentCaptor<StartCommand> captor = forClass(StartCommand.class);
        verify(ops).start(captor.capture());

        StartCommand cmd = captor.getValue();
        assertThat(cmd.definitionId()).isEqualTo("versioned-saga");
        assertThat(cmd.requestedDefinitionVersion())
                .as("proxy must thread the contract's definitionVersion=1 into StartCommand")
                .isEqualTo(1L);
    }

    /**
     * A proxy bound to v2 must produce {@code requestedDefinitionVersion=2}.
     */
    @Test
    @DisplayName("proxy for v2 contract threads requestedDefinitionVersion=2 into StartCommand")
    void proxyV2ThreadsVersionTwoIntoStartCommand() {
        WorkflowInstanceId fakeId = new WorkflowInstanceId(UUID.randomUUID());
        when(ops.start(any())).thenReturn(Future.succeededFuture(fakeId));

        WorkflowClientFactory factory = new WorkflowClientFactory(ops, registry);
        VersionedContractV2 proxy = factory.create(VersionedContractV2.class);
        proxy.start(new VersionedPayload("key-2"));

        ArgumentCaptor<StartCommand> captor = forClass(StartCommand.class);
        verify(ops).start(captor.capture());

        StartCommand cmd = captor.getValue();
        assertThat(cmd.requestedDefinitionVersion())
                .as("proxy must thread the contract's definitionVersion=2 into StartCommand")
                .isEqualTo(2L);
    }
}
