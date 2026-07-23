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
 * Verifies the happy-path direct-implementation pattern for {@link ServiceContractProcessor}.
 *
 * <p>Asserts that when a concrete class directly implements a {@code @ServiceContract} interface:
 * <ul>
 *   <li>A {@code {Contract}_ContractContributor} is generated in the contract's package.</li>
 *   <li>A {@code GeneratedServicesModule} with {@code @Provides @IntoSet} is
 *       generated.</li>
 *   <li>All expected API calls and metadata are present in the generated source.</li>
 * </ul>
 */
class ServiceContractProcessorDirectImplPositiveTest {

    @Test
    @DisplayName("direct-impl happy path generates ContractContributor and module with expected snippets")
    void directImplHappyPath_generatesContributorAndModuleWithExpectedSnippets() {
        JavaFileObject contract = SourceFiles.inline("com.example.UserService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract(value = "user-service", namespace = "integration")
                public interface UserService {
                    @ServiceOperation("get-user")
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

        // --- Assert contributor is generated ---
        result.assertGeneratedSourceContains(
                "com.example.UserService_ContractContributor", "ServiceContractContributor");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "UserServiceImpl");
        result.assertGeneratedSourceContains(
                "com.example.UserService_ContractContributor", "ServiceContractEntries.deployable()");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", ".contract(");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", ".serviceInstance(");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", ".operation(\"get-user\")");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", ".method(");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", ".payloadType(");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", ".returnType(");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", ".param(");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", ".resilienceAnnotations(");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", ".methodAnnotations(");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", ".classAnnotations(");
        result.assertGeneratedSourceContains(
                "com.example.UserService_ContractContributor",
                ".deploymentOptions(config, \"services\", \"contracts\", \"integration\", \"user-service\")");

        // --- Assert module is generated ---
        result.assertGeneratedSourceContains("com.example.GeneratedServicesModule", "ServiceContractContributor");
        result.assertGeneratedSourceContains("com.example.GeneratedServicesModule", "@Provides");
        result.assertGeneratedSourceContains("com.example.GeneratedServicesModule", "@IntoSet");
    }

    // --- Helper ---

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        var result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
