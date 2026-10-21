// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.codegen.workflow.processor.WorkflowContractProcessor;
import dev.vertique.workflow.client.WorkflowClientFactory;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code @WorkflowContract} is only a logical name: a name-only contract compiles, the
 * removed definition attributes and an invalid name fail compilation, and two definition classes of
 * one contract create two clients through the factory with generated proxies that carry no
 * definition id or version.
 *
 * <p>Compilation runs end to end through {@link WorkflowContractProcessor} and the
 * {@link ProcessorTestHarness}; the runtime half loads the generated classes and drives them through
 * the real registry and client factory with a recording {@link WorkflowOperations}.
 */
class ContractNameOnlyTest {

    private static final String ORDERS_FQN = "com.example.Orders";
    private static final String PROXY_FQN = "com.example.Orders_WorkflowClientProxy";
    private static final int MAX_NAME_CODE_POINTS = 255;
    private static final int GRINNING_FACE = 0x1F600;

    // --- Shared fixtures ---

    private static final JavaFileObject START_ORDER = SourceFiles.inline("com.example.StartOrder", """
            package com.example;
            import dev.vertique.workflow.contract.IdempotencyKeyed;
            public record StartOrder(String orderId) implements IdempotencyKeyed {
                @Override public String idempotencyKey() { return orderId; }
            }
            """);

    private static final JavaFileObject CANCEL_ORDER = SourceFiles.inline("com.example.CancelOrder", """
            package com.example;
            import dev.vertique.workflow.contract.SignalDedupKeyed;
            public record CancelOrder(String reason) implements SignalDedupKeyed {
                @Override public String dedupKey() { return reason; }
            }
            """);

    // --- Compile-time sources: the same valid contract under four annotations ---

    /** Source A: the logical name only. */
    private static final JavaFileObject NAME_ONLY_CONTRACT = named("orders");

    /** Source B: the removed definition attributes. */
    private static final JavaFileObject LEGACY_ATTRIBUTES_CONTRACT =
            orders("@WorkflowContract(definitionId = \"orders\", definitionVersion = 1L)");

    /** Source C: a blank name. */
    private static final JavaFileObject BLANK_NAME_CONTRACT = named("   ");

    /** Source D: a name one code point over the bound. */
    private static final JavaFileObject OVERLONG_NAME_CONTRACT = named("a".repeat(MAX_NAME_CODE_POINTS + 1));

    /**
     * Builds the orders contract annotated with the given logical name.
     *
     * @param name the annotation's name value, unquoted
     * @return the contract source
     */
    private static JavaFileObject named(String name) {
        return orders("@WorkflowContract(" + '"' + name + '"' + ")");
    }

    /**
     * Builds the orders contract with a start, a signal and a query under the given annotation line.
     *
     * @param annotation the complete {@code @WorkflowContract} annotation
     * @return the contract source
     */
    private static JavaFileObject orders(String annotation) {
        return SourceFiles.inline(ORDERS_FQN, """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowQuery;
                import dev.vertique.workflow.contract.WorkflowSignal;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import dev.vertique.workflow.ops.WorkflowView;
                import io.vertx.core.Future;
                %s
                public interface Orders {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartOrder payload);
                    @WorkflowSignal("cancel")
                    Future<Void> cancel(WorkflowInstanceId id, CancelOrder payload);
                    @WorkflowQuery("status")
                    Future<WorkflowView> status(WorkflowInstanceId id);
                }
                """.formatted(annotation));
    }

    // --- Runtime sources: two definitions of one contract ---

    private static final JavaFileObject ORDERS_STATE = SourceFiles.inline("com.example.OrdersState", """
            package com.example;
            public record OrdersState(String orderId) {}
            """);

    private static final JavaFileObject ORDERS_BASE = SourceFiles.inline("com.example.OrdersBase", """
            package com.example;
            import dev.vertique.workflow.dsl.WorkflowBuilder;
            import dev.vertique.workflow.dsl.WorkflowDefinition;
            public abstract class OrdersBase implements WorkflowDefinition<OrdersState, Orders> {
                private final String definitionId;
                private final long definitionVersion;
                protected OrdersBase(String definitionId, long definitionVersion) {
                    this.definitionId = definitionId;
                    this.definitionVersion = definitionVersion;
                }
                @Override public Class<Orders> contract() { return Orders.class; }
                @Override public Class<OrdersState> stateType() { return OrdersState.class; }
                @Override public String definitionId() { return definitionId; }
                @Override public long definitionVersion() { return definitionVersion; }
                @Override public void define(WorkflowBuilder<OrdersState> wf) {
                    wf.init(StartOrder.class, p -> new OrdersState(p.orderId()))
                            .initialStep("charge")
                            .dispatch("charge", "payment-svc", s -> new Object(), "wait-cancel")
                            .waitFor("wait-cancel", "cancel", CancelOrder.class, (s, p) -> s, "done")
                            .complete("done");
                }
            }
            """);

    private static final JavaFileObject ORDERS_STANDARD = SourceFiles.inline("com.example.OrdersStandard", """
            package com.example;
            public final class OrdersStandard extends OrdersBase {
                public OrdersStandard() { super("orders-standard", 1L); }
            }
            """);

    private static final JavaFileObject ORDERS_EXPRESS = SourceFiles.inline("com.example.OrdersExpress", """
            package com.example;
            public final class OrdersExpress extends OrdersBase {
                public OrdersExpress() { super("orders-express", 4L); }
            }
            """);

    // --- Tests ---

    @Test
    @DisplayName("a name-only contract compiles; the removed attributes and an invalid name do not")
    void nameOnlyContractCompilesAndLegacyAttributesFail() {
        // Given a contract written with its name only, with the removed attributes, with a blank name and with
        // a name of 256 code points
        // When each is compiled with the contract processor
        ProcessorTestHarness.Result nameOnly = compile(NAME_ONLY_CONTRACT);
        ProcessorTestHarness.Result legacy = compile(LEGACY_ATTRIBUTES_CONTRACT);
        ProcessorTestHarness.Result blank = compile(BLANK_NAME_CONTRACT);
        ProcessorTestHarness.Result overlong = compile(OVERLONG_NAME_CONTRACT);

        // Then the name-only contract compiles and gets its proxy
        nameOnly.assertSuccess().assertGeneratedSourceContains(PROXY_FQN, "implements Orders");

        // And the removed attributes fail in the compiler, naming the unknown attribute
        legacy.assertFailed().assertErrorMessage("definitionId");

        // And a blank name and an overlong name fail with the invalid-name error naming the interface and the rule
        blank.assertFailed();
        assertNameInvalidError(blank, "blank");
        overlong.assertFailed();
        assertNameInvalidError(overlong, String.valueOf(MAX_NAME_CODE_POINTS));
        assertFalse(
                errorMessages(overlong).stream().anyMatch(m -> m.contains("a".repeat(MAX_NAME_CODE_POINTS + 1))),
                "the error for an overlong name must not repeat the name");
    }

    @Test
    @DisplayName("a name of 255 code points compiles even when it is longer in UTF-16 units")
    void nameOfMaximumLengthInCodePointsCompiles() {
        // Given a contract whose name is exactly 255 code points, each a supplementary character
        String name = Character.toString(GRINNING_FACE).repeat(MAX_NAME_CODE_POINTS);
        assertEquals(MAX_NAME_CODE_POINTS, name.codePointCount(0, name.length()));
        assertTrue(name.length() > MAX_NAME_CODE_POINTS);

        // When it is compiled with the contract processor
        ProcessorTestHarness.Result result = compile(named(name));

        // Then the name is within the bound
        result.assertSuccess();
    }

    @Test
    @DisplayName("two definitions of one contract create two clients through the factory")
    void twoDefinitionsOfOneContractCreateTwoClientsThroughTheFactory() throws Exception {
        // Given the orders contract and two definitions of it compiled together
        ProcessorTestHarness.Result result = compileOrders();
        ClassLoader loader = result.generatedClassLoader();
        try {
            RecordingOperations ops = new RecordingOperations();
            WorkflowClientFactory factory = factoryOver(result, ops);
            Class<?> ordersType = result.loadGeneratedClass(ORDERS_FQN);
            Class<?> proxyType = result.loadGeneratedClass(PROXY_FQN);
            Class<?> startPayloadType = result.loadGeneratedClass("com.example.StartOrder");
            Object payload = startPayloadType.getConstructor(String.class).newInstance("order-1");

            // When a client is created from each definition class and each starts once
            Object standard =
                    createFromDefinitionClass(factory, result.loadGeneratedClass("com.example.OrdersStandard"));
            Object express = createFromDefinitionClass(factory, result.loadGeneratedClass("com.example.OrdersExpress"));
            ordersType.getMethod("start", startPayloadType).invoke(standard, payload);
            ordersType.getMethod("start", startPayloadType).invoke(express, payload);

            // Then the compilation emitted the proxy and no generated clients module, and the proxy source
            // carries no definition id or version
            result.assertGeneratedSourceDoesNotContain(PROXY_FQN, "orders-standard")
                    .assertGeneratedSourceDoesNotContain(PROXY_FQN, "orders-express")
                    .assertGeneratedSourceDoesNotContain(PROXY_FQN, "1L")
                    .assertGeneratedSourceDoesNotContain(PROXY_FQN, "4L")
                    .assertGeneratedSourceDoesNotContain(PROXY_FQN, "definitionVersion");
            assertTrue(
                    result.compilation().generatedFiles().stream()
                            .noneMatch(f -> f.getName().contains("GeneratedWorkflowClientsModule")),
                    "no GeneratedWorkflowClientsModule source or class may be generated");

            // And both clients are instances of the contract whose class is the generated proxy
            assertTrue(ordersType.isInstance(standard));
            assertTrue(ordersType.isInstance(express));
            assertSame(proxyType, standard.getClass());
            assertSame(proxyType, express.getClass());

            // And the recorded commands carry each client's definition id and the contract name
            List<StartCommand> starts = ops.starts();
            assertEquals(2, starts.size());
            assertEquals("orders-standard", starts.get(0).definitionId());
            assertEquals("orders-express", starts.get(1).definitionId());
            assertEquals("orders", starts.get(0).contractName());
            assertEquals("orders", starts.get(1).contractName());
            assertNull(starts.get(0).requestedDefinitionVersion());
            assertNull(starts.get(1).requestedDefinitionVersion());
        } finally {
            if (loader instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    // --- Helpers ---

    private static ProcessorTestHarness.Result compile(JavaFileObject contract) {
        return ProcessorTestHarness.run(new WorkflowContractProcessor(), START_ORDER, CANCEL_ORDER, contract);
    }

    /**
     * Compiles the name-only orders contract with two definitions of it.
     *
     * @return the successful compilation result
     */
    private static ProcessorTestHarness.Result compileOrders() {
        return ProcessorTestHarness.run(
                        new WorkflowContractProcessor(),
                        START_ORDER,
                        CANCEL_ORDER,
                        NAME_ONLY_CONTRACT,
                        ORDERS_STATE,
                        ORDERS_BASE,
                        ORDERS_STANDARD,
                        ORDERS_EXPRESS)
                .assertSuccess();
    }

    /**
     * Builds a client factory over a registry holding both compiled definitions and the given operations.
     *
     * @param result the compilation holding the definition classes
     * @param ops the operations the clients delegate to
     * @return the factory
     */
    private static WorkflowClientFactory factoryOver(ProcessorTestHarness.Result result, WorkflowOperations ops)
            throws ReflectiveOperationException {
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        for (String definition : List.of("com.example.OrdersStandard", "com.example.OrdersExpress")) {
            Object instance =
                    result.loadGeneratedClass(definition).getConstructor().newInstance();
            registry.register(assertInstanceOf(WorkflowDefinition.class, instance));
        }
        return new WorkflowClientFactory(ops, registry);
    }

    /** Creates a client from a compiled definition class, whose contract type the test cannot name. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object createFromDefinitionClass(WorkflowClientFactory factory, Class<?> definitionClass) {
        return factory.create((Class) definitionClass);
    }

    /**
     * Asserts that one error names the contract interface, says it is about the contract name, and
     * states the violated rule.
     *
     * @param result the failed compilation
     * @param rule a word the error uses for the violated rule
     */
    private static void assertNameInvalidError(ProcessorTestHarness.Result result, String rule) {
        assertTrue(
                errorMessages(result).stream()
                        .anyMatch(m -> m.contains(ORDERS_FQN)
                                && m.toLowerCase(Locale.ROOT).contains("contract name")
                                && m.contains(rule)),
                "expected an error naming " + ORDERS_FQN + ", the contract name and '" + rule + "' but got "
                        + errorMessages(result));
    }

    private static List<String> errorMessages(ProcessorTestHarness.Result result) {
        return result.compilation().errors().stream()
                .map(d -> d.getMessage(null))
                .toList();
    }

    /** Records every start command and answers it with one fixed instance id. */
    private static final class RecordingOperations implements WorkflowOperations {

        private static final WorkflowInstanceId STARTED_ID =
                new WorkflowInstanceId(UUID.fromString("00000000-0000-0000-0000-000000000042"));

        private final List<StartCommand> starts = new ArrayList<>();

        List<StartCommand> starts() {
            return List.copyOf(starts);
        }

        @Override
        public Future<WorkflowInstanceId> start(StartCommand cmd) {
            starts.add(cmd);
            return Future.succeededFuture(STARTED_ID);
        }

        @Override
        public Future<Void> signal(WorkflowInstanceId id, String signalName, Object payload, String signalDedupKey) {
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> cancel(WorkflowInstanceId id, String reason) {
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> retry(WorkflowInstanceId id) {
            return Future.succeededFuture();
        }

        @Override
        public Future<WorkflowView> query(WorkflowInstanceId id) {
            return Future.succeededFuture();
        }
    }
}
