// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import static dev.vertique.codegen.services.processor.ServiceContractTestFixtures.FRAMEWORK_SOURCES;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that handler methods with user-defined {@code @DispatchContextValue}-annotated
 * context-type parameters are accepted and classified as DISPATCH_CONTEXT, not rejected as a
 * second payload.
 *
 * <p>{@code @DispatchContextValue} is a {@code @Target(TYPE)} annotation — it decorates the
 * user-defined context type declaration, not the parameter usage site. This test confirms that
 * the processor correctly inspects the parameter's <em>type element</em> for the annotation,
 * rather than the parameter element itself (which would always return {@code false}).
 *
 * <p>Also covers the {@code DispatchEnvelope<?>} rejection path on handler methods, mirroring
 * {@link ServiceContractProcessorPayloadParamTest#bodyParam_failsWithBodyError()} for contract
 * methods.
 */
class ServiceContractProcessorContextParamTest {

    @Test
    @DisplayName("handler with @DispatchContextValue context-type param is accepted")
    void handlerWithDispatchContextValueType_isAccepted() {
        JavaFileObject contextType = SourceFiles.inline("com.example.MyOrderCtx", """
                        package com.example;
                        import dev.vertique.core.eventbus.DispatchContextValue;
                        @DispatchContextValue
                        public class MyOrderCtx {}
                        """);

        JavaFileObject contract = SourceFiles.inline("com.example.OrderService", """
                        package com.example;
                        import dev.vertique.services.ServiceContract;
                        import dev.vertique.services.ServiceOperation;
                        import io.vertx.core.Future;
                        @ServiceContract(value = "order-service", namespace = "integration")
                        public interface OrderService {
                            @ServiceOperation("process")
                            Future<String> process(String orderId);
                        }
                        """);

        JavaFileObject handler = SourceFiles.inline("com.example.OrderServiceHandler", """
                        package com.example;
                        import dev.vertique.services.ServiceHandler;
                        import jakarta.inject.Inject;
                        import io.vertx.core.Future;
                        public class OrderServiceHandler implements ServiceHandler<OrderService> {
                            @Inject OrderServiceHandler() {}
                            public Future<String> process(String orderId, MyOrderCtx ctx) {
                                return Future.succeededFuture(orderId);
                            }
                        }
                        """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contextType, contract, handler);

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();
    }

    @Test
    @DisplayName("handler method with DispatchEnvelope<?> param → FAILED with DispatchEnvelope error")
    void handlerWithDispatchEnvelopeParam_isRejected() {
        JavaFileObject envelopeType = SourceFiles.inline("dev.vertique.core.eventbus.DispatchEnvelope", """
                        package dev.vertique.core.eventbus;
                        public class DispatchEnvelope<T> {}
                        """);

        JavaFileObject contract = SourceFiles.inline("com.example.OrderService", """
                        package com.example;
                        import dev.vertique.services.ServiceContract;
                        import dev.vertique.services.ServiceOperation;
                        import io.vertx.core.Future;
                        @ServiceContract(value = "order-service", namespace = "integration")
                        public interface OrderService {
                            @ServiceOperation("process")
                            Future<String> process(String orderId);
                        }
                        """);

        JavaFileObject handler = SourceFiles.inline("com.example.OrderServiceHandler", """
                        package com.example;
                        import dev.vertique.services.ServiceHandler;
                        import dev.vertique.core.eventbus.DispatchEnvelope;
                        import jakarta.inject.Inject;
                        import io.vertx.core.Future;
                        public class OrderServiceHandler implements ServiceHandler<OrderService> {
                            @Inject OrderServiceHandler() {}
                            public Future<String> process(DispatchEnvelope<String> envelope) {
                                return Future.succeededFuture("ok");
                            }
                        }
                        """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, envelopeType, contract, handler);

        ProcessorTestHarness.run(new ServiceContractProcessor(), sources)
                .assertFailed()
                .assertErrorMessage("DispatchEnvelope");
    }

    // --- Helper ---

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        var result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
