// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code @Resilient} anchor on an operation of a service contract is applied once, by the services
 * dispatch pipeline, so the AOP processor must not weave it onto the contract's implementor. Every
 * other aspect trigger on the same method, and every other interface, is still woven.
 */
class ServiceContractResilientAnchorTest {

    private static final String IMPL_PROXY_FQN = "com.example.OrderServiceImpl$AopProxy";

    @Test
    @DisplayName("a @Resilient-only service-contract operation is not woven onto its implementor")
    void resilientOnlyContractOperationIsNotWoven() {
        var result = ProcessorTestHarness.run(
                        new AopProcessor(),
                        resilient(),
                        serviceContract(),
                        contract("@Resilient"),
                        implementor("OrderServiceImpl", "OrderService"))
                .assertSuccess();

        assertFalse(
                result.compilation().generatedSourceFile(IMPL_PROXY_FQN).isPresent(),
                "the services pipeline owns the anchor, so the implementor gets no proxy");
    }

    @Test
    @DisplayName("an operation a contract inherits from a plain interface is not woven either")
    void resilientOperationInheritedByAContractIsNotWoven() {
        var result = ProcessorTestHarness.run(
                        new AopProcessor(),
                        resilient(),
                        serviceContract(),
                        SourceFiles.inline("com.example.Base", """
                                package com.example;
                                import dev.vertique.resilience.annotation.Resilient;
                                import io.vertx.core.Future;
                                public interface Base {
                                    @Resilient
                                    Future<String> place(String id);
                                }
                                """),
                        SourceFiles.inline("com.example.OrderService", """
                                package com.example;
                                import dev.vertique.services.ServiceContract;
                                @ServiceContract(namespace = "orders", value = "order")
                                public interface OrderService extends Base {}
                                """),
                        implementor("OrderServiceImpl", "OrderService"))
                .assertSuccess();

        assertFalse(result.compilation().generatedSourceFile(IMPL_PROXY_FQN).isPresent());
    }

    @Test
    @DisplayName("another aspect on the same contract operation is still woven, without @Resilient")
    void otherAspectOnAContractOperationIsStillWoven() {
        var result = ProcessorTestHarness.run(
                        new AopProcessor(),
                        resilient(),
                        serviceContract(),
                        audited(),
                        contract("@Resilient @Audited"),
                        implementor("OrderServiceImpl", "OrderService"))
                .assertSuccess();

        String source = GeneratedSources.of(result, IMPL_PROXY_FQN);
        // The method's runtime annotations stay in its metadata; only the interceptor chain differs.
        assertFalse(source.contains("AspectProvider<Resilient>"), "no @Resilient interceptor is requested");
        assertFalse(source.contains("RESILIENT_"), "no @Resilient occurrence literal backs a chain entry");
        result.assertGeneratedSourceContains(IMPL_PROXY_FQN, "AspectProvider<Audited>");
    }

    @Test
    @DisplayName(
            "a plain interface's aspect on a signature the contract shares is woven whichever interface comes first")
    void plainInterfaceAspectSurvivesASharedContractSignature() {
        for (String order : new String[] {"OrderService, Auditable", "Auditable, OrderService"}) {
            var result = ProcessorTestHarness.run(
                            new AopProcessor(),
                            resilient(),
                            serviceContract(),
                            audited(),
                            contract("@Resilient"),
                            SourceFiles.inline("com.example.Auditable", """
                                    package com.example;
                                    import io.vertx.core.Future;
                                    public interface Auditable {
                                        @Audited
                                        Future<String> place(String id);
                                    }
                                    """),
                            SourceFiles.inline("com.example.OrderServiceImpl", """
                                    package com.example;
                                    import io.vertx.core.Future;
                                    import jakarta.inject.Inject;
                                    public class OrderServiceImpl implements %s {
                                        @Inject
                                        public OrderServiceImpl() {}
                                        @Override
                                        public Future<String> place(String id) {
                                            return Future.succeededFuture(id);
                                        }
                                    }
                                    """.formatted(order)))
                    .assertSuccess();

            assertTrue(
                    GeneratedSources.of(result, IMPL_PROXY_FQN).contains("AspectProvider<Audited>"),
                    "@Audited must be woven for: " + order);
        }
    }

    @Test
    @DisplayName("a @Resilient anchor on a plain interface is still woven onto its implementor")
    void resilientOnAPlainInterfaceIsStillWoven() {
        ProcessorTestHarness.run(
                        new AopProcessor(),
                        resilient(),
                        SourceFiles.inline("com.example.OrderService", """
                                package com.example;
                                import dev.vertique.resilience.annotation.Resilient;
                                import io.vertx.core.Future;
                                public interface OrderService {
                                    @Resilient
                                    Future<String> place(String id);
                                }
                                """),
                        implementor("OrderServiceImpl", "OrderService"))
                .assertSuccess()
                .assertGeneratedSourceContains(IMPL_PROXY_FQN, "Resilient");
    }

    @Test
    @DisplayName("a @Resilient anchor declared on the implementing class is still woven")
    void resilientOnTheImplementorClassIsStillWoven() {
        ProcessorTestHarness.run(
                        new AopProcessor(),
                        resilient(),
                        serviceContract(),
                        contract(""),
                        SourceFiles.inline("com.example.OrderServiceImpl", """
                                package com.example;
                                import dev.vertique.resilience.annotation.Resilient;
                                import io.vertx.core.Future;
                                import jakarta.inject.Inject;
                                public class OrderServiceImpl implements OrderService {
                                    @Inject
                                    public OrderServiceImpl() {}
                                    @Override
                                    @Resilient
                                    public Future<String> place(String id) {
                                        return Future.succeededFuture(id);
                                    }
                                }
                                """))
                .assertSuccess()
                .assertGeneratedSourceContains(IMPL_PROXY_FQN, "Resilient");
    }

    private static JavaFileObject resilient() {
        return SourceFiles.inline("dev.vertique.resilience.annotation.Resilient", """
                package dev.vertique.resilience.annotation;
                import dev.vertique.aop.Aspect;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @Aspect(ordering = 50)
                @Target(ElementType.METHOD)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Resilient {}
                """);
    }

    private static JavaFileObject audited() {
        return SourceFiles.inline("com.example.Audited", """
                package com.example;
                import dev.vertique.aop.Aspect;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @Aspect(ordering = 10)
                @Target(ElementType.METHOD)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Audited {}
                """);
    }

    private static JavaFileObject serviceContract() {
        return SourceFiles.inline("dev.vertique.services.ServiceContract", """
                package dev.vertique.services;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @Target(ElementType.TYPE)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface ServiceContract {
                    String namespace();
                    String value();
                }
                """);
    }

    private static JavaFileObject contract(String methodAnnotations) {
        return SourceFiles.inline("com.example.OrderService", """
                package com.example;
                import dev.vertique.resilience.annotation.Resilient;
                import dev.vertique.services.ServiceContract;
                import io.vertx.core.Future;
                @ServiceContract(namespace = "orders", value = "order")
                public interface OrderService {
                    %s
                    Future<String> place(String id);
                }
                """.formatted(methodAnnotations));
    }

    private static JavaFileObject implementor(String simpleName, String contract) {
        return SourceFiles.inline("com.example." + simpleName, """
                package com.example;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class %s implements %s {
                    @Inject
                    public %s() {}
                    @Override
                    public Future<String> place(String id) {
                        return Future.succeededFuture(id);
                    }
                }
                """.formatted(simpleName, contract, simpleName));
    }
}
