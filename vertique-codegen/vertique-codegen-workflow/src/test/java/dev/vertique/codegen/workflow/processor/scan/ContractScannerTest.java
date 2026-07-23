// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ContractScanner} reads {@code @WorkflowContract} attributes and correctly
 * classifies each method's {@link OperationRole} and parameter {@link ParamRole}s. The scanner is
 * exercised through a capturing {@link AbstractProcessor} compiled by the
 * {@link ProcessorTestHarness} against the real {@code vertique-workflow-core} types on the test
 * classpath.
 */
class ContractScannerTest {

    // --- Fixture sources ---

    private static final JavaFileObject ORDER_PAYLOAD = SourceFiles.inline("com.example.OrderPayload", """
            package com.example;
            import dev.vertique.workflow.contract.IdempotencyKeyed;
            public record OrderPayload(String orderId) implements IdempotencyKeyed {
                @Override public String idempotencyKey() { return orderId; }
            }
            """);

    private static final JavaFileObject CANCEL_PAYLOAD = SourceFiles.inline("com.example.CancelPayload", """
            package com.example;
            import dev.vertique.workflow.contract.SignalDedupKeyed;
            public record CancelPayload(String reason) implements SignalDedupKeyed {
                @Override public String dedupKey() { return reason; }
            }
            """);

    private static final JavaFileObject ORDER_CONTRACT = SourceFiles.inline("com.example.OrderContract", """
            package com.example;
            import dev.vertique.workflow.contract.WorkflowContract;
            import dev.vertique.workflow.contract.WorkflowStart;
            import dev.vertique.workflow.contract.WorkflowSignal;
            import dev.vertique.workflow.contract.WorkflowQuery;
            import dev.vertique.workflow.ops.WorkflowInstanceId;
            import dev.vertique.workflow.ops.WorkflowView;
            import io.vertx.core.Future;
            @WorkflowContract(definitionId = "order", definitionVersion = 2L)
            public interface OrderContract {
                @WorkflowStart
                Future<WorkflowInstanceId> start(OrderPayload p);
                @WorkflowSignal("cancel")
                Future<Void> cancel(WorkflowInstanceId id, CancelPayload c);
                @WorkflowQuery("status")
                Future<WorkflowView> status(WorkflowInstanceId id);
            }
            """);

    private static final JavaFileObject IDEMPOTENCY_KEY_PARAM_PAYLOAD =
            SourceFiles.inline("com.example.PlainPayload", """
            package com.example;
            public record PlainPayload(String data) {}
            """);

    private static final JavaFileObject IDEMPOTENCY_KEY_PARAM_CONTRACT =
            SourceFiles.inline("com.example.KeyParamContract", """
            package com.example;
            import dev.vertique.workflow.contract.WorkflowContract;
            import dev.vertique.workflow.contract.WorkflowStart;
            import dev.vertique.workflow.contract.IdempotencyKey;
            import dev.vertique.workflow.ops.WorkflowInstanceId;
            import io.vertx.core.Future;
            @WorkflowContract(definitionId = "keyparam", definitionVersion = 1L)
            public interface KeyParamContract {
                @WorkflowStart
                Future<WorkflowInstanceId> start(@IdempotencyKey String key, PlainPayload p);
            }
            """);

    // --- Tests ---

    @Test
    @DisplayName("reads @WorkflowContract definitionId and definitionVersion")
    void readsContractAttributes() {
        CapturingProcessor processor = new CapturingProcessor();

        ProcessorTestHarness.run(processor, ORDER_PAYLOAD, CANCEL_PAYLOAD, ORDER_CONTRACT)
                .assertSuccess();

        assertEquals(1, processor.models.size());
        ContractModel model = processor.models.get(0);
        assertEquals("order", model.definitionId());
        assertEquals(2L, model.definitionVersion());
    }

    @Test
    @DisplayName("scans three operations with correct roles on a full contract")
    void scansFullContractOperations() {
        CapturingProcessor processor = new CapturingProcessor();

        ProcessorTestHarness.run(processor, ORDER_PAYLOAD, CANCEL_PAYLOAD, ORDER_CONTRACT)
                .assertSuccess();

        ContractModel model = processor.models.get(0);
        assertEquals(3, model.operations().size());
    }

    @Test
    @DisplayName("start operation: role START, one PAYLOAD param, payloadIsIdempotencyKeyed=true")
    void startOperationRoleAndPayload() {
        CapturingProcessor processor = new CapturingProcessor();

        ProcessorTestHarness.run(processor, ORDER_PAYLOAD, CANCEL_PAYLOAD, ORDER_CONTRACT)
                .assertSuccess();

        ContractModel model = processor.models.get(0);
        OperationModel start = model.operations().stream()
                .filter(op -> op.role().map(r -> r == OperationRole.START).orElse(false))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No START operation found"));

        assertEquals(List.of(OperationRole.START), start.declaredRoles());
        assertEquals(1L, start.payloadParamCount());
        assertTrue(start.payloadIsIdempotencyKeyed(), "payloadIsIdempotencyKeyed should be true");
        assertFalse(start.payloadIsSignalDedupKeyed(), "payloadIsSignalDedupKeyed should be false");
        assertEquals("start", start.methodName());
    }

    @Test
    @DisplayName(
            "signal operation: role SIGNAL, signalName 'cancel', first param INSTANCE_ID, payload param present, payloadIsSignalDedupKeyed=true")
    void signalOperationRoleAndParams() {
        CapturingProcessor processor = new CapturingProcessor();

        ProcessorTestHarness.run(processor, ORDER_PAYLOAD, CANCEL_PAYLOAD, ORDER_CONTRACT)
                .assertSuccess();

        ContractModel model = processor.models.get(0);
        OperationModel signal = model.operations().stream()
                .filter(op -> op.role().map(r -> r == OperationRole.SIGNAL).orElse(false))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SIGNAL operation found"));

        assertEquals(List.of(OperationRole.SIGNAL), signal.declaredRoles());
        assertEquals("cancel", signal.signalName());
        assertEquals(
                ParamRole.INSTANCE_ID, signal.params().get(0).role(), "First param of cancel() should be INSTANCE_ID");
        assertTrue(signal.firstParamWithRole(ParamRole.PAYLOAD).isPresent(), "cancel() should have a PAYLOAD param");
        assertTrue(signal.payloadIsSignalDedupKeyed(), "payloadIsSignalDedupKeyed should be true");
        assertFalse(signal.payloadIsIdempotencyKeyed(), "payloadIsIdempotencyKeyed should be false");
    }

    @Test
    @DisplayName("query operation: role QUERY, one INSTANCE_ID param")
    void queryOperationRoleAndParams() {
        CapturingProcessor processor = new CapturingProcessor();

        ProcessorTestHarness.run(processor, ORDER_PAYLOAD, CANCEL_PAYLOAD, ORDER_CONTRACT)
                .assertSuccess();

        ContractModel model = processor.models.get(0);
        OperationModel query = model.operations().stream()
                .filter(op -> op.role().map(r -> r == OperationRole.QUERY).orElse(false))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No QUERY operation found"));

        assertEquals(List.of(OperationRole.QUERY), query.declaredRoles());
        assertEquals(1, query.params().size());
        assertEquals(ParamRole.INSTANCE_ID, query.params().get(0).role());
        assertNull(query.signalName(), "signalName should be null for non-SIGNAL op");
    }

    @Test
    @DisplayName(
            "@IdempotencyKey param is classified IDEMPOTENCY_KEY; payload without IdempotencyKeyed has payloadIsIdempotencyKeyed=false")
    void idempotencyKeyParamClassification() {
        CapturingProcessor processor = new CapturingProcessor();

        ProcessorTestHarness.run(processor, IDEMPOTENCY_KEY_PARAM_PAYLOAD, IDEMPOTENCY_KEY_PARAM_CONTRACT)
                .assertSuccess();

        ContractModel model = processor.models.get(0);
        assertEquals(1, model.operations().size());
        OperationModel start = model.operations().get(0);

        assertEquals(List.of(OperationRole.START), start.declaredRoles());

        ParamRoleModel keyParam = start.firstParamWithRole(ParamRole.IDEMPOTENCY_KEY)
                .orElseThrow(() -> new AssertionError("Expected an IDEMPOTENCY_KEY param"));
        assertEquals("key", keyParam.name());
        assertEquals(ParamRole.IDEMPOTENCY_KEY, keyParam.role());

        assertFalse(
                start.payloadIsIdempotencyKeyed(),
                "PlainPayload does not implement IdempotencyKeyed so payloadIsIdempotencyKeyed should be false");
    }

    @Test
    @DisplayName("inherited-override dedup: annotated override in sub-interface produces exactly one START operation")
    void inheritedOverrideIsDeduplicatedFavoringAnnotatedSubtype() {
        // Base interface (no @WorkflowContract) declares start() WITHOUT @WorkflowStart.
        // The @WorkflowContract sub-interface overrides it and adds @WorkflowStart.
        // The scanner must apply erased-signature deduplication to ensure that only one
        // operation is produced regardless of how many times the same signature appears in
        // getAllMembers (current javac APT deduplicates, but explicit dedup provides parity
        // with KafkaListenerScanner and guards against future APT behavior differences).
        JavaFileObject baseInterface = SourceFiles.inline("com.example.BaseOrderOps", """
                package com.example;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                public interface BaseOrderOps {
                    Future<WorkflowInstanceId> start(OrderPayload p);
                }
                """);

        JavaFileObject derivedContract = SourceFiles.inline("com.example.InheritedStartContract", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "inherited", definitionVersion = 1L)
                public interface InheritedStartContract extends BaseOrderOps {
                    @Override
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(OrderPayload p);
                }
                """);

        CapturingProcessor processor = new CapturingProcessor();

        ProcessorTestHarness.run(processor, ORDER_PAYLOAD, baseInterface, derivedContract)
                .assertSuccess();

        ContractModel model = processor.models.get(0);
        // Dedup must yield exactly one 'start' operation — not two.
        long startCount = model.operations().stream()
                .filter(op -> op.methodName().equals("start"))
                .count();
        assertEquals(1L, startCount, "dedup must retain exactly one 'start' operation");
        // The surviving operation must carry the START role (the annotated override wins).
        OperationModel start = model.operations().stream()
                .filter(op -> op.methodName().equals("start"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                List.of(OperationRole.START),
                start.declaredRoles(),
                "surviving 'start' must be the annotated override with role START");
    }

    // --- Capturing processor ---

    /**
     * Test processor that runs {@link ContractScanner} over every {@code @WorkflowContract}
     * interface and records each resulting {@link ContractModel}.
     */
    @SupportedAnnotationTypes("dev.vertique.workflow.contract.WorkflowContract")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    static final class CapturingProcessor extends AbstractProcessor {

        final List<ContractModel> models = new ArrayList<>();
        private ContractScanner scanner;

        @Override
        public synchronized void init(ProcessingEnvironment env) {
            super.init(env);
            scanner = new ContractScanner(new CodegenContext(env));
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (roundEnv.processingOver()) {
                return false;
            }
            TypeElement contractAnnotation =
                    processingEnv.getElementUtils().getTypeElement("dev.vertique.workflow.contract.WorkflowContract");
            if (contractAnnotation == null) {
                return false;
            }
            for (Element element : roundEnv.getElementsAnnotatedWith(contractAnnotation)) {
                if (element instanceof TypeElement type) {
                    models.add(scanner.scan(type));
                }
            }
            return false;
        }
    }
}
