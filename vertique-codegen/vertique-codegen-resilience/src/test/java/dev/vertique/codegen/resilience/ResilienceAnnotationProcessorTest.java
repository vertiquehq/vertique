// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** TP-001 proof for the complete T011 resilience annotation validation matrix. */
class ResilienceAnnotationProcessorTest {

    private static final String PACKAGE = "t011";
    private static final String COMMON_IMPORTS = """
            import dev.vertique.resilience.annotation.Bulkhead;
            import dev.vertique.resilience.annotation.CircuitBreaker;
            import dev.vertique.resilience.annotation.Resilient;
            import dev.vertique.resilience.annotation.Retry;
            import dev.vertique.resilience.annotation.Timeout;
            import io.vertx.core.Future;
            import jakarta.inject.Inject;
            """;

    @DisplayName("enforces the T011 contract matrix")
    @ParameterizedTest(name = "{0}")
    @MethodSource("t011ContractMatrixRows")
    void shouldEnforceT011ContractMatrix(MatrixRow row) {
        ProcessorTestHarness.Result result = compile(row);
        if (row.success()) {
            result.assertSuccess();
            assertEquals(0, result.compilation().generatedSourceFiles().size(), row.name());
        } else {
            result.assertFailed().assertErrorMessage(row.expectedMessage());
        }
    }

    static Stream<MatrixRow> t011ContractMatrixRows() {
        return Stream.of(
                error(
                        "declaration-without-anchor",
                        validClass("@Retry\n    public Future<String> call() { return null; }"),
                        "resilience declarations on a concrete class require @Resilient on the same method"),
                error(
                        "empty-anchor-no-declaration",
                        validClass("@Resilient\n    public Future<String> call() { return null; }"),
                        "@Resilient must name a policy or be accompanied by at least one resilience declaration"),
                error(
                        "bad-policy-name",
                        validClass(
                                "@Resilient(policy = \"not valid!\")\n    public Future<String> call() { return null; }"),
                        "resilience policy name must match [A-Za-z0-9._~-]{1,128}: not valid!"),
                error(
                        "non-public-class",
                        nonPublicClass(),
                        "resilient methods must be declared on a public Dagger-managed class"),
                error("final-class", finalClass(), "resilient methods cannot be declared on a final class"),
                error(
                        "no-inject-constructor",
                        noInjectConstructor(),
                        "resilient methods require exactly one @Inject constructor"),
                error(
                        "private-method",
                        validClass(
                                "@Resilient(policy = \"payments\")\n    private Future<String> call() { return null; }"),
                        "resilient methods must be instance methods that can be overridden"),
                error(
                        "sync-returning",
                        validClass("@Resilient(policy = \"payments\")\n    public String call() { return null; }"),
                        "resilient methods must return a concrete Future<T>"),
                error(
                        "raw-future",
                        validClass("@Resilient(policy = \"payments\")\n    public Future call() { return null; }"),
                        "resilient methods must return a concrete Future<T>"),
                error(
                        "wildcard-future",
                        validClass("@Resilient(policy = \"payments\")\n    public Future<?> call() { return null; }"),
                        "resilient methods must return a concrete Future<T>"),
                error(
                        "retry-max-retries",
                        validClass(
                                "@Resilient(policy = \"payments\")\n    @Retry(maxRetries = 101)\n    public Future<String> call() { return null; }"),
                        "@Retry.maxRetries must be between 0 and 100"),
                error(
                        "retry-delay-ms",
                        validClass(
                                "@Resilient(policy = \"payments\")\n    @Retry(delayMs = -1)\n    public Future<String> call() { return null; }"),
                        "@Retry.delayMs must be >= 0"),
                error(
                        "retry-backoff-multiplier",
                        validClass(
                                "@Resilient(policy = \"payments\")\n    @Retry(backoffMultiplier = 0.5)\n    public Future<String> call() { return null; }"),
                        "@Retry.backoffMultiplier must be >= 1.0"),
                error(
                        "retry-max-delay-ms",
                        validClass(
                                "@Resilient(policy = \"payments\")\n    @Retry(maxDelayMs = -1)\n    public Future<String> call() { return null; }"),
                        "@Retry.maxDelayMs must be >= 0"),
                error(
                        "timeout-value",
                        validClass(
                                "@Resilient(policy = \"payments\")\n    @Timeout(0)\n    public Future<String> call() { return null; }"),
                        "@Timeout.value must be positive"),
                error(
                        "circuit-breaker-max-failures",
                        validClass(
                                "@Resilient(policy = \"payments\")\n    @CircuitBreaker(maxFailures = 0)\n    public Future<String> call() { return null; }"),
                        "@CircuitBreaker.maxFailures must be positive"),
                error(
                        "circuit-breaker-reset-timeout",
                        validClass(
                                "@Resilient(policy = \"payments\")\n    @CircuitBreaker(resetTimeoutMs = 0)\n    public Future<String> call() { return null; }"),
                        "@CircuitBreaker.resetTimeoutMs must be positive"),
                error(
                        "bulkhead-max-concurrent-calls",
                        validClass(
                                "@Resilient(policy = \"payments\")\n    @Bulkhead(maxConcurrentCalls = 0)\n    public Future<String> call() { return null; }"),
                        "@Bulkhead.maxConcurrentCalls must be positive"),
                error(
                        "bulkhead-reject-with-queue",
                        validClass(
                                "@Resilient(policy = \"payments\")\n    @Bulkhead(maxConcurrentCalls = 1, maxQueueSize = 1)\n    public Future<String> call() { return null; }"),
                        "@Bulkhead(mode = REJECT) cannot configure maxQueueSize or queueTimeoutMs"),
                error(
                        "bulkhead-queue-max-queue-size",
                        validClass(
                                "@Resilient(policy = \"payments\")\n    @Bulkhead(maxConcurrentCalls = 1, mode = Bulkhead.Mode.QUEUE, maxQueueSize = 1025)\n    public Future<String> call() { return null; }"),
                        "@Bulkhead(mode = QUEUE).maxQueueSize must be between 1 and 1024"),
                error(
                        "bulkhead-queue-timeout-ms",
                        validClass(
                                "@Resilient(policy = \"payments\")\n    @Bulkhead(maxConcurrentCalls = 1, mode = Bulkhead.Mode.QUEUE, queueTimeoutMs = 60001)\n    public Future<String> call() { return null; }"),
                        "@Bulkhead(mode = QUEUE).queueTimeoutMs must be between 1 and 60000"),
                error(
                        "class-level-declaration-without-anchor",
                        classLevelRetry(false),
                        "class-level resilience declarations are not honored on concrete classes; declare them on each @Resilient method"),
                error(
                        "class-level-declaration-with-anchor-is-rejected",
                        classLevelRetry(true),
                        "class-level resilience declarations are not honored on concrete classes; declare them on each @Resilient method"),
                error(
                        "double-wrap-service-handler-rejected",
                        serviceHandler(),
                        "the services transport already wraps this operation from the contract; declare resilience on the contract or on the handler, not both",
                        serviceStubs()),
                error(
                        "double-wrap-direct-implementor-rejected",
                        directImplementor(),
                        "the services transport already wraps this operation from the contract; declare resilience on the contract or on the handler, not both",
                        serviceStubs()),
                error(
                        "double-wrap-contract-type-level-rejected",
                        serviceHandlerTypeLevel(),
                        "the services transport already wraps this operation from the contract; declare resilience on the contract or on the handler, not both",
                        serviceTypeLevelStubs()),
                error(
                        "double-wrap-super-interface-rejected",
                        serviceHandlerSuperInterface(),
                        "the services transport already wraps this operation from the contract; declare resilience on the contract or on the handler, not both",
                        serviceSuperInterfaceStubs()),
                error(
                        "abstract-class-declaration-requires-anchor",
                        abstractClassDeclaration(),
                        "resilience declarations on a concrete class require @Resilient on the same method"),
                error(
                        "interface-declaration-bounds-rejected",
                        interfaceDeclarationBounds(),
                        "@Retry.maxRetries must be between 0 and 100",
                        serviceContractStub()),
                error(
                        "plain-interface-anchor-rejected",
                        plainInterfaceAnchor(),
                        "@Resilient on an interface is honored only on a @ServiceContract or REST-client interface"),
                success("rest-client-interface-anchor-compiles", restClientInterfaceAnchor(), pathStub()),
                error(
                        "class-level-declaration-on-handler-rejected",
                        classLevelHandler(),
                        "class-level resilience declarations are not honored on concrete classes; declare them on each @Resilient method",
                        emptyServiceStubs()),
                success(
                        "handler-with-undeclared-contract-compiles",
                        handlerWithUndeclaredContract(),
                        emptyServiceStubs()),
                success(
                        "interface-anchor-with-policy-name-compiles",
                        serviceContractInterfaceAnchor(),
                        serviceContractStub()),
                error(
                        "empty-anchor-allowed-interface",
                        serviceContractInterfaceEmptyAnchor(),
                        "@Resilient must name a policy or be accompanied by at least one resilience declaration",
                        serviceContractStub()),
                success("interface-declaration-compiles", plainInterfaceDeclaration()),
                success(
                        "concrete-anchor-declaration-compiles",
                        validClass(
                                "@Resilient(policy = \"payments\")\n    @Retry(maxRetries = 2)\n    public Future<String> call() { return null; }")));
    }

    @Test
    void sensitivityChangesRetryMaximumBoundary() {
        MatrixRow invalid = error(
                "retry-max-retries",
                validClass(
                        "@Resilient(policy = \"payments\")\n    @Retry(maxRetries = 101)\n    public Future<String> call() { return null; }"),
                "@Retry.maxRetries must be between 0 and 100");
        MatrixRow valid = success(
                "retry-max-retries-boundary",
                validClass(
                        "@Resilient(policy = \"payments\")\n    @Retry(maxRetries = 100)\n    public Future<String> call() { return null; }"));
        compile(invalid).assertFailed().assertErrorMessage(invalid.expectedMessage());
        compile(valid).assertSuccess();
    }

    @Test
    void sensitivityRemovesServiceDoubleWrapOnlyWhenContractDeclarationIsRemoved() {
        MatrixRow invalid = error(
                "double-wrap-service-handler-rejected",
                serviceHandler(),
                "the services transport already wraps this operation from the contract; declare resilience on the contract or on the handler, not both",
                serviceStubs());
        MatrixRow valid = success("service-handler-with-undeclared-contract", serviceHandler(), emptyServiceStubs());
        compile(invalid).assertFailed().assertErrorMessage(invalid.expectedMessage());
        compile(valid).assertSuccess();
    }

    @Test
    void sensitivityMovesClassDeclarationToTheAnchoredMethod() {
        MatrixRow invalid = error(
                "class-level-declaration-with-anchor-is-rejected",
                classLevelRetry(true),
                "class-level resilience declarations are not honored on concrete classes; declare them on each @Resilient method");
        MatrixRow valid = success(
                "method-level-declaration",
                validClass(
                        "@Resilient(policy = \"payments\")\n    @Retry\n    public Future<String> call() { return null; }"));
        compile(invalid).assertFailed().assertErrorMessage(invalid.expectedMessage());
        compile(valid).assertSuccess();
    }

    private static ProcessorTestHarness.Result compile(MatrixRow row) {
        JavaFileObject[] sources = Stream.concat(Stream.of(row.source()), row.companions().stream())
                .toArray(JavaFileObject[]::new);
        return ProcessorTestHarness.run(new ResilienceAnnotationProcessor(), sources);
    }

    private static MatrixRow error(String name, String source, String message, JavaFileObject... companions) {
        return new MatrixRow(name, inline("Bean", source), List.of(companions), false, message);
    }

    private static MatrixRow success(String name, String source, JavaFileObject... companions) {
        return new MatrixRow(name, inline("Bean", source), List.of(companions), true, "");
    }

    private static JavaFileObject inline(String type, String body) {
        return SourceFiles.inline(PACKAGE + "." + type, "package " + PACKAGE + ";\n" + COMMON_IMPORTS + body);
    }

    private static String validClass(String method) {
        return "public class Bean {\n    @Inject public Bean() {}\n\n    " + method + "\n}\n";
    }

    private static String nonPublicClass() {
        return "class Bean {\n    @Inject public Bean() {}\n\n    @Resilient(policy = \"payments\")\n    public Future<String> call() { return null; }\n}\n";
    }

    private static String finalClass() {
        return "public final class Bean {\n    @Inject public Bean() {}\n\n    @Resilient(policy = \"payments\")\n    public Future<String> call() { return null; }\n}\n";
    }

    private static String noInjectConstructor() {
        return "public class Bean {\n    public Bean() {}\n\n    @Resilient(policy = \"payments\")\n    public Future<String> call() { return null; }\n}\n";
    }

    private static String classLevelRetry(boolean anchored) {
        return "@Retry\npublic class Bean {\n    @Inject public Bean() {}\n\n    "
                + (anchored ? "@Resilient(policy = \"payments\")\n    " : "")
                + "public Future<String> call() { return null; }\n}\n";
    }

    private static String abstractClassDeclaration() {
        return "public abstract class Bean {\n    @Inject public Bean() {}\n\n    @Retry\n    public Future<String> call() { return null; }\n}\n";
    }

    private static String interfaceDeclarationBounds() {
        return "@dev.vertique.services.ServiceContract(\"contract\")\npublic interface Bean {\n    @Retry(maxRetries = 101)\n    Future<String> call();\n}\n";
    }

    private static String plainInterfaceAnchor() {
        return "public interface Bean {\n    @Resilient(policy = \"payments\")\n    Future<String> call();\n}\n";
    }

    private static String restClientInterfaceAnchor() {
        return "@jakarta.ws.rs.Path(\"/beans\")\npublic interface Bean {\n    @Resilient(policy = \"payments\")\n    Future<String> call();\n}\n";
    }

    private static String serviceContractInterfaceAnchor() {
        return "@dev.vertique.services.ServiceContract(\"contract\")\npublic interface Bean {\n    @Resilient(policy = \"payments\")\n    Future<String> call();\n}\n";
    }

    private static String serviceContractInterfaceEmptyAnchor() {
        return "@dev.vertique.services.ServiceContract(\"contract\")\npublic interface Bean {\n    @Resilient\n    Future<String> call();\n}\n";
    }

    private static String plainInterfaceDeclaration() {
        return "public interface Bean {\n    @Retry\n    Future<String> call();\n}\n";
    }

    private static String serviceHandler() {
        return "public class Bean implements dev.vertique.services.ServiceHandler<Contract> {\n"
                + "    @Inject public Bean() {}\n\n"
                + "    @Resilient(policy = \"payments\")\n"
                + "    public Future<String> call() { return null; }\n}\n";
    }

    private static String directImplementor() {
        return "public class Bean implements Contract {\n    @Inject public Bean() {}\n\n    @Resilient(policy = \"payments\")\n    public Future<String> call() { return null; }\n}\n";
    }

    private static String serviceHandlerTypeLevel() {
        return "public class Bean implements dev.vertique.services.ServiceHandler<Contract> {\n    @Inject public Bean() {}\n\n    @Resilient(policy = \"payments\")\n    public Future<String> call() { return null; }\n}\n";
    }

    private static String serviceHandlerSuperInterface() {
        return "public class Bean implements dev.vertique.services.ServiceHandler<Contract> {\n    @Inject public Bean() {}\n\n    @Resilient(policy = \"payments\")\n    public Future<String> call() { return null; }\n}\n";
    }

    private static String classLevelHandler() {
        return "@Retry\npublic class Bean implements dev.vertique.services.ServiceHandler<Contract> {\n    @Inject public Bean() {}\n\n    public Future<String> call() { return null; }\n}\n";
    }

    private static String handlerWithUndeclaredContract() {
        return "public class Bean implements dev.vertique.services.ServiceHandler<Contract> {\n    @Inject public Bean() {}\n\n    @Resilient(policy = \"payments\")\n    @Retry(maxRetries = 2)\n    public Future<String> call() { return null; }\n}\n";
    }

    private static JavaFileObject[] serviceStubs() {
        return new JavaFileObject[] {serviceHandlerStub(), serviceContractStub(), contractWithRetry()};
    }

    private static JavaFileObject[] emptyServiceStubs() {
        return new JavaFileObject[] {serviceHandlerStub(), serviceContractStub(), contractWithoutResilience()};
    }

    private static JavaFileObject[] serviceTypeLevelStubs() {
        return new JavaFileObject[] {serviceHandlerStub(), serviceContractStub(), contractWithTypeLevelRetry()};
    }

    private static JavaFileObject[] serviceSuperInterfaceStubs() {
        return new JavaFileObject[] {serviceHandlerStub(), serviceContractStub(), baseContract(), derivedContract()};
    }

    private static JavaFileObject[] pathStub() {
        return new JavaFileObject[] {SourceFiles.inline("jakarta.ws.rs.Path", """
                package jakarta.ws.rs;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
                public @interface Path { String value(); }
                """)};
    }

    private static JavaFileObject serviceHandlerStub() {
        return SourceFiles.inline("dev.vertique.services.ServiceHandler", """
                package dev.vertique.services;
                public interface ServiceHandler<C> {}
                """);
    }

    private static JavaFileObject serviceContractStub() {
        return SourceFiles.inline("dev.vertique.services.ServiceContract", """
                package dev.vertique.services;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
                public @interface ServiceContract { String value(); }
                """);
    }

    private static JavaFileObject contractWithRetry() {
        return SourceFiles.inline(PACKAGE + ".Contract", """
                package t011;
                import dev.vertique.resilience.annotation.Resilient;
                import dev.vertique.resilience.annotation.Retry;
                import dev.vertique.services.ServiceContract;
                import io.vertx.core.Future;
                @ServiceContract("contract")
                public interface Contract {
                    @Retry
                    Future<String> call();
                }
                """);
    }

    private static JavaFileObject contractWithoutResilience() {
        return SourceFiles.inline(PACKAGE + ".Contract", """
                package t011;
                import dev.vertique.services.ServiceContract;
                import io.vertx.core.Future;
                @ServiceContract("contract")
                public interface Contract {
                    Future<String> call();
                }
                """);
    }

    private static JavaFileObject contractWithTypeLevelRetry() {
        return SourceFiles.inline(PACKAGE + ".Contract", """
                package t011;
                import dev.vertique.resilience.annotation.Timeout;
                import dev.vertique.services.ServiceContract;
                import io.vertx.core.Future;
                @ServiceContract("contract")
                @Timeout(2_000)
                public interface Contract {
                    Future<String> call();
                }
                """);
    }

    private static JavaFileObject baseContract() {
        return SourceFiles.inline(PACKAGE + ".Base", """
                package t011;
                import dev.vertique.resilience.annotation.Retry;
                import io.vertx.core.Future;
                public interface Base {
                    @Retry
                    Future<String> call();
                }
                """);
    }

    private static JavaFileObject derivedContract() {
        return SourceFiles.inline(PACKAGE + ".Contract", """
                package t011;
                import dev.vertique.services.ServiceContract;
                @ServiceContract("contract")
                public interface Contract extends Base {}
                """);
    }

    private record MatrixRow(
            String name,
            JavaFileObject source,
            List<JavaFileObject> companions,
            boolean success,
            String expectedMessage) {
        @Override
        public String toString() {
            return name;
        }
    }
}
