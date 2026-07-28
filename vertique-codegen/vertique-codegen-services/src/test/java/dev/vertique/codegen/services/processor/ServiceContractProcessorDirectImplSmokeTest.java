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
 * Smoke test that verifies a minimal direct-impl contract compiles and produces a
 * {@code _ContractContributor} source file plus a {@code GeneratedServicesModule}.
 *
 * <p>Verifies the end-to-end scaffold: processor discovers the impl, extracts the model,
 * validates it, and emits both files.
 */
class ServiceContractProcessorDirectImplSmokeTest {

    @Test
    @DisplayName("direct-impl generates _ContractContributor and module")
    void directImpl_generatesContributorAndModule() {
        JavaFileObject contract = SourceFiles.inline("com.example.UserService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import io.vertx.core.Future;
                @ServiceContract(value = "user-service", namespace = "integration")
                public interface UserService {
                    @dev.vertique.services.ServiceOperation("get-user")
                    Future<String> getUser(String userId);
                }
                """);

        JavaFileObject impl = SourceFiles.inline("com.example.UserServiceImpl", """
                package com.example;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class UserServiceImpl implements UserService {
                    @Inject UserServiceImpl() {}
                    @Override public Future<String> getUser(String userId) {
                        return Future.succeededFuture(userId);
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract, impl);
        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

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
        JavaFileObject contract = SourceFiles.inline("com.example.UserService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import io.vertx.core.Future;
                @ServiceContract(value = "user-service")
                public interface UserService {
                    Future<String> getUser(String userId);
                }
                """);

        JavaFileObject impl = SourceFiles.inline("com.example.UserServiceImpl", """
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
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract, impl);
        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();
        // @NoAutoWire skips codegen; the manual @Services path stays in user code
        var generated = result.compilation().generatedSourceFile("com.example.UserService_ContractContributor");
        org.junit.jupiter.api.Assertions.assertTrue(
                generated.isEmpty(), "Expected no contributor when impl is annotated @NoAutoWire");
    }

    // --- Helper ---

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        var result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
