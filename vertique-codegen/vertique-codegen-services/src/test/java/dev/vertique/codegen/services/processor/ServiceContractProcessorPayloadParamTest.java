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
 * Verifies that {@link ServiceContractProcessor} rejects contract methods with invalid payload
 * parameter configurations.
 *
 * <p>Mirrors {@code dev.vertique.services.ParameterClassifier#classifyParams} (rule at
 * {@code ParameterClassifier.java:119-126}).
 *
 * <p>Cases covered:
 * <ul>
 *   <li>Method with two non-context params (exceeds the one-payload limit).</li>
 *   <li>Method with a {@code DispatchEnvelope<?>} parameter (forbidden by the classifier).</li>
 * </ul>
 */
class ServiceContractProcessorPayloadParamTest {

    @Test
    @DisplayName("contract method with two non-context params → FAILED with 'more than one payload param' error")
    void twoPayloadParams_failsWithMultiplePayloadError() {
        JavaFileObject contract = SourceFiles.inline("com.example.UserService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract(value = "user-service", namespace = "integration")
                public interface UserService {
                    @ServiceOperation("update-user")
                    Future<String> updateUser(String userId, String name);
                }
                """);

        JavaFileObject impl = SourceFiles.inline("com.example.UserServiceImpl", """
                package com.example;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class UserServiceImpl implements UserService {
                    @Inject UserServiceImpl() {}
                    @Override public Future<String> updateUser(String userId, String name) {
                        return Future.succeededFuture(userId);
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract, impl);

        ProcessorTestHarness.run(new ServiceContractProcessor(), sources)
                .assertFailed()
                .assertErrorMessage("payload");
    }

    @Test
    @DisplayName("contract method with DispatchEnvelope<?> parameter → FAILED with Body error")
    void bodyParam_failsWithBodyError() {
        JavaFileObject bodyType = SourceFiles.inline("dev.vertique.core.eventbus.DispatchEnvelope", """
                package dev.vertique.core.eventbus;
                public class DispatchEnvelope<T> {}
                """);

        JavaFileObject contract = SourceFiles.inline("com.example.UserService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import dev.vertique.core.eventbus.DispatchEnvelope;
                import io.vertx.core.Future;
                @ServiceContract(value = "user-service", namespace = "integration")
                public interface UserService {
                    @ServiceOperation("get-user")
                    Future<String> getUser(DispatchEnvelope<String> request);
                }
                """);

        JavaFileObject impl = SourceFiles.inline("com.example.UserServiceImpl", """
                package com.example;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                import dev.vertique.core.eventbus.DispatchEnvelope;
                public class UserServiceImpl implements UserService {
                    @Inject UserServiceImpl() {}
                    @Override public Future<String> getUser(DispatchEnvelope<String> request) {
                        return Future.succeededFuture("ok");
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, bodyType, contract, impl);

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
