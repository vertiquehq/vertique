// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.validate;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.codegen.workflow.processor.WorkflowContractProcessor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ContractShapeValidator}, {@link ReturnTypeValidator}, and
 * {@link ParamAnnotationValidator} compile-time checks for {@code @WorkflowContract} interfaces,
 * exercised end-to-end through {@link WorkflowContractProcessor} via the
 * {@link ProcessorTestHarness}.
 *
 * <p>Each test covers exactly one contract-shape or annotation rule. Fixtures reference the real
 * types from {@code vertique-workflow-core} on the test classpath.
 */
class WorkflowContractValidatorTest {

    // --- Shared payload fixtures ---

    /** An IdempotencyKeyed payload for use in valid start methods. */
    private static final JavaFileObject IDEMPOTENCY_KEYED_PAYLOAD = SourceFiles.inline("com.example.StartPayload", """
            package com.example;
            import dev.vertique.workflow.contract.IdempotencyKeyed;
            public record StartPayload(String orderId) implements IdempotencyKeyed {
                @Override public String idempotencyKey() { return orderId; }
            }
            """);

    /** A SignalDedupKeyed payload for use in valid signal methods. */
    private static final JavaFileObject SIGNAL_DEDUP_KEYED_PAYLOAD =
            SourceFiles.inline("com.example.CancelPayload", """
            package com.example;
            import dev.vertique.workflow.contract.SignalDedupKeyed;
            public record CancelPayload(String reason) implements SignalDedupKeyed {
                @Override public String dedupKey() { return reason; }
            }
            """);

    /** A plain payload (no marker interfaces). */
    private static final JavaFileObject PLAIN_PAYLOAD = SourceFiles.inline("com.example.PlainPayload", """
            package com.example;
            public record PlainPayload(String data) {}
            """);

    // --- Tests ---

    @Test
    @DisplayName("fully valid contract compiles without errors")
    void validContractSucceeds() {
        JavaFileObject contract = SourceFiles.inline("com.example.OrderContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.contract.WorkflowSignal;
                import dev.vertique.workflow.contract.WorkflowQuery;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import dev.vertique.workflow.ops.WorkflowView;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "order", definitionVersion = 1L)
                public interface OrderContract {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartPayload payload);
                    @WorkflowSignal("cancel")
                    Future<Void> cancel(WorkflowInstanceId id, CancelPayload payload);
                    @WorkflowQuery("status")
                    Future<WorkflowView> status(WorkflowInstanceId id);
                }
                """);

        ProcessorTestHarness.run(
                        new WorkflowContractProcessor(),
                        IDEMPOTENCY_KEYED_PAYLOAD,
                        SIGNAL_DEDUP_KEYED_PAYLOAD,
                        contract)
                .assertSuccess();
    }

    @Test
    @DisplayName("@WorkflowContract on a class produces interface-required error")
    void contractOnClassIsRejected() {
        JavaFileObject contract = SourceFiles.inline("com.example.BadContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                @WorkflowContract(definitionId = "bad", definitionVersion = 1L)
                public class BadContract {}
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), contract)
                .assertFailed()
                .assertErrorMessage("@WorkflowContract com.example.BadContract must be an interface");
    }

    @Test
    @DisplayName("contract with zero @WorkflowStart methods produces start-cardinality error")
    void noStartMethodProducesCardinalityError() {
        JavaFileObject contract = SourceFiles.inline("com.example.NoStartContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowSignal;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "nostrt", definitionVersion = 1L)
                public interface NoStartContract {
                    @WorkflowSignal("cancel")
                    Future<Void> cancel(WorkflowInstanceId id, NoStartContract__Payload p);
                }
                """);
        // Provide a placeholder payload inline
        JavaFileObject payload = SourceFiles.inline("com.example.NoStartContract__Payload", """
                package com.example;
                import dev.vertique.workflow.contract.SignalDedupKeyed;
                public record NoStartContract__Payload(String x) implements SignalDedupKeyed {
                    @Override public String dedupKey() { return x; }
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), payload, contract)
                .assertFailed()
                .assertErrorMessage(
                        "Contract com.example.NoStartContract must have exactly one @WorkflowStart method; found 0");
    }

    @Test
    @DisplayName("default method on contract produces default-method error")
    void defaultMethodIsRejected() {
        JavaFileObject contract = SourceFiles.inline("com.example.DefaultMethodContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "dflt", definitionVersion = 1L)
                public interface DefaultMethodContract {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartPayload payload);
                    default String helper() { return "x"; }
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), IDEMPOTENCY_KEYED_PAYLOAD, contract)
                .assertFailed()
                .assertErrorMessage("is a default method");
    }

    @Test
    @DisplayName("method with both @WorkflowStart and @WorkflowQuery produces conflicting-roles error")
    void conflictingRolesIsRejected() {
        JavaFileObject contract = SourceFiles.inline("com.example.ConflictContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.contract.WorkflowQuery;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import dev.vertique.workflow.ops.WorkflowView;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "cflct", definitionVersion = 1L)
                public interface ConflictContract {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartPayload payload);
                    @WorkflowStart
                    @WorkflowQuery("status")
                    Future<WorkflowView> startAndQuery(StartPayload payload);
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), IDEMPOTENCY_KEYED_PAYLOAD, contract)
                .assertFailed()
                .assertErrorMessage("conflicting workflow annotations");
    }

    @Test
    @DisplayName("non-annotated abstract method produces no-role error")
    void noRoleMethodIsRejected() {
        JavaFileObject contract = SourceFiles.inline("com.example.NoRoleContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "norole", definitionVersion = 1L)
                public interface NoRoleContract {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartPayload payload);
                    void unannotated(String x);
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), IDEMPOTENCY_KEYED_PAYLOAD, contract)
                .assertFailed()
                .assertErrorMessage("has no workflow role annotation");
    }

    @Test
    @DisplayName("@WorkflowStart with WorkflowInstanceId param produces forbids-instanceId error")
    void startWithInstanceIdParamIsRejected() {
        JavaFileObject contract = SourceFiles.inline("com.example.StartWithIdContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "stwid", definitionVersion = 1L)
                public interface StartWithIdContract {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(WorkflowInstanceId id, StartPayload payload);
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), IDEMPOTENCY_KEYED_PAYLOAD, contract)
                .assertFailed()
                .assertErrorMessage("@WorkflowStart must not have a WorkflowInstanceId parameter");
    }

    @Test
    @DisplayName("@WorkflowStart with plain payload and no idempotency source produces missing-idempotency error")
    void startWithNoIdempotencySourceIsRejected() {
        JavaFileObject contract = SourceFiles.inline("com.example.NoIdempotencyContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "noidemp", definitionVersion = 1L)
                public interface NoIdempotencyContract {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(PlainPayload payload);
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), PLAIN_PAYLOAD, contract)
                .assertFailed()
                .assertErrorMessage("has no idempotency key source");
    }

    @Test
    @DisplayName("@WorkflowStart returning Future<String> produces start-return-type error")
    void startWithWrongReturnTypeIsRejected() {
        JavaFileObject contract = SourceFiles.inline("com.example.BadStartReturnContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "badsret", definitionVersion = 1L)
                public interface BadStartReturnContract {
                    @WorkflowStart
                    Future<String> start(StartPayload payload);
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), IDEMPOTENCY_KEYED_PAYLOAD, contract)
                .assertFailed()
                .assertErrorMessage("return type is not Future<WorkflowInstanceId>");
    }

    @Test
    @DisplayName("@WorkflowSignal with WorkflowInstanceId NOT first produces instanceId-first error")
    void signalWithInstanceIdNotFirstIsRejected() {
        JavaFileObject contract = SourceFiles.inline("com.example.IdNotFirstContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.contract.WorkflowSignal;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "idfirst", definitionVersion = 1L)
                public interface IdNotFirstContract {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartPayload payload);
                    @WorkflowSignal("cancel")
                    Future<Void> cancel(CancelPayload payload, WorkflowInstanceId id);
                }
                """);

        ProcessorTestHarness.run(
                        new WorkflowContractProcessor(),
                        IDEMPOTENCY_KEYED_PAYLOAD,
                        SIGNAL_DEDUP_KEYED_PAYLOAD,
                        contract)
                .assertFailed()
                .assertErrorMessage("WorkflowInstanceId to be the first parameter");
    }

    @Test
    @DisplayName("@WorkflowSignal returning Future<String> produces signal-return-type error")
    void signalWithWrongReturnTypeIsRejected() {
        JavaFileObject contract = SourceFiles.inline("com.example.BadSignalReturnContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.contract.WorkflowSignal;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "badsigret", definitionVersion = 1L)
                public interface BadSignalReturnContract {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartPayload payload);
                    @WorkflowSignal("cancel")
                    Future<String> cancel(WorkflowInstanceId id, CancelPayload payload);
                }
                """);

        ProcessorTestHarness.run(
                        new WorkflowContractProcessor(),
                        IDEMPOTENCY_KEYED_PAYLOAD,
                        SIGNAL_DEDUP_KEYED_PAYLOAD,
                        contract)
                .assertFailed()
                .assertErrorMessage("return type is not Future<Void>");
    }

    @Test
    @DisplayName("@WorkflowSignal with no dedup source produces missing-dedup error")
    void signalWithNoDedupSourceIsRejected() {
        JavaFileObject contract = SourceFiles.inline("com.example.NoDedupContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.contract.WorkflowSignal;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "nodedup", definitionVersion = 1L)
                public interface NoDedupContract {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartPayload payload);
                    @WorkflowSignal("cancel")
                    Future<Void> cancel(WorkflowInstanceId id, PlainPayload payload);
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), IDEMPOTENCY_KEYED_PAYLOAD, PLAIN_PAYLOAD, contract)
                .assertFailed()
                .assertErrorMessage("has no dedup key source");
    }

    @Test
    @DisplayName("two @WorkflowSignal methods with same name produce duplicate-signal-name error")
    void duplicateSignalNameIsRejected() {
        JavaFileObject contract = SourceFiles.inline("com.example.DupSignalContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.contract.WorkflowSignal;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "dupsig", definitionVersion = 1L)
                public interface DupSignalContract {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartPayload payload);
                    @WorkflowSignal("dup")
                    Future<Void> cancelA(WorkflowInstanceId id, CancelPayload payload);
                    @WorkflowSignal("dup")
                    Future<Void> cancelB(WorkflowInstanceId id, CancelPayload payload);
                }
                """);

        ProcessorTestHarness.run(
                        new WorkflowContractProcessor(),
                        IDEMPOTENCY_KEYED_PAYLOAD,
                        SIGNAL_DEDUP_KEYED_PAYLOAD,
                        contract)
                .assertFailed()
                .assertErrorMessage("Duplicate @WorkflowSignal(\"dup\")");
    }

    @Test
    @DisplayName("@IdempotencyKey on an Integer param produces param-annotation-type error")
    void idempotencyKeyOnNonStringProducesTypeError() {
        JavaFileObject contract = SourceFiles.inline("com.example.BadIdempKeyContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.contract.IdempotencyKey;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "badidkey", definitionVersion = 1L)
                public interface BadIdempKeyContract {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(@IdempotencyKey Integer key, PlainPayload payload);
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), PLAIN_PAYLOAD, contract)
                .assertFailed()
                .assertErrorMessage("@IdempotencyKey must be on a String parameter");
    }

    @Test
    @DisplayName("@WorkflowQuery returning Future<String> produces query-return error (not Future<WorkflowView>)")
    void queryWithNonViewReturnTypeIsRejected() {
        JavaFileObject contract = SourceFiles.inline("com.example.BadQueryReturnContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.contract.WorkflowQuery;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "badqret", definitionVersion = 1L)
                public interface BadQueryReturnContract {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartPayload payload);
                    @WorkflowQuery("status")
                    Future<String> status(WorkflowInstanceId id);
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), IDEMPOTENCY_KEYED_PAYLOAD, contract)
                .assertFailed()
                .assertErrorMessage("return type is not Future<WorkflowView>");
    }

    @Test
    @DisplayName("@WorkflowQuery returning Future<Object> (a WorkflowView supertype) is rejected — exact-match rule")
    void queryWithSupertypeReturnTypeIsRejected() {
        // V1 requires exactly Future<WorkflowView>. A supertype like Future<Object> — which an
        // assignable-from check would have accepted — must be rejected so runtime and codegen agree.
        JavaFileObject contract = SourceFiles.inline("com.example.SupertypeQueryContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.contract.WorkflowQuery;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "superqret", definitionVersion = 1L)
                public interface SupertypeQueryContract {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartPayload payload);
                    @WorkflowQuery("status")
                    Future<Object> status(WorkflowInstanceId id);
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), IDEMPOTENCY_KEYED_PAYLOAD, contract)
                .assertFailed()
                .assertErrorMessage("return type is not Future<WorkflowView>");
    }

    @Test
    @DisplayName("@WorkflowQuery returning Future<? extends WorkflowView> is rejected (exact, non-erased match)")
    void queryWithWildcardReturnTypeIsRejected() {
        // An erased comparison would collapse `? extends WorkflowView` to its bound and wrongly accept it,
        // making codegen looser than the runtime (whose isFutureOf requires an exact Class). Reject it.
        JavaFileObject contract = SourceFiles.inline("com.example.WildcardQueryContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.contract.WorkflowQuery;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import dev.vertique.workflow.ops.WorkflowView;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "wildqret", definitionVersion = 1L)
                public interface WildcardQueryContract {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartPayload payload);
                    @WorkflowQuery("status")
                    Future<? extends WorkflowView> status(WorkflowInstanceId id);
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), IDEMPOTENCY_KEYED_PAYLOAD, contract)
                .assertFailed()
                .assertErrorMessage("return type is not Future<WorkflowView>");
    }

    @Test
    @DisplayName("@WorkflowStart returning Future<? extends WorkflowInstanceId> is rejected (exact, non-erased match)")
    void startWithWildcardReturnTypeIsRejected() {
        JavaFileObject contract = SourceFiles.inline("com.example.WildcardStartContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "wildstart", definitionVersion = 1L)
                public interface WildcardStartContract {
                    @WorkflowStart
                    Future<? extends WorkflowInstanceId> start(StartPayload payload);
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), IDEMPOTENCY_KEYED_PAYLOAD, contract)
                .assertFailed()
                .assertErrorMessage("return type is not Future<WorkflowInstanceId>");
    }

    @Test
    @DisplayName(
            "inherited-override contract: annotated override of unannotated base method compiles without spurious errors")
    void inheritedOverrideContractSucceeds() {
        // A plain base interface (no @WorkflowContract) declares start() WITHOUT @WorkflowStart.
        // The @WorkflowContract sub-interface overrides it and adds @WorkflowStart.
        // Without deduplication, getAllMembers would surface both declarations:
        //   - the inherited unannotated one → triggers "no workflow role annotation" error
        //   - the annotated override → the intended operation
        // After deduplication, only the annotated override survives.
        JavaFileObject baseInterface = SourceFiles.inline("com.example.BaseOrderOps", """
                package com.example;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                public interface BaseOrderOps {
                    Future<WorkflowInstanceId> start(StartPayload p);
                }
                """);

        JavaFileObject contract = SourceFiles.inline("com.example.InheritedContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.contract.WorkflowQuery;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import dev.vertique.workflow.ops.WorkflowView;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "inherited", definitionVersion = 1L)
                public interface InheritedContract extends BaseOrderOps {
                    @Override
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartPayload p);
                    @WorkflowQuery("status")
                    Future<WorkflowView> status(WorkflowInstanceId id);
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), IDEMPOTENCY_KEYED_PAYLOAD, baseInterface, contract)
                .assertSuccess();
    }

    @Test
    @DisplayName("@WorkflowQuery with two params produces query-param error")
    void queryWithTwoParamsIsRejected() {
        JavaFileObject contract = SourceFiles.inline("com.example.TwoParamQueryContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.contract.WorkflowQuery;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import dev.vertique.workflow.ops.WorkflowView;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "2pq", definitionVersion = 1L)
                public interface TwoParamQueryContract {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartPayload payload);
                    @WorkflowQuery("status")
                    Future<WorkflowView> status(WorkflowInstanceId id, String extra);
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), IDEMPOTENCY_KEYED_PAYLOAD, contract)
                .assertFailed()
                .assertErrorMessage("must have exactly one parameter of type WorkflowInstanceId");
    }

    @Test
    @DisplayName("operation inherited from two unrelated sibling interfaces → ambiguous-operation error")
    void siblingDuplicateOperationRejected() {
        // SiblingA declares an annotated start; SiblingB declares the SAME erased signature with no role.
        // getAllMembers reports both, MethodOverrides preserves both (genuine siblings), and the shape
        // validator rejects the ambiguous operation rather than emitting a duplicate-method proxy —
        // matching the runtime, which sees both via Class#getMethods().
        JavaFileObject siblingA = SourceFiles.inline("com.example.SiblingA", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                public interface SiblingA {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartPayload payload);
                }
                """);
        JavaFileObject siblingB = SourceFiles.inline("com.example.SiblingB", """
                package com.example;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                public interface SiblingB {
                    Future<WorkflowInstanceId> start(StartPayload payload);
                }
                """);
        JavaFileObject contract = SourceFiles.inline("com.example.SiblingContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowQuery;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import dev.vertique.workflow.ops.WorkflowView;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "order", definitionVersion = 1L)
                public interface SiblingContract extends SiblingA, SiblingB {
                    @WorkflowQuery("status")
                    Future<WorkflowView> status(WorkflowInstanceId id);
                }
                """);

        ProcessorTestHarness.run(
                        new WorkflowContractProcessor(), IDEMPOTENCY_KEYED_PAYLOAD, siblingA, siblingB, contract)
                .assertFailed()
                .assertErrorMessage("inherits an ambiguous operation");
    }
}
