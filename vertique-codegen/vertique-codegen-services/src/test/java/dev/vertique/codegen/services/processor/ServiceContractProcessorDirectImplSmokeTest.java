// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smoke test that verifies a minimal direct-impl contract compiles and produces a
 * {@code _ContractContributor} source file plus a {@code GeneratedServicesModule}.
 *
 * <p>Verifies the end-to-end scaffold: processor discovers the impl, extracts the model,
 * validates it, and emits both files.
 */
class ServiceContractProcessorDirectImplSmokeTest {

    // --- Minimal fixture sources ---

    private static final String SERVICE_CONTRACT_SOURCE = """
            package dev.vertique.services;
            import java.lang.annotation.*;
            @Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME) @Documented
            public @interface ServiceContract {
                String value();
                String namespace() default "";
            }
            """;

    private static final String SERVICE_OPERATION_SOURCE = """
            package dev.vertique.services;
            import java.lang.annotation.*;
            @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @Documented
            public @interface ServiceOperation {
                String value() default "";
            }
            """;

    private static final String ONE_WAY_SOURCE = """
            package dev.vertique.services;
            import java.lang.annotation.*;
            @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @Documented
            public @interface OneWay {}
            """;

    private static final String SERVICE_CONTRACT_CONTRIBUTOR_SOURCE = """
            package dev.vertique.services;
            import io.vertx.core.json.JsonObject;
            import java.util.List;
            public interface ServiceContractContributor {
                List<dev.vertique.services.ServiceContractRegistry.ContractEntry<?>> contribute(JsonObject config);
            }
            """;

    private static final String CONTRACT_ENTRY_SOURCE = """
            package dev.vertique.services;
            public class ServiceContractRegistry {
                public record ContractEntry<T>(Class<T> contract) {}
            }
            """;

    private static final String SERVICE_CONTRACT_ENTRIES_SOURCE = """
            package dev.vertique.services;
            import io.vertx.core.json.JsonObject;
            public final class ServiceContractEntries {
                private ServiceContractEntries() {}
                public static EntryBuilder deployable() { return new EntryBuilder(); }
                public static final class EntryBuilder {
                    public EntryBuilder contract(Class<?> c) { return this; }
                    public EntryBuilder serviceInstance(Object o) { return this; }
                    public EntryBuilder namespace(String n) { return this; }
                    public EntryBuilder name(String n) { return this; }
                    public OperationBuilder operation(String op) { return new OperationBuilder(this); }
                    public EntryBuilder deploymentOptions(JsonObject cfg, String... path) { return this; }
                    public dev.vertique.services.ServiceContractRegistry.ContractEntry<?> build() { return null; }
                }
                public static final class OperationBuilder {
                    private final EntryBuilder parent;
                    OperationBuilder(EntryBuilder parent) { this.parent = parent; }
                    public OperationBuilder method(java.lang.reflect.Method m) { return this; }
                    public OperationBuilder handlerMethod(java.lang.reflect.Method m) { return this; }
                    public OperationBuilder returnType(Class<?> t) { return this; }
                    public OperationBuilder payloadType(Class<?> t) { return this; }
                    public OperationBuilder param(String name, Object src, Class<?> t) { return this; }
                    public OperationBuilder handlerParam(String name, Object src, Class<?> t) { return this; }
                    public OperationBuilder oneWay() { return this; }
                    public OperationBuilder resilienceAnnotations(Object a) { return this; }
                    public OperationBuilder methodAnnotations(java.util.List<java.lang.annotation.Annotation> a) { return this; }
                    public OperationBuilder classAnnotations(java.util.List<java.lang.annotation.Annotation> a) { return this; }
                    public EntryBuilder done() { return parent; }
                }
            }
            """;

    private static final String PARAM_SOURCE_ENUM_SOURCE = """
            package dev.vertique.services.dispatch;
            public class ServiceMethodMeta {
                public enum ParamSource { PAYLOAD, DISPATCH_CONTEXT }
            }
            """;

    private static final String RESILIENCE_ANNOTATIONS_SOURCE = """
            package dev.vertique.core.resilience;
            import java.lang.reflect.Method;
            public final class ResilienceAnnotations {
                public static ResilienceAnnotations resolve(Class<?> c, Method m) { return null; }
            }
            """;

    private static final String ANNOTATION_RESOLVER_SOURCE = """
            package dev.vertique.core.util;
            import java.lang.annotation.Annotation;
            import java.lang.reflect.Method;
            import java.util.List;
            public final class AnnotationResolver {
                public static List<Annotation> resolveMethodAnnotations(Method m) { return List.of(); }
                public static List<Annotation> resolveClassAnnotations(Class<?> c) { return List.of(); }
            }
            """;

    private static final String FUTURE_SOURCE = """
            package io.vertx.core;
            public interface Future<T> {
                static <T> Future<T> succeededFuture(T value) { return null; }
                static <T> Future<T> succeededFuture() { return null; }
                static <T> Future<T> failedFuture(Throwable t) { return null; }
            }
            """;

    private static final String JSON_OBJECT_SOURCE = """
            package io.vertx.core.json;
            public class JsonObject {}
            """;

    @Test
    @DisplayName("direct-impl generates _ContractContributor and module")
    void directImpl_generatesContributorAndModule() {
        var result = ProcessorTestHarness.run(
                new ServiceContractProcessor(),
                SourceFiles.inline("io.vertx.core.Future", FUTURE_SOURCE),
                SourceFiles.inline("io.vertx.core.json.JsonObject", JSON_OBJECT_SOURCE),
                SourceFiles.inline("dev.vertique.services.ServiceContract", SERVICE_CONTRACT_SOURCE),
                SourceFiles.inline("dev.vertique.services.ServiceOperation", SERVICE_OPERATION_SOURCE),
                SourceFiles.inline("dev.vertique.services.OneWay", ONE_WAY_SOURCE),
                SourceFiles.inline(
                        "dev.vertique.services.ServiceContractContributor", SERVICE_CONTRACT_CONTRIBUTOR_SOURCE),
                SourceFiles.inline("dev.vertique.services.ServiceContractRegistry", CONTRACT_ENTRY_SOURCE),
                SourceFiles.inline("dev.vertique.services.ServiceContractEntries", SERVICE_CONTRACT_ENTRIES_SOURCE),
                SourceFiles.inline("dev.vertique.services.dispatch.ServiceMethodMeta", PARAM_SOURCE_ENUM_SOURCE),
                SourceFiles.inline("dev.vertique.core.resilience.ResilienceAnnotations", RESILIENCE_ANNOTATIONS_SOURCE),
                SourceFiles.inline("dev.vertique.core.util.AnnotationResolver", ANNOTATION_RESOLVER_SOURCE),
                SourceFiles.inline("com.example.UserService", """
                        package com.example;
                        import dev.vertique.services.ServiceContract;
                        import io.vertx.core.Future;
                        @ServiceContract(value = "user-service", namespace = "integration")
                        public interface UserService {
                            @dev.vertique.services.ServiceOperation("get-user")
                            Future<String> getUser(String userId);
                        }
                        """),
                SourceFiles.inline("com.example.UserServiceImpl", """
                        package com.example;
                        import jakarta.inject.Inject;
                        import io.vertx.core.Future;
                        public class UserServiceImpl implements UserService {
                            @Inject UserServiceImpl() {}
                            @Override public Future<String> getUser(String userId) {
                                return Future.succeededFuture(userId);
                            }
                        }
                        """));

        result.assertSuccess();
        // Should have generated UserService_ContractContributor
        result.assertGeneratedSourceContains(
                "com.example.UserService_ContractContributor", "ServiceContractContributor");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "UserServiceImpl");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "get-user");
        // Should have generated the module
        result.assertGeneratedSourceContains("com.example.GeneratedServicesModule", "ServiceContractContributor");
    }

    @Test
    @DisplayName("@NoAutoWire impl is skipped — no contributor generated")
    void noAutoWireImpl_noContributorGenerated() {
        var result = ProcessorTestHarness.run(
                new ServiceContractProcessor(),
                SourceFiles.inline("io.vertx.core.Future", FUTURE_SOURCE),
                SourceFiles.inline("dev.vertique.services.ServiceContract", SERVICE_CONTRACT_SOURCE),
                SourceFiles.inline("com.example.UserService", """
                        package com.example;
                        import dev.vertique.services.ServiceContract;
                        import io.vertx.core.Future;
                        @ServiceContract(value = "user-service")
                        public interface UserService {
                            Future<String> getUser(String userId);
                        }
                        """),
                SourceFiles.inline("com.example.UserServiceImpl", """
                        package com.example;
                        import dev.vertique.codegen.NoAutoWire;
                        import jakarta.inject.Inject;
                        import io.vertx.core.Future;
                        @NoAutoWire
                        public class UserServiceImpl implements UserService {
                            @Inject UserServiceImpl() {}
                            @Override public Future<String> getUser(String userId) {
                                return Future.succeededFuture(userId);
                            }
                        }
                        """));

        result.assertSuccess();
        // @NoAutoWire skips codegen; the manual @Services path stays in user code
        var generated = result.compilation().generatedSourceFile("com.example.UserService_ContractContributor");
        org.junit.jupiter.api.Assertions.assertTrue(
                generated.isEmpty(), "Expected no contributor when impl is annotated @NoAutoWire");
    }
}
