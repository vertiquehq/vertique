// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.contract.BusinessKey;
import dev.vertique.workflow.contract.BusinessKeyed;
import dev.vertique.workflow.contract.IdempotencyKey;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.SignalDedupKeyed;
import dev.vertique.workflow.contract.SubjectRef;
import dev.vertique.workflow.contract.SubjectReferenced;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowQuery;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.contract.WorkflowStart;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import io.vertx.core.Future;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WorkflowClientFactory} creates proxies that correctly route method calls
 * to {@link WorkflowOperations}, source idempotency/dedup/business keys correctly, and behave
 * correctly for {@link Object} methods.
 *
 * <p>Each contract in this test has exactly one {@code @WorkflowStart} method as required by the
 * proxy validator. Tests that exercise different start-method shapes use separate contract
 * interfaces.
 */
class WorkflowClientFactoryTest {

    // --- Fixture types ---

    record OrderState(String id) {}

    record StartPayloadWithInterface(String orderId) implements IdempotencyKeyed, BusinessKeyed, SubjectReferenced {
        @Override
        public String idempotencyKey() {
            return orderId;
        }

        @Override
        public String businessKey() {
            return "bk-" + orderId;
        }

        @Override
        public WorkflowSubjectRef subjectRef() {
            return new WorkflowSubjectRef("Order", orderId, null);
        }
    }

    record SignalPayloadWithInterface(String data) implements SignalDedupKeyed {
        @Override
        public String dedupKey() {
            return "dedup-" + data;
        }
    }

    // --- Contract fixtures (one @WorkflowStart each) ---

    /**
     * Primary contract: start with {@code IdempotencyKeyed} payload, two signals, one query.
     * Used by signal, query, and object-method tests.
     */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface SampleContract {
        /** Start with IdempotencyKeyed payload. */
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayloadWithInterface payload);

        /** Signal with SignalDedupKeyed payload. */
        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayloadWithInterface payload);

        /** Signal with @SignalDedupKey parameter. */
        @WorkflowSignal("order.shipped")
        Future<Void> ship(WorkflowInstanceId id, SignalPayloadWithInterface payload, @SignalDedupKey String dedupKey);

        /** Query. */
        @WorkflowQuery("view")
        Future<WorkflowView> getView(WorkflowInstanceId id);
    }

    /**
     * Contract used to test {@code @IdempotencyKey} parameter overrides the interface.
     */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface KeyedStartContract {
        /** Start with @IdempotencyKey parameter. */
        @WorkflowStart
        Future<WorkflowInstanceId> startWithKey(@IdempotencyKey String key, StartPayloadWithInterface payload);

        /** Signal (required for definition with a signal node). */
        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayloadWithInterface payload);

        /** Query. */
        @WorkflowQuery("view")
        Future<WorkflowView> getView(WorkflowInstanceId id);
    }

    /**
     * Contract used to test {@code @BusinessKey} and {@code @SubjectRef} parameter overrides.
     */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface FullStartContract {
        /** Start with @BusinessKey and @SubjectRef parameters (override interface). */
        @WorkflowStart
        Future<WorkflowInstanceId> startFull(
                StartPayloadWithInterface payload,
                @BusinessKey String businessKey,
                @SubjectRef WorkflowSubjectRef subjectRef);

        /** Signal (required for definition with a signal node). */
        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayloadWithInterface payload);

        /** Query. */
        @WorkflowQuery("view")
        Future<WorkflowView> getView(WorkflowInstanceId id);
    }

    // --- Shared setup helper ---

    /**
     * Registers the shared "order-saga" definition for a given registry.
     *
     * <p>The definition includes two {@code waitFor} signal nodes so signal-name validation
     * passes for all three contract variants.
     *
     * @param registry the registry to populate
     * @param contract the contract class to link the definition to
     * @param <C> the contract type
     */
    private static <C> void registerDefinition(DefaultWorkflowRegistry registry, Class<C> contract) {
        registry.register(new WorkflowDefinition<OrderState, C>() {
            @Override
            public Class<C> contract() {
                return contract;
            }

            @Override
            public Class<OrderState> stateType() {
                return OrderState.class;
            }

            @Override
            public String definitionId() {
                return "order-saga";
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<OrderState> wf) {
                wf.init(StartPayloadWithInterface.class, p -> new OrderState(p.orderId()))
                        .initialStep("charge")
                        .dispatch("charge", "payment-svc", s -> new Object(), "wait-confirm")
                        .waitFor(
                                "wait-confirm",
                                "order.confirmed",
                                SignalPayloadWithInterface.class,
                                (s, p) -> s,
                                "wait-shipped")
                        .waitFor("wait-shipped", "order.shipped", SignalPayloadWithInterface.class, (s, p) -> s, "done")
                        .complete("done");
            }
        });
    }

    private DefaultWorkflowRegistry registry;
    private WorkflowOperations ops;
    private WorkflowClientFactory factory;

    @BeforeEach
    void setUp() {
        registry = new DefaultWorkflowRegistry();
        registerDefinition(registry, SampleContract.class);
        ops = mock(WorkflowOperations.class);
        factory = new WorkflowClientFactory(ops, registry);
    }

    // --- Tests ---

    @Nested
    @DisplayName("@WorkflowStart routing")
    class StartRouting {

        @Test
        @DisplayName("start() with IdempotencyKeyed payload delegates to ops.start() with correct idempotencyKey")
        void startWithIdempotencyKeyedPayload() {
            WorkflowInstanceId instanceId = new WorkflowInstanceId(UUID.randomUUID());
            when(ops.start(argThat(cmd -> cmd.idempotencyKey().equals("order-123"))))
                    .thenReturn(Future.succeededFuture(instanceId));

            SampleContract proxy = factory.create(SampleContract.class);
            StartPayloadWithInterface payload = new StartPayloadWithInterface("order-123");
            Future<WorkflowInstanceId> result = proxy.start(payload);

            assertThat(result.result()).isEqualTo(instanceId);
            verify(ops).start(argThat(cmd -> {
                assertThat(cmd.idempotencyKey()).isEqualTo("order-123");
                assertThat(cmd.definitionId()).isEqualTo("order-saga");
                assertThat(cmd.businessKey()).isEqualTo("bk-order-123");
                assertThat(cmd.subjectRef()).isNotNull();
                assertThat(cmd.subjectRef().type()).isEqualTo("Order");
                return true;
            }));
        }

        @Test
        @DisplayName("startWithKey() uses @IdempotencyKey parameter over IdempotencyKeyed interface")
        void startWithIdempotencyKeyAnnotation() {
            // Use a separate registry+factory for the KeyedStartContract
            DefaultWorkflowRegistry keyedRegistry = new DefaultWorkflowRegistry();
            registerDefinition(keyedRegistry, KeyedStartContract.class);
            WorkflowClientFactory keyedFactory = new WorkflowClientFactory(ops, keyedRegistry);

            WorkflowInstanceId instanceId = new WorkflowInstanceId(UUID.randomUUID());
            when(ops.start(argThat(cmd -> cmd.idempotencyKey().equals("explicit-key"))))
                    .thenReturn(Future.succeededFuture(instanceId));

            KeyedStartContract proxy = keyedFactory.create(KeyedStartContract.class);
            StartPayloadWithInterface payload = new StartPayloadWithInterface("order-456");
            Future<WorkflowInstanceId> result = proxy.startWithKey("explicit-key", payload);

            assertThat(result.result()).isEqualTo(instanceId);
            verify(ops).start(argThat((StartCommand cmd) -> {
                assertThat(cmd.idempotencyKey()).isEqualTo("explicit-key");
                return true;
            }));
        }

        @Test
        @DisplayName("startFull() @BusinessKey and @SubjectRef parameters win over interface methods")
        void startWithAnnotationParamsOverrideInterface() {
            // Use a separate registry+factory for the FullStartContract
            DefaultWorkflowRegistry fullRegistry = new DefaultWorkflowRegistry();
            registerDefinition(fullRegistry, FullStartContract.class);
            WorkflowClientFactory fullFactory = new WorkflowClientFactory(ops, fullRegistry);

            WorkflowInstanceId instanceId = new WorkflowInstanceId(UUID.randomUUID());
            WorkflowSubjectRef explicitRef = new WorkflowSubjectRef("Invoice", "inv-1", "v2");
            when(ops.start(argThat(cmd -> "explicit-bk".equals(cmd.businessKey()))))
                    .thenReturn(Future.succeededFuture(instanceId));

            FullStartContract proxy = fullFactory.create(FullStartContract.class);
            StartPayloadWithInterface payload = new StartPayloadWithInterface("order-789");
            Future<WorkflowInstanceId> result = proxy.startFull(payload, "explicit-bk", explicitRef);

            assertThat(result.result()).isEqualTo(instanceId);
            verify(ops).start(argThat((StartCommand cmd) -> {
                assertThat(cmd.businessKey()).isEqualTo("explicit-bk");
                assertThat(cmd.subjectRef()).isEqualTo(explicitRef);
                return true;
            }));
        }
    }

    @Nested
    @DisplayName("@WorkflowSignal routing")
    class SignalRouting {

        @Test
        @DisplayName("confirm() with SignalDedupKeyed payload uses dedupKey() from interface")
        void signalWithDedupKeyedPayload() {
            WorkflowInstanceId instanceId = new WorkflowInstanceId(UUID.randomUUID());
            when(ops.signal(instanceId, "order.confirmed", null, "dedup-data-1"))
                    .thenReturn(Future.succeededFuture());

            SampleContract proxy = factory.create(SampleContract.class);
            SignalPayloadWithInterface signalPayload = new SignalPayloadWithInterface("data-1");
            proxy.confirm(instanceId, signalPayload);

            verify(ops).signal(instanceId, "order.confirmed", signalPayload, "dedup-data-1");
        }

        @Test
        @DisplayName("ship() @SignalDedupKey parameter wins over SignalDedupKeyed interface")
        void signalWithAnnotationDedupKey() {
            WorkflowInstanceId instanceId = new WorkflowInstanceId(UUID.randomUUID());
            when(ops.signal(instanceId, "order.shipped", null, "explicit-dedup"))
                    .thenReturn(Future.succeededFuture());

            SampleContract proxy = factory.create(SampleContract.class);
            SignalPayloadWithInterface signalPayload = new SignalPayloadWithInterface("data-2");
            proxy.ship(instanceId, signalPayload, "explicit-dedup");

            verify(ops).signal(instanceId, "order.shipped", signalPayload, "explicit-dedup");
        }
    }

    @Nested
    @DisplayName("@WorkflowQuery routing")
    class QueryRouting {

        @Test
        @DisplayName("getView() delegates to ops.query() with the correct WorkflowInstanceId")
        void queryDelegatesToOps() {
            WorkflowInstanceId instanceId = new WorkflowInstanceId(UUID.randomUUID());
            WorkflowView view = mock(WorkflowView.class);
            when(ops.query(instanceId)).thenReturn(Future.succeededFuture(view));

            SampleContract proxy = factory.create(SampleContract.class);
            Future<WorkflowView> result = proxy.getView(instanceId);

            assertThat(result.result()).isSameAs(view);
            verify(ops).query(instanceId);
        }
    }

    @Nested
    @DisplayName("Object method behaviour")
    class ObjectMethods {

        @Test
        @DisplayName("toString() returns 'WorkflowProxy[contract.getName()]'")
        void toStringReturnsProxyLabel() {
            SampleContract proxy = factory.create(SampleContract.class);

            assertThat(proxy.toString()).isEqualTo("WorkflowProxy[" + SampleContract.class.getName() + "]");
        }

        @Test
        @DisplayName("equals() is identity-based")
        void equalsIsIdentityBased() {
            SampleContract proxy1 = factory.create(SampleContract.class);
            SampleContract proxy2 = factory.create(SampleContract.class);

            assertThat(proxy1.equals(proxy1)).isTrue();
            assertThat(proxy1.equals(proxy2)).isFalse();
        }

        @Test
        @DisplayName("hashCode() is System.identityHashCode")
        void hashCodeIsIdentityHash() {
            SampleContract proxy = factory.create(SampleContract.class);

            assertThat(proxy.hashCode()).isEqualTo(System.identityHashCode(proxy));
        }
    }

    @Nested
    @DisplayName("validation at proxy creation")
    class ProxyCreationValidation {

        @Test
        @DisplayName("create() fails immediately for an invalid contract")
        void failsForInvalidContract() {
            interface NoAnnotation {
                @WorkflowStart
                Future<WorkflowInstanceId> start(StartPayloadWithInterface p);
            }
            assertThatThrownBy(() -> factory.create(NoAnnotation.class))
                    .isInstanceOf(dev.vertique.workflow.exception.WorkflowProxyContractException.class);
        }

        @Test
        @DisplayName("create() rejects a @WorkflowContract on a class (not an interface)")
        void failsForNonInterfaceContract() {
            assertThatThrownBy(() -> factory.create(NonInterfaceContract.class))
                    .isInstanceOf(dev.vertique.workflow.exception.WorkflowProxyContractException.class)
                    .hasMessageContaining("must be an interface");
        }
    }

    /** A @WorkflowContract on a class rather than an interface — must be rejected at create() time. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    abstract static class NonInterfaceContract {
        @WorkflowStart
        abstract Future<WorkflowInstanceId> start(StartPayloadWithInterface payload);
    }
}
