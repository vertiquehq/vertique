// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import dev.vertique.workflow.exception.WorkflowProxyContractException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link WorkflowProxyValidator} against every validation rule in §8.3 of the
 * implementation plan. Each test covers either a happy path or exactly one failure case.
 */
class WorkflowProxyValidatorTest {

    // --- Shared state types ---

    record OrderState(String id) {}

    record StartPayload(String orderId) implements IdempotencyKeyed, BusinessKeyed, SubjectReferenced {
        @Override
        public String idempotencyKey() {
            return orderId;
        }

        @Override
        public String businessKey() {
            return orderId;
        }

        @Override
        public WorkflowSubjectRef subjectRef() {
            return new WorkflowSubjectRef("Order", orderId, null);
        }
    }

    record SignalPayload(String data) implements SignalDedupKeyed {
        @Override
        public String dedupKey() {
            return data;
        }
    }

    // --- Registry setup helper ---

    /** Registers a simple definition with two signals: "order.confirmed" and "order.shipped". */
    private static DefaultWorkflowRegistry buildRegistry() {
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(new WorkflowDefinition<OrderState, ValidContract>() {
            @Override
            public Class<ValidContract> contract() {
                return ValidContract.class;
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
                wf.init(StartPayload.class, p -> new OrderState(p.orderId()))
                        .initialStep("charge")
                        .dispatch("charge", "payment-svc", s -> new Object(), "wait-confirm")
                        .waitFor("wait-confirm", "order.confirmed", SignalPayload.class, (s, p) -> s, "wait-shipped")
                        .waitFor("wait-shipped", "order.shipped", SignalPayload.class, (s, p) -> s, "done")
                        .complete("done");
            }
        });
        return registry;
    }

    // --- Contract fixtures ---

    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface ValidContract {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayload payload);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);
    }

    /** Happy path: @IdempotencyKey parameter. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface ContractWithIdempotencyKeyParam {
        @WorkflowStart
        Future<WorkflowInstanceId> start(@IdempotencyKey String key, StartPayload payload);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayload payload);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);
    }

    /** Happy path: @BusinessKey parameter wins over BusinessKeyed interface. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface ContractWithBusinessKeyParam {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload, @BusinessKey String bk);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayload payload);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);
    }

    /** Happy path: @SubjectRef parameter wins over SubjectReferenced interface. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface ContractWithSubjectRefParam {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload, @SubjectRef WorkflowSubjectRef ref);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayload payload);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);
    }

    /** Happy path: @SignalDedupKey parameter. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface ContractWithSignalDedupKeyParam {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayload payload, @SignalDedupKey String dedupKey);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);
    }

    /** Failure: missing @WorkflowContract annotation. */
    interface NoContractAnnotation {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);
    }

    /** Failure: wrong return type on @WorkflowStart (not Future<WorkflowInstanceId>). */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface WrongStartReturnType {
        @WorkflowStart
        Future<String> start(StartPayload payload); // wrong: Future<String>
    }

    /** Failure: no idempotency key source. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface NoIdempotencyKeySource {
        record PlainPayload(String id) {} // does NOT implement IdempotencyKeyed

        @WorkflowStart
        Future<WorkflowInstanceId> start(PlainPayload payload); // no @IdempotencyKey, no interface

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayload payload);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);
    }

    /** Failure: wrong return type on @WorkflowSignal (not Future<Void>). */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface WrongSignalReturnType {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowSignal("order.confirmed")
        Future<String> confirm(WorkflowInstanceId id, SignalPayload payload); // wrong: Future<String>
    }

    /** Failure: signal name not in plan. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface SignalNameNotInPlan {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowSignal("nonexistent.signal")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayload payload);
    }

    /** Failure: no dedup key source on signal. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface NoDedupKeySource {
        record PlainSignalPayload(String data) {} // does NOT implement SignalDedupKeyed

        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, PlainSignalPayload payload); // no @SignalDedupKey
    }

    /** Failure: wrong parameter shape for @WorkflowQuery (extra parameter). */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface QueryWrongParamShape {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id, String extra); // wrong: extra param
    }

    /** Failure: @WorkflowQuery return type is a WorkflowView supertype (Future<Object>), not exact. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface QuerySupertypeReturn {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowQuery("view")
        Future<Object> query(WorkflowInstanceId id); // wrong: supertype of WorkflowView
    }

    /** Failure: @WorkflowQuery return type is a wildcard (Future<? extends WorkflowView>), not exact. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface QueryWildcardReturn {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowQuery("view")
        Future<? extends WorkflowView> query(WorkflowInstanceId id); // wrong: wildcard, not exact WorkflowView
    }

    /** Failure: @WorkflowStart return type is a wildcard (Future<? extends WorkflowInstanceId>), not exact. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface StartWildcardReturn {
        @WorkflowStart
        Future<? extends WorkflowInstanceId> start(StartPayload payload); // wrong: wildcard
    }

    /** Failure: @WorkflowContract on a class, not an interface — must be rejected like the codegen path does. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    abstract static class NotAnInterfaceContract {
        @WorkflowStart
        abstract Future<WorkflowInstanceId> start(StartPayload payload);
    }

    /** Failure: two @WorkflowSignal methods with same name. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface DuplicateSignalMethods {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm1(WorkflowInstanceId id, SignalPayload payload);

        @WorkflowSignal("order.confirmed") // duplicate signal name
        Future<Void> confirm2(WorkflowInstanceId id, SignalPayload payload);
    }

    /** Failure: method carries two conflicting role annotations. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface ConflictingAnnotations {
        @WorkflowStart
        @WorkflowSignal("order.confirmed") // conflict!
        Future<WorkflowInstanceId> startOrConfirm(StartPayload payload);
    }

    /** Failure: @WorkflowStart method has a WorkflowInstanceId parameter (forbidden). */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface StartWithInstanceId {
        @WorkflowStart
        Future<WorkflowInstanceId> start(WorkflowInstanceId id, StartPayload payload);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayload payload);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);
    }

    /** Failure: @WorkflowStart with more than one payload parameter. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface StartWithTwoPayloads {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload, StartPayload anotherPayload);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayload payload);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);
    }

    /** Failure: @WorkflowStart with duplicate @IdempotencyKey parameters. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface StartWithDuplicateIdempotencyKey {
        @WorkflowStart
        Future<WorkflowInstanceId> start(
                StartPayload payload, @IdempotencyKey String key1, @IdempotencyKey String key2);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayload payload);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);
    }

    /** Failure: @WorkflowSignal with no WorkflowInstanceId parameter. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface SignalWithNoInstanceId {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(SignalPayload payload); // no WorkflowInstanceId
    }

    /** Failure: @WorkflowSignal with two WorkflowInstanceId parameters. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface SignalWithTwoInstanceIds {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id1, WorkflowInstanceId id2, SignalPayload payload);
    }

    /** Failure: @WorkflowSignal with WorkflowInstanceId NOT at position 0. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface SignalWithInstanceIdNotFirst {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(SignalPayload payload, WorkflowInstanceId id); // id is second, not first

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);
    }

    /** Failure: @WorkflowSignal with duplicate @SignalDedupKey parameters. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface SignalWithDuplicateDedupKey {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(
                WorkflowInstanceId id, SignalPayload payload, @SignalDedupKey String k1, @SignalDedupKey String k2);
    }

    /** Failure: @WorkflowSignal with more than one payload parameter. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface SignalWithTwoPayloads {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayload p1, SignalPayload p2);
    }

    /**
     * Failure: @WorkflowStart with only annotated params and no payload — e.g. the method that
     * declares just an @IdempotencyKey String and no payload object. Previously accepted; now
     * rejected because the proxy would route a null payload through WorkflowOperations.start().
     */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface StartWithNoPayload {
        @WorkflowStart
        Future<WorkflowInstanceId> start(@IdempotencyKey String key);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayload payload);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);
    }

    /**
     * Failure: @WorkflowSignal with only annotated / WorkflowInstanceId params and no payload.
     * Previously accepted; now rejected because the proxy would route a null payload through
     * WorkflowOperations.signal().
     */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface SignalWithNoPayload {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, @SignalDedupKey String key);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);
    }

    /** Failure: method with no role annotation (not @WorkflowStart, @WorkflowSignal, or @WorkflowQuery). */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface ContractWithNoRoleMethod {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayload payload);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);

        // No role annotation — should be rejected eagerly
        Future<Void> doSomethingUnannotated(WorkflowInstanceId id);
    }

    /** Failure: interface declares a default method — not supported in cycle 1. */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface ContractWithDefaultMethod {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayload payload);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);

        // Default method — not supported in cycle 1
        default String helperMethod() {
            return "helper";
        }
    }

    /** Failure: @WorkflowStart payload type doesn't match plan's startPayloadType (StartPayload). */
    record WrongStartPayload(String orderId) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return orderId;
        }
    }

    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface WrongStartPayloadType {
        // Plan declares startPayloadType=StartPayload; this contract uses WrongStartPayload
        @WorkflowStart
        Future<WorkflowInstanceId> start(WrongStartPayload payload);

        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayload payload);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);
    }

    /** Failure: @WorkflowSignal payload type doesn't match plan's signalPayloadTypes for "order.confirmed". */
    record WrongSignalPayload(String code) implements SignalDedupKeyed {
        @Override
        public String dedupKey() {
            return code;
        }
    }

    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface WrongSignalPayloadType {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);

        // Plan declares "order.confirmed" with payloadClass=SignalPayload; this uses WrongSignalPayload
        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, WrongSignalPayload payload);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);
    }

    /** Sibling interface declaring an annotated start; paired with {@link StartSiblingUnannotated}. */
    interface StartSiblingAnnotated {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartPayload payload);
    }

    /** Sibling interface declaring the SAME start signature with NO role annotation. */
    interface StartSiblingUnannotated {
        Future<WorkflowInstanceId> start(StartPayload payload);
    }

    /**
     * Contract inheriting the same {@code start(StartPayload)} signature from two unrelated sibling
     * interfaces (one annotated, one not). {@code Class#getMethods()} returns both declarations, so
     * the validator must reject the ambiguous shape rather than depend on which one it encounters
     * first — the divergence that would otherwise let codegen and the reflective runtime disagree.
     */
    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface SiblingDuplicateStartContract extends StartSiblingAnnotated, StartSiblingUnannotated {
        @WorkflowSignal("order.confirmed")
        Future<Void> confirm(WorkflowInstanceId id, SignalPayload payload);

        @WorkflowQuery("view")
        Future<WorkflowView> query(WorkflowInstanceId id);
    }

    private DefaultWorkflowRegistry registry;

    @BeforeEach
    void setUp() {
        registry = buildRegistry();
    }

    // --- Tests ---

    @Nested
    @DisplayName("happy paths")
    class HappyPath {

        @Test
        @DisplayName("well-formed contract with IdempotencyKeyed payload passes validation")
        void validContractWithIdempotencyKeyedPayload() {
            assertThatCode(() -> WorkflowProxyValidator.validate(ValidContract.class, registry))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("@IdempotencyKey String parameter is accepted as idempotency key source")
        void idempotencyKeyAnnotationAccepted() {
            assertThatCode(() -> WorkflowProxyValidator.validate(ContractWithIdempotencyKeyParam.class, registry))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("@SignalDedupKey String parameter is accepted as dedup key source")
        void signalDedupKeyAnnotationAccepted() {
            assertThatCode(() -> WorkflowProxyValidator.validate(ContractWithSignalDedupKeyParam.class, registry))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("@BusinessKey parameter is accepted alongside BusinessKeyed payload")
        void businessKeyAnnotationAccepted() {
            assertThatCode(() -> WorkflowProxyValidator.validate(ContractWithBusinessKeyParam.class, registry))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("@SubjectRef parameter is accepted alongside SubjectReferenced payload")
        void subjectRefAnnotationAccepted() {
            assertThatCode(() -> WorkflowProxyValidator.validate(ContractWithSubjectRefParam.class, registry))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("failure cases — §8.3")
    class FailureCases {

        @Test
        @DisplayName("missing @WorkflowContract annotation → reject")
        void missingContractAnnotation() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(NoContractAnnotation.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class);
        }

        @Test
        @DisplayName("operation inherited from two unrelated sibling interfaces → reject (ambiguous)")
        void siblingDuplicateOperationRejected() {
            // C extends A, B where both declare start(StartPayload); getMethods() returns both, so the
            // validator must reject regardless of which declaration it sees first — parity with codegen,
            // which preserves both via MethodOverrides and rejects the same shape before emission.
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(SiblingDuplicateStartContract.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("inherits an ambiguous operation")
                    .hasMessageContaining("sibling interfaces");
        }

        @Test
        @DisplayName("@WorkflowContract with unregistered definitionId → reject")
        void unregisteredDefinition() {
            @WorkflowContract(definitionId = "unknown-saga", definitionVersion = 1)
            interface Unknown {
                @WorkflowStart
                Future<WorkflowInstanceId> start(StartPayload p);
            }
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(Unknown.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("unknown-saga");
        }

        @Test
        @DisplayName("@WorkflowContract with unregistered version → reject")
        void unregisteredVersion() {
            @WorkflowContract(definitionId = "order-saga", definitionVersion = 99)
            interface WrongVersion {
                @WorkflowStart
                Future<WorkflowInstanceId> start(StartPayload p);
            }
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(WrongVersion.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class);
        }

        @Test
        @DisplayName("@WorkflowStart return type not Future<WorkflowInstanceId> → reject")
        void wrongStartReturnType() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(WrongStartReturnType.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class);
        }

        @Test
        @DisplayName("@WorkflowStart with no idempotency key source → reject")
        void noIdempotencyKeySource() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(NoIdempotencyKeySource.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("idempotency");
        }

        @Test
        @DisplayName("@WorkflowSignal return type not Future<Void> → reject")
        void wrongSignalReturnType() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(WrongSignalReturnType.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class);
        }

        @Test
        @DisplayName("@WorkflowSignal signal name not in plan → reject")
        void signalNameNotInPlan() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(SignalNameNotInPlan.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("nonexistent.signal");
        }

        @Test
        @DisplayName("@WorkflowSignal with no dedup key source → reject")
        void noDedupKeySource() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(NoDedupKeySource.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("dedup");
        }

        @Test
        @DisplayName("@WorkflowQuery with extra parameter → reject")
        void queryWrongParamShape() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(QueryWrongParamShape.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class);
        }

        @Test
        @DisplayName("@WorkflowQuery returning a WorkflowView supertype (Future<Object>) → reject (exact-match rule)")
        void querySupertypeReturn() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(QuerySupertypeReturn.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("return type is not Future<WorkflowView>");
        }

        @Test
        @DisplayName("@WorkflowQuery returning Future<? extends WorkflowView> → reject (exact-match rule)")
        void queryWildcardReturn() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(QueryWildcardReturn.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("return type is not Future<WorkflowView>");
        }

        @Test
        @DisplayName("@WorkflowStart returning Future<? extends WorkflowInstanceId> → reject (exact-match rule)")
        void startWildcardReturn() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(StartWildcardReturn.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("return type is not Future<WorkflowInstanceId>");
        }

        @Test
        @DisplayName("@WorkflowContract on a class (not an interface) → reject (parity with codegen)")
        void contractMustBeInterface() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(NotAnInterfaceContract.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("must be an interface");
        }

        @Test
        @DisplayName("two @WorkflowSignal methods with same name → reject")
        void duplicateSignalMethods() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(DuplicateSignalMethods.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("order.confirmed");
        }

        @Test
        @DisplayName("method with two conflicting role annotations → reject")
        void conflictingAnnotations() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(ConflictingAnnotations.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class);
        }

        @Test
        @DisplayName("@WorkflowStart with WorkflowInstanceId parameter → reject")
        void startWithInstanceIdParam() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(StartWithInstanceId.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("WorkflowInstanceId");
        }

        @Test
        @DisplayName("@WorkflowStart with two payload parameters → reject")
        void startWithTwoPayloads() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(StartWithTwoPayloads.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("payload");
        }

        @Test
        @DisplayName("@WorkflowStart with duplicate @IdempotencyKey parameters → reject")
        void startWithDuplicateIdempotencyKey() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(StartWithDuplicateIdempotencyKey.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("IdempotencyKey");
        }

        @Test
        @DisplayName("@WorkflowSignal with no WorkflowInstanceId parameter → reject")
        void signalWithNoInstanceId() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(SignalWithNoInstanceId.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("WorkflowInstanceId");
        }

        @Test
        @DisplayName("@WorkflowSignal with two WorkflowInstanceId parameters → reject")
        void signalWithTwoInstanceIds() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(SignalWithTwoInstanceIds.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("WorkflowInstanceId");
        }

        @Test
        @DisplayName("@WorkflowSignal with WorkflowInstanceId not at position 0 → reject")
        void signalWithInstanceIdNotFirst() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(SignalWithInstanceIdNotFirst.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("first");
        }

        @Test
        @DisplayName("@WorkflowSignal with duplicate @SignalDedupKey parameters → reject")
        void signalWithDuplicateDedupKey() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(SignalWithDuplicateDedupKey.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("SignalDedupKey");
        }

        @Test
        @DisplayName("@WorkflowSignal with two payload parameters → reject")
        void signalWithTwoPayloads() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(SignalWithTwoPayloads.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("payload");
        }

        @Test
        @DisplayName("@WorkflowStart with no payload parameter (only annotated params) → reject")
        void startWithNoPayload() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(StartWithNoPayload.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("payload");
        }

        @Test
        @DisplayName("@WorkflowSignal with no payload parameter (only WorkflowInstanceId + @SignalDedupKey) → reject")
        void signalWithNoPayload() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(SignalWithNoPayload.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("payload");
        }

        @Test
        @DisplayName(
                "method with no role annotation (@WorkflowStart/@WorkflowSignal/@WorkflowQuery) → reject at validate time")
        void methodWithNoRoleAnnotation() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(ContractWithNoRoleMethod.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("doSomethingUnannotated")
                    .hasMessageContaining("no workflow role annotation");
        }

        @Test
        @DisplayName("default method on workflow contract interface → reject at validate time")
        void defaultMethodOnContractInterface() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(ContractWithDefaultMethod.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("helperMethod")
                    .hasMessageContaining("default method");
        }

        @Test
        @DisplayName("@WorkflowStart payload type does not match plan's startPayloadType → reject")
        void startPayloadTypeMismatch() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(WrongStartPayloadType.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("WrongStartPayload")
                    .hasMessageContaining("startPayloadType")
                    .hasMessageContaining("StartPayload");
        }

        @Test
        @DisplayName("@WorkflowSignal payload type does not match plan's signalPayloadTypes → reject")
        void signalPayloadTypeMismatch() {
            assertThatThrownBy(() -> WorkflowProxyValidator.validate(WrongSignalPayloadType.class, registry))
                    .isInstanceOf(WorkflowProxyContractException.class)
                    .hasMessageContaining("WrongSignalPayload")
                    .hasMessageContaining("payloadType")
                    .hasMessageContaining("SignalPayload");
        }
    }

    @Nested
    @DisplayName("sourcing precedence")
    class SourcingPrecedence {

        @Test
        @DisplayName("@BusinessKey annotation source wins over BusinessKeyed interface")
        void businessKeyAnnotationWinsOverInterface() {
            // Both annotation and interface are present; validation must succeed (annotation wins at runtime)
            assertThatCode(() -> WorkflowProxyValidator.validate(ContractWithBusinessKeyParam.class, registry))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("@SubjectRef annotation source wins over SubjectReferenced interface")
        void subjectRefAnnotationWinsOverInterface() {
            assertThatCode(() -> WorkflowProxyValidator.validate(ContractWithSubjectRefParam.class, registry))
                    .doesNotThrowAnyException();
        }
    }
}
