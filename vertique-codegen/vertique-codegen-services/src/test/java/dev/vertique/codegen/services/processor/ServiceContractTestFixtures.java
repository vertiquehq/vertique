// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;

/**
 * Shared source-fixture constants used across all {@link ServiceContractProcessor} tests.
 *
 * <p>Each constant is a minimal stub of a framework type that the processor references at
 * compile time. The stubs are sufficient to let the processor's generated code compile in the
 * in-memory test compiler without pulling in any real runtime dependencies.
 *
 * <p>All test classes in this package inherit these fixtures via the {@link #FRAMEWORK_SOURCES}
 * array. Individual tests may add domain-specific sources on top.
 */
final class ServiceContractTestFixtures {

    // --- Framework stubs ---

    static final JavaFileObject FUTURE_SOURCE = SourceFiles.inline("io.vertx.core.Future", """
            package io.vertx.core;
            import java.util.function.Function;
            public interface Future<T> {
                static <T> Future<T> succeededFuture(T value) { return null; }
                static <T> Future<T> succeededFuture() { return null; }
                static <T> Future<T> failedFuture(Throwable t) { return null; }
                <U> Future<U> compose(Function<? super T, Future<U>> mapper);
            }
            """);

    static final JavaFileObject JSON_OBJECT_SOURCE = SourceFiles.inline("io.vertx.core.json.JsonObject", """
            package io.vertx.core.json;
            public class JsonObject {}
            """);

    static final JavaFileObject SERVICE_CONTRACT_SOURCE =
            SourceFiles.inline("dev.vertique.services.ServiceContract", """
                    package dev.vertique.services;
                    import java.lang.annotation.*;
                    @Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME) @Documented
                    public @interface ServiceContract {
                        String value();
                        String namespace() default "";
                    }
                    """);

    static final JavaFileObject SERVICE_OPERATION_SOURCE =
            SourceFiles.inline("dev.vertique.services.ServiceOperation", """
                    package dev.vertique.services;
                    import java.lang.annotation.*;
                    @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @Documented
                    public @interface ServiceOperation {
                        String value() default "";
                    }
                    """);

    static final JavaFileObject ONE_WAY_SOURCE = SourceFiles.inline("dev.vertique.services.OneWay", """
            package dev.vertique.services;
            import java.lang.annotation.*;
            @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @Documented
            public @interface OneWay {}
            """);

    static final JavaFileObject SERVICE_HANDLER_SOURCE =
            SourceFiles.inline("dev.vertique.services.ServiceHandler", """
                    package dev.vertique.services;
                    public interface ServiceHandler<C> {}
                    """);

    static final JavaFileObject SERVICE_CONTRACT_CONTRIBUTOR_SOURCE =
            SourceFiles.inline("dev.vertique.services.ServiceContractContributor", """
                    package dev.vertique.services;
                    import io.vertx.core.json.JsonObject;
                    import java.util.List;
                    public interface ServiceContractContributor {
                        List<dev.vertique.services.ServiceContractRegistry.ContractEntry<?>> contribute(JsonObject config);
                    }
                    """);

    static final JavaFileObject SERVICE_CLIENT_FACTORY_SOURCE =
            SourceFiles.inline("dev.vertique.services.ServiceClientFactory", """
                    package dev.vertique.services;
                    public class ServiceClientFactory {
                        public <T> T create(Class<T> contract) { return null; }
                    }
                    """);

    static final JavaFileObject CONTRACT_ENTRY_SOURCE =
            SourceFiles.inline("dev.vertique.services.ServiceContractRegistry", """
                    package dev.vertique.services;
                    import dev.vertique.services.dispatch.ServiceMethodMeta;
                    import java.util.Map;
                    public class ServiceContractRegistry {
                        public record ContractEntry<T>(Class<T> contract, Map<String, ServiceMethodMeta> operations) {}
                    }
                    """);

    static final JavaFileObject SERVICE_CONTRACT_ENTRIES_SOURCE =
            SourceFiles.inline("dev.vertique.services.ServiceContractEntries", """
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
                    """);

    static final JavaFileObject PARAM_SOURCE_ENUM_SOURCE =
            SourceFiles.inline("dev.vertique.services.dispatch.ServiceMethodMeta", """
                    package dev.vertique.services.dispatch;
                    import java.util.List;
                    public class ServiceMethodMeta {
                        public enum ParamSource { PAYLOAD, DISPATCH_CONTEXT }
                        public record ParamMeta(String name, ParamSource source, Class<?> type, String lookupKey) {}
                        public List<ParamMeta> params() { return List.of(); }
                        public boolean oneWay() { return false; }
                    }
                    """);

    static final JavaFileObject RESILIENCE_ANNOTATIONS_SOURCE =
            SourceFiles.inline("dev.vertique.resilience.annotation.ResilienceAnnotations", """
                    package dev.vertique.resilience.annotation;
                    import java.lang.reflect.Method;
                    public final class ResilienceAnnotations {
                        public static ResilienceAnnotations resolve(Class<?> c, Method m) { return null; }
                    }
                    """);

    static final JavaFileObject ANNOTATION_RESOLVER_SOURCE =
            SourceFiles.inline("dev.vertique.core.util.AnnotationResolver", """
                    package dev.vertique.core.util;
                    import java.lang.annotation.Annotation;
                    import java.lang.reflect.Method;
                    import java.util.List;
                    public final class AnnotationResolver {
                        public static List<Annotation> resolveMethodAnnotations(Method m) { return List.of(); }
                        public static List<Annotation> resolveClassAnnotations(Class<?> c) { return List.of(); }
                    }
                    """);

    static final JavaFileObject SECURITY_CONTEXT_SOURCE =
            SourceFiles.inline("dev.vertique.security.SecurityContext", """
                    package dev.vertique.security;
                    public interface SecurityContext {}
                    """);

    static final JavaFileObject DISPATCH_CONTEXT_VALUE_SOURCE =
            SourceFiles.inline("dev.vertique.core.eventbus.DispatchContextValue", """
                    package dev.vertique.core.eventbus;
                    import java.lang.annotation.*;
                    @Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME)
                    public @interface DispatchContextValue {}
                    """);

    static final JavaFileObject PROVIDER_SOURCE = SourceFiles.inline("jakarta.inject.Provider", """
            package jakarta.inject;
            public interface Provider<T> {
                T get();
            }
            """);

    static final JavaFileObject CONDITION_SOURCE =
            SourceFiles.inline("dev.vertique.core.config.PropertyCondition", """
            package dev.vertique.core.config;
            import io.vertx.core.json.JsonObject;
            public record PropertyCondition(String name, String havingValue, boolean matchIfMissing) {
                public static boolean matchesAll(JsonObject config, PropertyCondition[] conditions) {
                    return true;
                }
            }
            """);

    static final JavaFileObject SERVICE_REGISTRATION_VIOLATION_SOURCE =
            SourceFiles.inline("dev.vertique.services.ServiceRegistrationViolation", """
                    package dev.vertique.services;
                    public record ServiceRegistrationViolation(Class<?> contract, String method, String message) {
                        public static ServiceRegistrationViolation ofType(Class<?> contract, String message) {
                            return new ServiceRegistrationViolation(contract, null, message);
                        }
                    }
                    """);

    static final JavaFileObject SERVICE_REGISTRATION_EXCEPTION_SOURCE =
            SourceFiles.inline("dev.vertique.services.ServiceRegistrationException", """
                    package dev.vertique.services;
                    import java.util.List;
                    public class ServiceRegistrationException extends RuntimeException {
                        public ServiceRegistrationException(List<ServiceRegistrationViolation> violations) {
                            super("registration failed");
                        }
                    }
                    """);

    /**
     * All framework stub sources needed by any test that invokes the processor in {@code codegen}
     * mode. Individual tests supply their own domain sources on top of this array.
     */
    static final JavaFileObject[] FRAMEWORK_SOURCES = {
        FUTURE_SOURCE,
        JSON_OBJECT_SOURCE,
        SERVICE_CONTRACT_SOURCE,
        SERVICE_OPERATION_SOURCE,
        ONE_WAY_SOURCE,
        SERVICE_HANDLER_SOURCE,
        SERVICE_CONTRACT_CONTRIBUTOR_SOURCE,
        SERVICE_CLIENT_FACTORY_SOURCE,
        CONTRACT_ENTRY_SOURCE,
        SERVICE_CONTRACT_ENTRIES_SOURCE,
        PARAM_SOURCE_ENUM_SOURCE,
        RESILIENCE_ANNOTATIONS_SOURCE,
        ANNOTATION_RESOLVER_SOURCE,
        SECURITY_CONTEXT_SOURCE,
        DISPATCH_CONTEXT_VALUE_SOURCE,
        PROVIDER_SOURCE,
        CONDITION_SOURCE,
        SERVICE_REGISTRATION_VIOLATION_SOURCE,
        SERVICE_REGISTRATION_EXCEPTION_SOURCE
    };

    private ServiceContractTestFixtures() {}
}
