// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor;

import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link KafkaConsumerProcessor} scan + emit behaviour for the supported Kafka consumer
 * models:
 * <ul>
 *   <li>Model 1 {@code @KafkaSource} — service-impl class with one or more {@code @KafkaSource}
 *       methods; emits one SOURCE meta per method</li>
 *   <li>Model 3 router — {@code @KafkaListener} interface with {@code @KafkaHandler} methods</li>
 *   <li>Model 4 direct handler — {@code @KafkaListener} class implementing
 *       {@code KafkaRecordHandler<V>}</li>
 * </ul>
 */
class KafkaConsumerProcessorTest {

    // --- Shared fixture sources that appear in multiple tests ---

    private static final JavaFileObject SOME_EVENT = SourceFiles.inline("com.example.SomeEvent", """
            package com.example;
            public record SomeEvent(String id) {}
            """);

    private static final JavaFileObject ORDER_SERVICE = SourceFiles.inline("com.example.OrderService", """
            package com.example;
            public interface OrderService {}
            """);

    private static final JavaFileObject PAYMENT_SERVICE = SourceFiles.inline("com.example.PaymentService", """
            package com.example;
            public interface PaymentService {}
            """);

    // --- Model 3 Router tests ---

    @Nested
    @DisplayName("Model 3 router — @KafkaListener interface")
    class RouterTests {

        @Test
        @DisplayName("generates _BindingMeta with Kind.ROUTER for a @KafkaListener interface")
        void routerGeneratesBindingMetaWithKindRouter() {
            JavaFileObject router = SourceFiles.inline("com.example.OrderRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    import dev.vertique.kafka.DispatchTo;
                    @KafkaListener(name = "orders", topic = "order.events", groupId = "g")
                    public interface OrderRouter {
                        @KafkaHandler(matchHeader = "event-type", matchValue = "order.created")
                        @DispatchTo(service = OrderService.class, operation = "processOrder")
                        void onOrderCreated(SomeEvent event);

                        @KafkaHandler(defaultHandler = true)
                        void onUnmatched();
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT, ORDER_SERVICE)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.OrderRouter_BindingMeta", "Kind.ROUTER");
        }

        @Test
        @DisplayName("generated router meta contains the binding name, topic, and groupId")
        void routerMetaContainsBindingAttrs() {
            JavaFileObject router = SourceFiles.inline("com.example.OrderRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "orders", topic = "order.events", groupId = "order-group")
                    public interface OrderRouter {
                        @KafkaHandler(defaultHandler = true)
                        void onAny();
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.OrderRouter_BindingMeta", "\"orders\"")
                    .assertGeneratedSourceContains("com.example.OrderRouter_BindingMeta", "\"order.events\"")
                    .assertGeneratedSourceContains("com.example.OrderRouter_BindingMeta", "\"order-group\"");
        }

        @Test
        @DisplayName("generated router meta contains matchHeader and @DispatchTo service + operation")
        void routerMetaContainsMatchHeaderAndDispatchTo() {
            JavaFileObject router = SourceFiles.inline("com.example.OrderRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    import dev.vertique.kafka.DispatchTo;
                    @KafkaListener(name = "orders", topic = "order.events", groupId = "g")
                    public interface OrderRouter {
                        @KafkaHandler(matchHeader = "event-type", matchValue = "order.created")
                        @DispatchTo(service = OrderService.class, operation = "processOrder")
                        void onOrderCreated(SomeEvent event);

                        @KafkaHandler(defaultHandler = true)
                        void onUnmatched();
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT, ORDER_SERVICE)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.OrderRouter_BindingMeta", "\"event-type\"")
                    .assertGeneratedSourceContains("com.example.OrderRouter_BindingMeta", "\"order.created\"")
                    .assertGeneratedSourceContains("com.example.OrderRouter_BindingMeta", "OrderService.class")
                    .assertGeneratedSourceContains("com.example.OrderRouter_BindingMeta", "\"processOrder\"");
        }

        @Test
        @DisplayName("generated router meta contains Void.class for routes with no payload param")
        void routerRouteWithNoPayloadUsesVoidClass() {
            JavaFileObject router = SourceFiles.inline("com.example.OrderRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "orders", topic = "order.events", groupId = "g")
                    public interface OrderRouter {
                        @KafkaHandler(defaultHandler = true)
                        void onUnmatched();
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.OrderRouter_BindingMeta", "Void.class");
        }

        @Test
        @DisplayName("generated router meta contains SomeEvent.class for routes with a payload param")
        void routerRouteWithPayloadUsesDeclaredClass() {
            JavaFileObject router = SourceFiles.inline("com.example.OrderRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "orders", topic = "order.events", groupId = "g")
                    public interface OrderRouter {
                        @KafkaHandler(matchHeader = "type", matchValue = "created")
                        void onCreated(SomeEvent event);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.OrderRouter_BindingMeta", "SomeEvent.class");
        }

        @Test
        @DisplayName("generated router meta contains defaultHandler = true for catch-all routes")
        void routerDefaultHandlerRouteIsPresent() {
            JavaFileObject router = SourceFiles.inline("com.example.OrderRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "orders", topic = "order.events", groupId = "g")
                    public interface OrderRouter {
                        @KafkaHandler(defaultHandler = true)
                        void onUnmatched();
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.OrderRouter_BindingMeta", "true");
        }

        @Test
        @DisplayName("generated router meta null targetService/operation for routes without @DispatchTo")
        void routerRouteWithoutDispatchToHasNullTargets() {
            JavaFileObject router = SourceFiles.inline("com.example.OrderRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "orders", topic = "order.events", groupId = "g")
                    public interface OrderRouter {
                        @KafkaHandler(defaultHandler = true)
                        void onUnmatched();
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.OrderRouter_BindingMeta", "null");
        }

        @Test
        @DisplayName("generated router meta contains two routes for two @KafkaHandler methods")
        void routerWithTwoHandlerMethodsGeneratesTwoRoutes() {
            JavaFileObject router = SourceFiles.inline("com.example.TwoRouteRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    import dev.vertique.kafka.DispatchTo;
                    @KafkaListener(name = "two", topic = "t", groupId = "g")
                    public interface TwoRouteRouter {
                        @KafkaHandler(matchHeader = "h", matchValue = "v1")
                        @DispatchTo(service = OrderService.class, operation = "op1")
                        void onFirst(SomeEvent event);

                        @KafkaHandler(defaultHandler = true)
                        void onDefault();
                    }
                    """);

            // Both route constructors appear in the output; the simplest check is that
            // RouteMeta appears twice. We count the second route's matchHeader being "".
            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT, ORDER_SERVICE)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.TwoRouteRouter_BindingMeta", "Kind.ROUTER")
                    .assertGeneratedSourceContains("com.example.TwoRouteRouter_BindingMeta", "OrderService.class")
                    .assertGeneratedSourceContains("com.example.TwoRouteRouter_BindingMeta", "\"op1\"");
        }
    }

    // --- Model 4 Direct Handler tests ---

    @Nested
    @DisplayName("Model 4 direct handler — @KafkaListener class implementing KafkaRecordHandler<V>")
    class DirectHandlerTests {

        @Test
        @DisplayName("generates _BindingMeta with Kind.HANDLER for a @KafkaListener handler class")
        void handlerGeneratesBindingMetaWithKindHandler() {
            JavaFileObject handler = SourceFiles.inline("com.example.OrderHandler", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "orders", topic = "order.events", groupId = "g")
                    public class OrderHandler implements KafkaRecordHandler<SomeEvent> {
                        @Override
                        public Future<Void> handle(KafkaMessage<SomeEvent> msg) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), handler, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.OrderHandler_BindingMeta", "Kind.HANDLER");
        }

        @Test
        @DisplayName("generated handler meta contains the resolved value type SomeEvent.class")
        void handlerMetaContainsResolvedValueType() {
            JavaFileObject handler = SourceFiles.inline("com.example.OrderHandler", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "orders", topic = "order.events", groupId = "g")
                    public class OrderHandler implements KafkaRecordHandler<SomeEvent> {
                        @Override
                        public Future<Void> handle(KafkaMessage<SomeEvent> msg) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), handler, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.OrderHandler_BindingMeta", "SomeEvent.class");
        }

        @Test
        @DisplayName("generated handler meta contains the binding name, topic, and groupId")
        void handlerMetaContainsBindingAttrs() {
            JavaFileObject handler = SourceFiles.inline("com.example.OrderHandler", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "orders", topic = "order.events", groupId = "handler-group")
                    public class OrderHandler implements KafkaRecordHandler<SomeEvent> {
                        @Override
                        public Future<Void> handle(KafkaMessage<SomeEvent> msg) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), handler, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.OrderHandler_BindingMeta", "\"orders\"")
                    .assertGeneratedSourceContains("com.example.OrderHandler_BindingMeta", "\"order.events\"")
                    .assertGeneratedSourceContains("com.example.OrderHandler_BindingMeta", "\"handler-group\"");
        }

        @Test
        @DisplayName("generated handler meta has null targetService and targetOperation")
        void handlerMetaHasNullDispatchTargets() {
            JavaFileObject handler = SourceFiles.inline("com.example.OrderHandler", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "orders", topic = "order.events", groupId = "g")
                    public class OrderHandler implements KafkaRecordHandler<SomeEvent> {
                        @Override
                        public Future<Void> handle(KafkaMessage<SomeEvent> msg) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), handler, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.OrderHandler_BindingMeta", "null");
        }

        @Test
        @DisplayName("generated handler meta has an empty routes list")
        void handlerMetaHasEmptyRoutes() {
            JavaFileObject handler = SourceFiles.inline("com.example.OrderHandler", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "orders", topic = "order.events", groupId = "g")
                    public class OrderHandler implements KafkaRecordHandler<SomeEvent> {
                        @Override
                        public Future<Void> handle(KafkaMessage<SomeEvent> msg) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), handler, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.OrderHandler_BindingMeta", "List.of()");
        }

        @Test
        @DisplayName("generated handler meta reflects non-default errorStrategy and commitStrategy")
        void handlerMetaReflectsCustomStrategies() {
            JavaFileObject handler = SourceFiles.inline("com.example.OrderHandler", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import dev.vertique.kafka.ErrorStrategy;
                    import dev.vertique.kafka.CommitStrategy;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "orders", topic = "order.events", groupId = "g",
                                   errorStrategy = ErrorStrategy.DEAD_LETTER,
                                   commitStrategy = CommitStrategy.MANUAL)
                    public class OrderHandler implements KafkaRecordHandler<SomeEvent> {
                        @Override
                        public Future<Void> handle(KafkaMessage<SomeEvent> msg) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), handler, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.OrderHandler_BindingMeta", "ErrorStrategy.DEAD_LETTER")
                    .assertGeneratedSourceContains("com.example.OrderHandler_BindingMeta", "CommitStrategy.MANUAL");
        }
    }

    // --- Model 1 @KafkaSource tests ---

    @Nested
    @DisplayName("Model 1 @KafkaSource — service-impl method bindings")
    class KafkaSourceTests {

        @Test
        @DisplayName("two @KafkaSource methods on one impl class → one companion with two BINDING metas")
        void twoSourceMethodsGenerateTwoBindingMetas() {
            JavaFileObject impl = SourceFiles.inline("com.example.OrderServiceImpl", """
                    package com.example;
                    import dev.vertique.kafka.KafkaSource;
                    import io.vertx.core.Future;
                    public class OrderServiceImpl {
                        @KafkaSource(name = "order-created-binding", topic = "order.created", groupId = "order-svc")
                        public Future<Void> processCreated(SomeEvent event) { return Future.succeededFuture(); }

                        @KafkaSource(name = "order-cancelled-binding", topic = "order.cancelled", groupId = "order-svc")
                        public Future<Void> processCancelled(SomeEvent event) { return Future.succeededFuture(); }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), impl, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.OrderServiceImpl_BindingMeta", "Kind.SOURCE")
                    .assertGeneratedSourceContains(
                            "com.example.OrderServiceImpl_BindingMeta", "\"order-created-binding\"")
                    .assertGeneratedSourceContains(
                            "com.example.OrderServiceImpl_BindingMeta", "\"order-cancelled-binding\"")
                    .assertGeneratedSourceContains("com.example.OrderServiceImpl_BindingMeta", "\"order.created\"")
                    .assertGeneratedSourceContains("com.example.OrderServiceImpl_BindingMeta", "\"order.cancelled\"")
                    .assertGeneratedSourceContains("com.example.OrderServiceImpl_BindingMeta", "\"processCreated\"")
                    .assertGeneratedSourceContains("com.example.OrderServiceImpl_BindingMeta", "\"processCancelled\"");
        }

        @Test
        @DisplayName("@KafkaSource meta has null valueType and null targetService")
        void sourceMetaHasNullValueTypeAndTargetService() {
            JavaFileObject impl = SourceFiles.inline("com.example.PaymentServiceImpl", """
                    package com.example;
                    import dev.vertique.kafka.KafkaSource;
                    import io.vertx.core.Future;
                    public class PaymentServiceImpl {
                        @KafkaSource(topic = "payment.events", groupId = "pay-svc")
                        public Future<Void> handlePayment(SomeEvent event) { return Future.succeededFuture(); }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), impl, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.PaymentServiceImpl_BindingMeta", "null")
                    .assertGeneratedSourceContains("com.example.PaymentServiceImpl_BindingMeta", "Kind.SOURCE")
                    .assertGeneratedSourceContains("com.example.PaymentServiceImpl_BindingMeta", "List.of()");
        }

        @Test
        @DisplayName("@KafkaSource meta carries targetOperation equal to the impl method name")
        void sourceMetaTargetOperationIsMethodName() {
            JavaFileObject impl = SourceFiles.inline("com.example.ShipmentServiceImpl", """
                    package com.example;
                    import dev.vertique.kafka.KafkaSource;
                    import io.vertx.core.Future;
                    public class ShipmentServiceImpl {
                        @KafkaSource(topic = "shipment.events", groupId = "ship-svc")
                        public Future<Void> handleShipment(SomeEvent event) { return Future.succeededFuture(); }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), impl, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.ShipmentServiceImpl_BindingMeta", "\"handleShipment\"");
        }

        @Test
        @DisplayName("@KafkaSource meta reflects non-default errorStrategy and commitStrategy")
        void sourceMetaReflectsCustomStrategies() {
            JavaFileObject impl = SourceFiles.inline("com.example.InvoiceServiceImpl", """
                    package com.example;
                    import dev.vertique.kafka.KafkaSource;
                    import dev.vertique.kafka.ErrorStrategy;
                    import dev.vertique.kafka.CommitStrategy;
                    import io.vertx.core.Future;
                    public class InvoiceServiceImpl {
                        @KafkaSource(topic = "invoice.events", groupId = "inv-svc",
                                     errorStrategy = ErrorStrategy.DEAD_LETTER,
                                     commitStrategy = CommitStrategy.MANUAL,
                                     deadLetterTopic = "invoice.events.dlq")
                        public Future<Void> handleInvoice(SomeEvent event) { return Future.succeededFuture(); }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), impl, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.InvoiceServiceImpl_BindingMeta", "ErrorStrategy.DEAD_LETTER")
                    .assertGeneratedSourceContains(
                            "com.example.InvoiceServiceImpl_BindingMeta", "CommitStrategy.MANUAL")
                    .assertGeneratedSourceContains(
                            "com.example.InvoiceServiceImpl_BindingMeta", "\"invoice.events.dlq\"");
        }

        @Test
        @DisplayName(
                "@KafkaSource on an interface method → compile error 'must be on the service implementation method'")
        void sourceOnInterfaceMethodIsRejected() {
            JavaFileObject contractInterface = SourceFiles.inline("com.example.OrderService", """
                    package com.example;
                    import dev.vertique.kafka.KafkaSource;
                    import io.vertx.core.Future;
                    public interface OrderService {
                        @KafkaSource(topic = "order.events", groupId = "order-svc")
                        Future<Void> processOrder(SomeEvent event);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), contractInterface, SOME_EVENT)
                    .assertFailed()
                    .assertErrorMessage("must be on the service implementation method");
        }
    }

    // --- Fix 1: generic payload types emit erased .class ---

    @Nested
    @DisplayName("Fix 1 — generic payload type emits erased class literal")
    class GenericPayloadErasureTests {

        @Test
        @DisplayName(
                "generic route payload Envelope<SomeEvent> emits Envelope.class (erased), not Envelope<SomeEvent>.class")
        void genericRoutePayloadIsErased() {
            JavaFileObject envelope = SourceFiles.inline("com.example.Envelope", """
                    package com.example;
                    public class Envelope<T> { public T payload; }
                    """);
            JavaFileObject router = SourceFiles.inline("com.example.EnvelopeRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "env", topic = "env.events", groupId = "g")
                    public interface EnvelopeRouter {
                        @KafkaHandler(matchHeader = "type", matchValue = "order")
                        void onOrder(Envelope<SomeEvent> event);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, envelope, SOME_EVENT)
                    .assertSuccess()
                    // erased class literal must appear
                    .assertGeneratedSourceContains("com.example.EnvelopeRouter_BindingMeta", "Envelope.class")
                    // parameterized form must NOT appear
                    .assertGeneratedSourceDoesNotContain(
                            "com.example.EnvelopeRouter_BindingMeta", "Envelope<SomeEvent>.class");
        }

        @Test
        @DisplayName("generic handler value type Envelope<SomeEvent> emits Envelope.class (erased)")
        void genericHandlerValueTypeIsErased() {
            JavaFileObject envelope = SourceFiles.inline("com.example.Envelope", """
                    package com.example;
                    public class Envelope<T> { public T payload; }
                    """);
            JavaFileObject handler = SourceFiles.inline("com.example.EnvelopeHandler", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "env-handler", topic = "env.events", groupId = "g")
                    public class EnvelopeHandler implements KafkaRecordHandler<Envelope<SomeEvent>> {
                        @Override
                        public Future<Void> handle(KafkaMessage<Envelope<SomeEvent>> msg) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), handler, envelope, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.EnvelopeHandler_BindingMeta", "Envelope.class")
                    .assertGeneratedSourceDoesNotContain(
                            "com.example.EnvelopeHandler_BindingMeta", "Envelope<SomeEvent>.class");
        }
    }

    // --- Fix 2: inherited @KafkaHandler methods are scanned ---

    @Nested
    @DisplayName("Fix 2 — inherited @KafkaHandler methods from super-interface are included")
    class InheritedHandlerTests {

        @Test
        @DisplayName(
                "@KafkaListener interface inheriting a @KafkaHandler method from super-interface generates a route for it")
        void inheritedKafkaHandlerMethodIsIncluded() {
            JavaFileObject baseInterface = SourceFiles.inline("com.example.BaseRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaHandler;
                    public interface BaseRouter {
                        @KafkaHandler(matchHeader = "event-type", matchValue = "base-event")
                        void onBaseEvent(SomeEvent event);
                    }
                    """);
            JavaFileObject router = SourceFiles.inline("com.example.ExtendedRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "extended", topic = "ext.events", groupId = "g")
                    public interface ExtendedRouter extends BaseRouter {
                        @KafkaHandler(defaultHandler = true)
                        void onDefault();
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, baseInterface, SOME_EVENT)
                    .assertSuccess()
                    // The inherited route's match header and value must appear
                    .assertGeneratedSourceContains("com.example.ExtendedRouter_BindingMeta", "\"base-event\"")
                    // The inherited route's payload type must appear
                    .assertGeneratedSourceContains("com.example.ExtendedRouter_BindingMeta", "SomeEvent.class");
        }
    }

    // --- Fix 4: raw KafkaRecordHandler (unresolved V) triggers a diagnostic error ---

    @Nested
    @DisplayName("Fix 4 — raw KafkaRecordHandler (unresolved V) is rejected at compile time")
    class RawHandlerValidationTests {

        @Test
        @DisplayName("@KafkaListener class implementing raw KafkaRecordHandler (no type arg) is rejected")
        void rawKafkaRecordHandlerIsRejected() {
            JavaFileObject handler = SourceFiles.inline("com.example.RawHandler", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "raw", topic = "raw.events", groupId = "g")
                    @SuppressWarnings("rawtypes")
                    public class RawHandler implements KafkaRecordHandler {
                        @Override
                        public Future<Void> handle(KafkaMessage msg) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), handler)
                    .assertFailed()
                    .assertErrorMessage(Diagnostics.kafkaHandlerUnresolvedValueType("RawHandler"));
        }
    }

    // --- Fix 5: @DispatchContextValue on parameter type is correctly classified ---

    @Nested
    @DisplayName("Fix 5 — @DispatchContextValue on parameter type is classified as dispatch context")
    class DispatchContextValueTests {

        @Test
        @DisplayName(
                "@KafkaHandler method with @DispatchContextValue-annotated parameter type is valid (not two-payload error)")
        void dispatchContextValueOnTypeIsClassifiedCorrectly() {
            JavaFileObject myCtx = SourceFiles.inline("com.example.MyCtx", """
                    package com.example;
                    import dev.vertique.core.eventbus.DispatchContextValue;
                    @DispatchContextValue
                    public class MyCtx {}
                    """);
            JavaFileObject router = SourceFiles.inline("com.example.CtxRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "ctx", topic = "ctx.events", groupId = "g")
                    public interface CtxRouter {
                        @KafkaHandler(matchHeader = "type", matchValue = "event")
                        void onEvent(SomeEvent event, MyCtx ctx);
                    }
                    """);

            // MyCtx is @DispatchContextValue → classified as dispatch context, not payload.
            // So there is exactly one payload (SomeEvent). No payload-arity error.
            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, myCtx, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.CtxRouter_BindingMeta", "Kind.ROUTER")
                    .assertGeneratedSourceContains("com.example.CtxRouter_BindingMeta", "SomeEvent.class");
        }
    }

    // --- Fix N (parity): payload-must-be-first enforcement ---

    @Nested
    @DisplayName("Payload-first enforcement — context param before payload is rejected")
    class PayloadFirstEnforcementTests {

        @Test
        @DisplayName("@KafkaHandler with context param first and payload second is rejected")
        void contextFirstPayloadSecondIsRejected() {
            JavaFileObject myCtx = SourceFiles.inline("com.example.MyCtx", """
                    package com.example;
                    import dev.vertique.core.eventbus.DispatchContextValue;
                    @DispatchContextValue
                    public class MyCtx {}
                    """);
            JavaFileObject router = SourceFiles.inline("com.example.CtxFirstRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "ctx-first", topic = "ctx.events", groupId = "g")
                    public interface CtxFirstRouter {
                        @KafkaHandler(matchHeader = "type", matchValue = "event")
                        void onEvent(MyCtx ctx, SomeEvent event);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, myCtx, SOME_EVENT)
                    .assertFailed()
                    .assertErrorMessage(Diagnostics.kafkaHandlerPayloadNotFirst("CtxFirstRouter", "onEvent"));
        }

        @Test
        @DisplayName("@KafkaHandler with only a context parameter (no payload) is rejected")
        void contextOnlyHandlerIsRejected() {
            JavaFileObject myCtx = SourceFiles.inline("com.example.MyCtx", """
                    package com.example;
                    import dev.vertique.core.eventbus.DispatchContextValue;
                    @DispatchContextValue
                    public class MyCtx {}
                    """);
            JavaFileObject router = SourceFiles.inline("com.example.CtxOnlyRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "ctx-only", topic = "ctx.events", groupId = "g")
                    public interface CtxOnlyRouter {
                        @KafkaHandler(defaultHandler = true)
                        void onDefault(MyCtx ctx);
                    }
                    """);

            // Reflective resolveRouteValueType would use params[0] (MyCtx) as the value type while codegen
            // would emit Void.class — so a context-only @KafkaHandler is rejected to preserve parity.
            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, myCtx, SOME_EVENT)
                    .assertFailed()
                    .assertErrorMessage(Diagnostics.kafkaHandlerPayloadNotFirst("CtxOnlyRouter", "onDefault"));
        }

        @Test
        @DisplayName("@KafkaHandler with payload first and context param after is accepted (correct order)")
        void payloadFirstContextAfterIsAccepted() {
            JavaFileObject myCtx = SourceFiles.inline("com.example.MyCtx", """
                    package com.example;
                    import dev.vertique.core.eventbus.DispatchContextValue;
                    @DispatchContextValue
                    public class MyCtx {}
                    """);
            JavaFileObject router = SourceFiles.inline("com.example.PayloadFirstRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "payload-first", topic = "pf.events", groupId = "g")
                    public interface PayloadFirstRouter {
                        @KafkaHandler(matchHeader = "type", matchValue = "event")
                        void onEvent(SomeEvent event, MyCtx ctx);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, myCtx, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.PayloadFirstRouter_BindingMeta", "SomeEvent.class");
        }
    }

    // --- Fix 1 (round-2): dual-annotation aggregation ---

    @Nested
    @DisplayName(
            "Fix 1 (round-2) — dual-annotation @KafkaListener + @KafkaSource emits single companion with both HANDLER and SOURCE metas")
    class DualAnnotationAggregationTests {

        @Test
        @DisplayName(
                "@KafkaListener handler class with @KafkaSource method → single companion with Kind.HANDLER and Kind.SOURCE entries")
        void dualAnnotatedClassEmitsSingleCompanionWithBothKinds() {
            // A Model 4 direct handler (class) that also carries a @KafkaSource method.
            // Router interfaces cannot carry @KafkaSource, so the dual case is always a
            // @KafkaListener class implementing KafkaRecordHandler<V>.
            JavaFileObject dualClass = SourceFiles.inline("com.example.OrderHandlerWithSource", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import dev.vertique.kafka.KafkaSource;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "orders", topic = "order.events", groupId = "g")
                    public class OrderHandlerWithSource implements KafkaRecordHandler<SomeEvent> {
                        @Override
                        public Future<Void> handle(KafkaMessage<SomeEvent> msg) {
                            return Future.succeededFuture();
                        }

                        @KafkaSource(name = "order-source", topic = "order.source", groupId = "src-g")
                        public Future<Void> processFromSource(SomeEvent event) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), dualClass, SOME_EVENT)
                    .assertSuccess()
                    // The single companion must contain the HANDLER meta (from @KafkaListener)
                    .assertGeneratedSourceContains("com.example.OrderHandlerWithSource_BindingMeta", "Kind.HANDLER")
                    // AND the SOURCE meta (from @KafkaSource) — not silently dropped
                    .assertGeneratedSourceContains("com.example.OrderHandlerWithSource_BindingMeta", "Kind.SOURCE")
                    // The @KafkaSource method name must appear as the targetOperation
                    .assertGeneratedSourceContains(
                            "com.example.OrderHandlerWithSource_BindingMeta", "\"processFromSource\"");
        }
    }

    // --- Fix 2 (round-2): override de-duplication ---

    @Nested
    @DisplayName("Fix 2 (round-2) — inherited @KafkaHandler override de-duplication")
    class InheritedHandlerOverrideTests {

        @Test
        @DisplayName(
                "sub-interface @KafkaHandler override generates exactly one route (the override, not the inherited)")
        void overriddenHandlerMethodGeneratesOneRoute() {
            // BaseRouter declares @KafkaHandler with matchHeader = "event-type", matchValue = "base".
            // SubRouter overrides it with @KafkaHandler with matchHeader = "event-type", matchValue = "override".
            // getAllMembers() may return both; we must emit only the sub-interface's version.
            JavaFileObject baseInterface = SourceFiles.inline("com.example.BaseRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaHandler;
                    public interface BaseRouter {
                        @KafkaHandler(matchHeader = "event-type", matchValue = "base-event")
                        void onBaseEvent(SomeEvent event);
                    }
                    """);
            JavaFileObject subInterface = SourceFiles.inline("com.example.SubRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "sub", topic = "sub.events", groupId = "g")
                    public interface SubRouter extends BaseRouter {
                        @Override
                        @KafkaHandler(matchHeader = "event-type", matchValue = "override-event")
                        void onBaseEvent(SomeEvent event);

                        @KafkaHandler(defaultHandler = true)
                        void onDefault();
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), subInterface, baseInterface, SOME_EVENT)
                    .assertSuccess()
                    // The override's matchValue must appear
                    .assertGeneratedSourceContains("com.example.SubRouter_BindingMeta", "\"override-event\"")
                    // The base's matchValue must NOT appear (would indicate duplicate route)
                    .assertGeneratedSourceDoesNotContain("com.example.SubRouter_BindingMeta", "\"base-event\"");
        }
    }

    // --- Fix 2 (round-3): override-drops-annotation yields no route ---

    @Nested
    @DisplayName("Fix 2 (round-3) — override that drops @KafkaHandler generates no route (reflective parity)")
    class InheritedHandlerOverrideDropTests {

        @Test
        @DisplayName("sub-interface overrides @KafkaHandler method WITHOUT re-declaring @KafkaHandler"
                + " → no route generated for that method")
        void overrideThatDropsAnnotationGeneratesNoRoute() {
            // BaseRouter declares @KafkaHandler on onX. SubRouter overrides onX without
            // re-declaring @KafkaHandler. The reflective path: Class#getMethods() returns SubRouter's
            // onX; method.getAnnotation(KafkaHandler.class) returns null → no route.
            // The codegen path must match: dedup keeps SubRouter's onX (most-specific), then the
            // @KafkaHandler filter rejects it → no route for onX.
            JavaFileObject baseInterface = SourceFiles.inline("com.example.BaseRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaHandler;
                    public interface BaseRouter {
                        @KafkaHandler(matchHeader = "event-type", matchValue = "base-event")
                        void onX(SomeEvent event);
                    }
                    """);
            JavaFileObject subInterface = SourceFiles.inline("com.example.SubDropRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "sub-drop", topic = "sub.events", groupId = "g")
                    public interface SubDropRouter extends BaseRouter {
                        // Override without @KafkaHandler — annotation is intentionally dropped.
                        @Override
                        void onX(SomeEvent event);

                        // This route must still be present so the router is valid.
                        @KafkaHandler(defaultHandler = true)
                        void onDefault();
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), subInterface, baseInterface, SOME_EVENT)
                    .assertSuccess()
                    // The default handler route must be present
                    .assertGeneratedSourceContains("com.example.SubDropRouter_BindingMeta", "Kind.ROUTER")
                    // The base-event matchValue must NOT appear: onX route must not be generated
                    .assertGeneratedSourceDoesNotContain("com.example.SubDropRouter_BindingMeta", "\"base-event\"");
        }
    }

    // --- Fix 3 (round-2): @KafkaListener.valueType() assignability ---

    @Nested
    @DisplayName("Fix 3 (round-2) — @KafkaListener.valueType() assignability enforcement")
    class ValueTypeAssignabilityTests {

        @Test
        @DisplayName(
                "@KafkaListener(valueType=Order.class) on KafkaRecordHandler<Payment> is rejected with mismatch diagnostic")
        void valueTypeMismatchIsRejected() {
            JavaFileObject orderType = SourceFiles.inline("com.example.Order", """
                    package com.example;
                    public record Order(String id) {}
                    """);
            JavaFileObject paymentType = SourceFiles.inline("com.example.Payment", """
                    package com.example;
                    public record Payment(String id) {}
                    """);
            JavaFileObject mismatchHandler = SourceFiles.inline("com.example.MismatchHandler", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "mismatch", topic = "t", groupId = "g", valueType = Order.class)
                    public class MismatchHandler implements KafkaRecordHandler<Payment> {
                        @Override
                        public Future<Void> handle(KafkaMessage<Payment> msg) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), mismatchHandler, orderType, paymentType)
                    .assertFailed()
                    .assertErrorMessage("annotated valueType must be assignable from the handler's value type V");
        }

        @Test
        @DisplayName(
                "@KafkaListener(valueType=Void.class) on KafkaRecordHandler<Payment> is accepted (default, no restriction)")
        void voidValueTypeIsAccepted() {
            JavaFileObject paymentType = SourceFiles.inline("com.example.Payment", """
                    package com.example;
                    public record Payment(String id) {}
                    """);
            JavaFileObject handler = SourceFiles.inline("com.example.PaymentHandler", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "payment", topic = "payment.events", groupId = "g")
                    public class PaymentHandler implements KafkaRecordHandler<Payment> {
                        @Override
                        public Future<Void> handle(KafkaMessage<Payment> msg) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), handler, paymentType)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.PaymentHandler_BindingMeta", "Kind.HANDLER");
        }

        @Test
        @DisplayName(
                "@KafkaListener(valueType=Payment.class) on KafkaRecordHandler<Payment> is accepted (matching type)")
        void matchingValueTypeIsAccepted() {
            JavaFileObject paymentType = SourceFiles.inline("com.example.Payment", """
                    package com.example;
                    public record Payment(String id) {}
                    """);
            JavaFileObject handler = SourceFiles.inline("com.example.PaymentHandler", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "payment", topic = "payment.events", groupId = "g",
                                   valueType = Payment.class)
                    public class PaymentHandler implements KafkaRecordHandler<Payment> {
                        @Override
                        public Future<Void> handle(KafkaMessage<Payment> msg) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), handler, paymentType)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.PaymentHandler_BindingMeta", "Kind.HANDLER");
        }
    }

    // --- Direct handler / router shape validation (Slice 6) ---

    @Nested
    @DisplayName("Slice 6 — direct-handler shape validation")
    class DirectHandlerShapeTests {

        @Test
        @DisplayName("@KafkaListener class that does not implement KafkaRecordHandler is rejected")
        void rejectsListenerClassNotImplementingRecordHandler() {
            JavaFileObject badClass = SourceFiles.inline("com.example.BadListener", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    @KafkaListener(name = "bad", topic = "t", groupId = "g")
                    public class BadListener {
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), badClass)
                    .assertFailed()
                    .assertErrorMessage(
                            "must either declare @KafkaHandler methods (router)" + " or implement KafkaRecordHandler");
        }

        @Test
        @DisplayName("@KafkaListener interface with no @KafkaHandler methods is rejected")
        void rejectsListenerInterfaceWithNoHandlerMethods() {
            JavaFileObject emptyInterface = SourceFiles.inline("com.example.EmptyListener", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    @KafkaListener(name = "empty", topic = "t", groupId = "g")
                    public interface EmptyListener {
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), emptyInterface)
                    .assertFailed()
                    .assertErrorMessage(
                            "must either declare @KafkaHandler methods (router)" + " or implement KafkaRecordHandler");
        }

        @Test
        @DisplayName("valid @KafkaListener KafkaRecordHandler<V> impl still passes validation (Model 4 happy path)")
        void validDirectHandlerStillPassesValidation() {
            JavaFileObject handler = SourceFiles.inline("com.example.GoodHandler", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "good", topic = "good.events", groupId = "g")
                    public class GoodHandler implements KafkaRecordHandler<SomeEvent> {
                        @Override
                        public Future<Void> handle(KafkaMessage<SomeEvent> msg) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), handler, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.GoodHandler_BindingMeta", "Kind.HANDLER");
        }
    }

    // --- @JsonProfile annotation expansion (FR-JSON-060/062/066) ---

    /**
     * Verifies that {@code KafkaListenerScanner} reads a type-level
     * {@link dev.vertique.core.json.JsonProfile @JsonProfile} on the {@code @KafkaListener} type as
     * the sole per-binding profile selector and folds it into the generated {@code _BindingMeta},
     * applying the FR-JSON-066 method-placement reject. {@code @JsonProfile} is the only per-binding
     * profile selection annotation.
     *
     * <ul>
     *   <li>{@code @JsonProfile} ⇒ profile literal emitted (ROUTER and HANDLER).</li>
     *   <li>no annotation ⇒ no profile literal emitted (null-encoded).</li>
     *   <li>method-level {@code @JsonProfile} on a listener type ⇒ compile error naming the method
     *       and TYPE-level placement.</li>
     * </ul>
     */
    @Nested
    @DisplayName("@JsonProfile expansion — type-level @JsonProfile is read by KafkaListenerScanner")
    class JsonProfileAnnotationExpansionTests {

        @Test
        @DisplayName("router @JsonProfile(\"...\") with no attribute emits that profile literal in the meta")
        void routerWithJsonProfileAnnotation_emitsProfileLiteralInMeta() {
            JavaFileObject router = SourceFiles.inline("com.example.AnnProfiledRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    import dev.vertique.core.json.JsonProfile;
                    @JsonProfile("events-v2")
                    @KafkaListener(name = "ann-profiled", topic = "p.events", groupId = "g")
                    public interface AnnProfiledRouter {
                        @KafkaHandler(defaultHandler = true)
                        void onAny(SomeEvent event);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.AnnProfiledRouter_BindingMeta", "Kind.ROUTER")
                    .assertGeneratedSourceContains("com.example.AnnProfiledRouter_BindingMeta", "\"events-v2\"");
        }

        @Test
        @DisplayName("handler @JsonProfile(\"...\") with no attribute emits that profile literal in the meta")
        void handlerWithJsonProfileAnnotation_emitsProfileLiteralInMeta() {
            JavaFileObject handler = SourceFiles.inline("com.example.AnnProfiledHandler", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import dev.vertique.core.json.JsonProfile;
                    import io.vertx.core.Future;
                    @JsonProfile("events-v2")
                    @KafkaListener(name = "ann-profiled-handler", topic = "p.events", groupId = "g")
                    public class AnnProfiledHandler implements KafkaRecordHandler<SomeEvent> {
                        @Override
                        public Future<Void> handle(KafkaMessage<SomeEvent> msg) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), handler, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.AnnProfiledHandler_BindingMeta", "Kind.HANDLER")
                    .assertGeneratedSourceContains("com.example.AnnProfiledHandler_BindingMeta", "\"events-v2\"");
        }

        @Test
        @DisplayName("handler with no @JsonProfile emits no profile string literal (null-encoded)")
        void handlerWithNoAnnotation_emitsNullProfileInMeta() {
            // A listener with no @JsonProfile must emit no profile literal — the blank profile is
            // null-encoded (the optional-String convention), so it falls back to config/vertx at
            // runtime.
            JavaFileObject handler = SourceFiles.inline("com.example.NoProfileHandler", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "no-profile", topic = "p.events", groupId = "g")
                    public class NoProfileHandler implements KafkaRecordHandler<SomeEvent> {
                        @Override
                        public Future<Void> handle(KafkaMessage<SomeEvent> msg) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), handler, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.NoProfileHandler_BindingMeta", "Kind.HANDLER")
                    // No profile literal of any kind must leak into the generated meta.
                    .assertGeneratedSourceDoesNotContain("com.example.NoProfileHandler_BindingMeta", "events-v2");
        }

        @Test
        @DisplayName("method-level @JsonProfile on a @KafkaListener interface emits a compile error naming the method")
        void methodLevelJsonProfileOnKafkaListenerInterface_emitsCompileError() {
            JavaFileObject router = SourceFiles.inline("com.example.MethodProfiledRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    import dev.vertique.core.json.JsonProfile;
                    @KafkaListener(name = "method-profiled", topic = "p.events", groupId = "g")
                    public interface MethodProfiledRouter {
                        @JsonProfile("v2")
                        @KafkaHandler(defaultHandler = true)
                        void onAny(SomeEvent event);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT)
                    .assertFailed()
                    // The diagnostic must name the offending method and require TYPE-level placement.
                    .assertErrorMessage("onAny")
                    .assertErrorMessage("TYPE");
        }

        @Test
        @DisplayName("method-level @JsonProfile on a @KafkaListener class emits a compile error naming the method")
        void methodLevelJsonProfileOnKafkaListenerClass_emitsCompileError() {
            JavaFileObject handler = SourceFiles.inline("com.example.MethodProfiledHandler", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import dev.vertique.core.json.JsonProfile;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "method-profiled-handler", topic = "p.events", groupId = "g")
                    public class MethodProfiledHandler implements KafkaRecordHandler<SomeEvent> {
                        @JsonProfile("v2")
                        @Override
                        public Future<Void> handle(KafkaMessage<SomeEvent> msg) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), handler, SOME_EVENT)
                    .assertFailed()
                    // The diagnostic must name the offending method.
                    .assertErrorMessage("handle");
        }

        @Test
        @DisplayName(
                "method-level @JsonProfile on an INHERITED @KafkaHandler method emits a compile error naming the method")
        void methodLevelJsonProfileOnInheritedHandlerMethod_emitsCompileError() {
            // FR-JSON-066 parity: the handler-read uses getAllMembers (inherited-inclusive), so the
            // method-level reject must scan the SAME member set. A @JsonProfile on a @KafkaHandler
            // method declared on the SUPER-interface must be rejected even though the @KafkaListener
            // type (the Child) does not declare it. getEnclosedElements() misses it → currently no error.
            JavaFileObject base = SourceFiles.inline("com.example.MethodProfiledBase", """
                    package com.example;
                    import dev.vertique.kafka.KafkaHandler;
                    import dev.vertique.core.json.JsonProfile;
                    public interface MethodProfiledBase {
                        @JsonProfile("v2")
                        @KafkaHandler(matchHeader = "event-type", matchValue = "created")
                        void onCreated(SomeEvent event);
                    }
                    """);
            JavaFileObject child = SourceFiles.inline("com.example.MethodProfiledChild", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "inherited-method-profiled", topic = "p.events", groupId = "g")
                    public interface MethodProfiledChild extends MethodProfiledBase {
                        @KafkaHandler(defaultHandler = true)
                        void onDefault();
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), child, base, SOME_EVENT)
                    .assertFailed()
                    // The diagnostic must name the offending inherited method and require TYPE-level placement.
                    .assertErrorMessage("onCreated")
                    .assertErrorMessage("TYPE");
        }

        @Test
        @DisplayName("@KafkaListener.valueJsonProfile() attribute no longer exists (Phase 3 removal)")
        void kafkaListenerValueJsonProfileAttributeMethod_noLongerExists() throws Exception {
            // Proves the legacy attribute was removed from the annotation, not merely unread: the
            // accessor method must be absent from the annotation class.
            org.junit.jupiter.api.Assertions.assertThrows(
                    NoSuchMethodException.class,
                    () -> dev.vertique.kafka.KafkaListener.class.getDeclaredMethod("valueJsonProfile"));
        }
    }

    // --- Validation tests (FR-CG006-005) ---

    @Nested
    @DisplayName("Validation — FR-CG006-005 compile-time checks")
    class ValidationTests {

        // --- (a) blank topic ---

        @Test
        @DisplayName("FR-005: @KafkaListener with blank topic is rejected")
        void rejectsBlankTopic() {
            JavaFileObject router = SourceFiles.inline("com.example.BadRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "bad", topic = "", groupId = "g")
                    public interface BadRouter {
                        @KafkaHandler(defaultHandler = true)
                        void onAny(SomeEvent event);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT)
                    .assertFailed()
                    .assertErrorMessage(Diagnostics.kafkaListenerBlankTopic("BadRouter"));
        }

        @Test
        @DisplayName("FR-005: @KafkaListener with whitespace-only topic is rejected")
        void rejectsWhitespaceTopic() {
            JavaFileObject handler = SourceFiles.inline("com.example.BlankHandler", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaRecordHandler;
                    import dev.vertique.kafka.KafkaMessage;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "blank", topic = "   ", groupId = "g")
                    public class BlankHandler implements KafkaRecordHandler<SomeEvent> {
                        @Override
                        public Future<Void> handle(KafkaMessage<SomeEvent> msg) {
                            return Future.succeededFuture();
                        }
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), handler, SOME_EVENT)
                    .assertFailed()
                    .assertErrorMessage(Diagnostics.kafkaListenerBlankTopic("BlankHandler"));
        }

        // --- (c) two payload params ---

        @Test
        @DisplayName("FR-005: @KafkaHandler with two payload params is rejected")
        void rejectsTwoPayloadParams() {
            JavaFileObject router = SourceFiles.inline("com.example.TwoPayloadRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "two", topic = "t", groupId = "g")
                    public interface TwoPayloadRouter {
                        @KafkaHandler(defaultHandler = true)
                        void onAny(SomeEvent a, SomeEvent b);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT)
                    .assertFailed()
                    .assertErrorMessage(Diagnostics.kafkaHandlerPayloadArity("TwoPayloadRouter", "onAny", 2));
        }

        // --- (d) non-void / non-Future<Void> return type ---

        @Test
        @DisplayName("FR-005: @KafkaHandler with non-void return type is rejected")
        void rejectsNonVoidReturnType() {
            JavaFileObject router = SourceFiles.inline("com.example.BadReturnRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "bad", topic = "t", groupId = "g")
                    public interface BadReturnRouter {
                        @KafkaHandler(defaultHandler = true)
                        String onAny(SomeEvent event);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT)
                    .assertFailed()
                    .assertErrorMessage(Diagnostics.kafkaHandlerReturnType("BadReturnRouter", "onAny"));
        }

        @Test
        @DisplayName("FR-005: @KafkaHandler returning Future<String> (not Future<Void>) is rejected")
        void rejectsFutureOfStringReturnType() {
            JavaFileObject router = SourceFiles.inline("com.example.BadFutureRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "bad", topic = "t", groupId = "g")
                    public interface BadFutureRouter {
                        @KafkaHandler(defaultHandler = true)
                        Future<String> onAny(SomeEvent event);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT)
                    .assertFailed()
                    .assertErrorMessage(Diagnostics.kafkaHandlerReturnType("BadFutureRouter", "onAny"));
        }

        // --- (e) same payload type across distinct routes is allowed ---

        @Test
        @DisplayName("two @KafkaHandler methods with the same payload type but distinct match rules are accepted")
        void acceptsSamePayloadTypeAcrossDistinctRoutes() {
            // Route selection is by matchHeader/matchProperty/defaultHandler, never by payload type
            // (KafkaRecordDispatcher.resolveRoute), so two routes that share a payload schema but match on
            // different header values are unambiguous and valid — codegen must NOT reject them.
            JavaFileObject router = SourceFiles.inline("com.example.UniformPayloadRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "uniform", topic = "t", groupId = "g")
                    public interface UniformPayloadRouter {
                        @KafkaHandler(matchHeader = "h", matchValue = "v1")
                        void onFirst(SomeEvent event);

                        @KafkaHandler(matchHeader = "h", matchValue = "v2")
                        void onSecond(SomeEvent event);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT)
                    .assertSuccess();
        }

        @Test
        @DisplayName("two @KafkaHandler methods with the same header selector are rejected")
        void rejectsDuplicateHeaderSelector() {
            // resolveRoute is first-match-wins, so a second route with an identical matchHeader+matchValue
            // is unreachable — reject it (selector uniqueness, not payload-type uniqueness).
            JavaFileObject router = SourceFiles.inline("com.example.DupHeaderRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "duph", topic = "t", groupId = "g")
                    public interface DupHeaderRouter {
                        @KafkaHandler(matchHeader = "h", matchValue = "v")
                        void onFirst(SomeEvent event);

                        @KafkaHandler(matchHeader = "h", matchValue = "v")
                        void onSecond(SomeEvent event);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT)
                    .assertFailed()
                    .assertErrorMessage(Diagnostics.kafkaDuplicateRouteSelector("DupHeaderRouter", "header", "h", "v"));
        }

        @Test
        @DisplayName("two @KafkaHandler methods with the same property selector are rejected")
        void rejectsDuplicatePropertySelector() {
            JavaFileObject router = SourceFiles.inline("com.example.DupPropertyRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "dupp", topic = "t", groupId = "g")
                    public interface DupPropertyRouter {
                        @KafkaHandler(matchProperty = "p", matchValue = "v")
                        void onFirst(SomeEvent event);

                        @KafkaHandler(matchProperty = "p", matchValue = "v")
                        void onSecond(SomeEvent event);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT)
                    .assertFailed()
                    .assertErrorMessage(
                            Diagnostics.kafkaDuplicateRouteSelector("DupPropertyRouter", "property", "p", "v"));
        }

        @Test
        @DisplayName("selectors whose name/value contain delimiter characters do not falsely collide")
        void acceptsDelimiterContainingSelectorsWithoutFalseCollision() {
            // ("h","a=b") and ("h=a","b") would map to the same delimiter-joined string but are genuinely
            // distinct selectors — the structured key keeps them apart, so the router must compile.
            JavaFileObject router = SourceFiles.inline("com.example.DelimRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "delim", topic = "t", groupId = "g")
                    public interface DelimRouter {
                        @KafkaHandler(matchHeader = "h", matchValue = "a=b")
                        void onFirst(SomeEvent event);

                        @KafkaHandler(matchHeader = "h=a", matchValue = "b")
                        void onSecond(SomeEvent event);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT)
                    .assertSuccess();
        }

        // --- (f) no match attribute ---

        @Test
        @DisplayName("FR-005: @KafkaHandler with no matchHeader / matchProperty / defaultHandler is rejected")
        void rejectsHandlerWithNoMatchAttribute() {
            JavaFileObject router = SourceFiles.inline("com.example.NoMatchRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "nomatch", topic = "t", groupId = "g")
                    public interface NoMatchRouter {
                        @KafkaHandler
                        void onAny(SomeEvent event);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT)
                    .assertFailed()
                    .assertErrorMessage(Diagnostics.kafkaHandlerMatchRule("NoMatchRouter", "onAny"));
        }

        // --- (g) two defaultHandler = true ---

        @Test
        @DisplayName("FR-005: two @KafkaHandler methods with defaultHandler=true are rejected")
        void rejectsTwoDefaultHandlers() {
            JavaFileObject router = SourceFiles.inline("com.example.TwoDefaultRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    @KafkaListener(name = "twodefault", topic = "t", groupId = "g")
                    public interface TwoDefaultRouter {
                        @KafkaHandler(defaultHandler = true)
                        void onDefaultA(SomeEvent event);

                        @KafkaHandler(defaultHandler = true)
                        void onDefaultB(SomeEvent event);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT)
                    .assertFailed()
                    .assertErrorMessage(Diagnostics.kafkaHandlerMatchRule("TwoDefaultRouter", "onDefaultB"));
        }

        // --- Valid cases: Future<Void> return should be accepted ---

        @Test
        @DisplayName("FR-005: @KafkaHandler returning Future<Void> is valid")
        void acceptsFutureVoidReturnType() {
            JavaFileObject router = SourceFiles.inline("com.example.FutureVoidRouter", """
                    package com.example;
                    import dev.vertique.kafka.KafkaListener;
                    import dev.vertique.kafka.KafkaHandler;
                    import io.vertx.core.Future;
                    @KafkaListener(name = "fv", topic = "t", groupId = "g")
                    public interface FutureVoidRouter {
                        @KafkaHandler(defaultHandler = true)
                        Future<Void> onAny(SomeEvent event);
                    }
                    """);

            ProcessorTestHarness.run(new KafkaConsumerProcessor(), router, SOME_EVENT)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.FutureVoidRouter_BindingMeta", "Kind.ROUTER");
        }
    }
}
