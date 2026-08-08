// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import static dev.vertique.codegen.services.processor.ServiceContractTestFixtures.FRAMEWORK_SOURCES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.io.IOException;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that generated typed-client bindings are real Dagger bindings rather than only source
 * snippets. The source-output assertions in this class also pin the construction boundary and the
 * module's contributor/client coexistence shape.
 */
class ServiceClientDaggerBindingTest {

    private static final JavaFileObject NO_AUTO_WIRE_SOURCE =
            SourceFiles.inline("dev.vertique.codegen.NoAutoWire", """
            package dev.vertique.codegen;
            import java.lang.annotation.*;
            @Target(ElementType.TYPE) @Retention(RetentionPolicy.SOURCE)
            public @interface NoAutoWire {}
            """);

    @Test
    @DisplayName("GeneratedServicesModule publishes a singleton client and Dagger injects it")
    void generatedClientBinding_isInjectableAndUsesFactory() throws IOException {
        JavaFileObject contract = userServiceContract("com.example.UserService", "UserService");
        JavaFileObject impl = userServiceImpl("com.example.UserServiceImpl", "UserService");
        JavaFileObject consumer = SourceFiles.inline("com.example.UserServiceConsumer", """
                package com.example;
                import jakarta.inject.Inject;
                public final class UserServiceConsumer {
                    private final UserService service;
                    @Inject public UserServiceConsumer(UserService service) { this.service = service; }
                    public UserService service() { return service; }
                }
                """);
        JavaFileObject factoryModule = SourceFiles.inline("com.example.FactoryModule", """
                package com.example;
                import dagger.Module;
                import dagger.Provides;
                import dev.vertique.services.ServiceClientFactory;
                @Module public abstract class FactoryModule {
                    @Provides static ServiceClientFactory factory() { return new ServiceClientFactory(); }
                }
                """);
        JavaFileObject component = SourceFiles.inline("com.example.TestComponent", """
                package com.example;
                import dagger.Component;
                import jakarta.inject.Singleton;
                @Singleton
                @Component(modules = {GeneratedServicesModule.class, FactoryModule.class})
                interface TestComponent {
                    UserServiceConsumer consumer();
                }
                """);

        Compilation compilation = compileWithDagger(contract, impl, consumer, factoryModule, component);
        assertEquals(Compilation.Status.SUCCESS, compilation.status(), diagnostics(compilation));

        ProcessorTestHarness.Result output =
                ProcessorTestHarness.run(new ServiceContractProcessor(), concat(FRAMEWORK_SOURCES, contract, impl));
        output.assertSuccess();
        output.assertGeneratedSourceContains("com.example.GeneratedServicesModule", "@IntoSet");
        output.assertGeneratedSourceContains("com.example.GeneratedServicesModule", "@Singleton");
        output.assertGeneratedSourceContains(
                "com.example.GeneratedServicesModule", "return serviceClientFactory.create(UserService.class)");
        output.assertGeneratedSourceContains("com.example.GeneratedServicesModule", "provideUserServiceClient(");
    }

    @Test
    @DisplayName("a contract-only compilation emits the unified module and client binding")
    void contractOnlyCompilation_emitsModuleAndClientBinding() {
        JavaFileObject contract = userServiceContract("com.example.ContractsOnly", "ContractsOnly");

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), concat(FRAMEWORK_SOURCES, contract));

        result.assertSuccess();
        result.assertGeneratedSourceContains("com.example.GeneratedServicesModule", "provideContractsOnlyClient(");
        result.assertGeneratedSourceContains(
                "com.example.GeneratedServicesModule", "return serviceClientFactory.create(ContractsOnly.class)");
    }

    @Test
    @DisplayName("multiple contracts and same-simple-name contracts receive unique provider methods")
    void multipleContracts_haveCollisionSafeMethods() {
        JavaFileObject first = userServiceContract("one.Foo", "Foo");
        JavaFileObject second = userServiceContract("two.Foo", "Foo");

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), concat(FRAMEWORK_SOURCES, first, second));

        result.assertSuccess();
        result.assertGeneratedSourceContains(
                "vertique.generated.services.GeneratedServicesModule", "provideFooClient(");
        result.assertGeneratedSourceContains(
                "vertique.generated.services.GeneratedServicesModule", "provideFooClient_two_Foo(");
        assertEquals(
                2,
                count(
                        source(result, "vertique.generated.services.GeneratedServicesModule"),
                        "return serviceClientFactory.create"));
    }

    @Test
    @DisplayName("client provider names cannot collide with contributor provider names")
    void clientAndContributorMethods_doNotCollide() {
        JavaFileObject clientContract = userServiceContract("com.example.Foo", "Foo");
        JavaFileObject contributorContract = userServiceContract("com.example.ProvideFooClient", "ProvideFooClient");
        JavaFileObject contributorImpl = userServiceImpl("com.example.ProvideFooClientImpl", "ProvideFooClient");

        var result = ProcessorTestHarness.run(
                new ServiceContractProcessor(),
                concat(FRAMEWORK_SOURCES, clientContract, contributorContract, contributorImpl));

        result.assertSuccess();
        String module = source(result, "com.example.GeneratedServicesModule");
        assertTrue(module.contains("provideFooClient(ProvideFooClient_ContractContributor impl)"));
        assertTrue(module.contains("provideFooClient_com_example_Foo("));
    }

    @Test
    @DisplayName("contract @NoAutoWire suppresses only the typed-client binding")
    void noAutoWireOnContract_keepsContributorAndAllowsManualBinding() throws IOException {
        JavaFileObject contract = SourceFiles.inline("com.example.UserService", """
                package com.example;
                import dev.vertique.codegen.NoAutoWire;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @NoAutoWire @ServiceContract("user")
                public interface UserService {
                    @ServiceOperation("get") Future<String> get(String id);
                }
                """);
        JavaFileObject impl = userServiceImpl("com.example.UserServiceImpl", "UserService");
        JavaFileObject manualModule = SourceFiles.inline("com.example.ManualModule", """
                package com.example;
                import dagger.Module;
                import dagger.Provides;
                import dev.vertique.services.ServiceClientFactory;
                @Module public abstract class ManualModule {
                    @Provides static UserService userService(ServiceClientFactory factory) {
                        return factory.create(UserService.class);
                    }
                }
                """);
        JavaFileObject factoryModule = SourceFiles.inline("com.example.FactoryModule", """
                package com.example;
                import dagger.Module;
                import dagger.Provides;
                import dev.vertique.services.ServiceClientFactory;
                @Module public abstract class FactoryModule {
                    @Provides static ServiceClientFactory factory() { return new ServiceClientFactory(); }
                }
                """);
        JavaFileObject component = SourceFiles.inline("com.example.TestComponent", """
                package com.example;
                import dagger.Component;
                import jakarta.inject.Singleton;
                @Singleton
                @Component(modules = {GeneratedServicesModule.class, ManualModule.class, FactoryModule.class})
                interface TestComponent {
                    UserService service();
                }
                """);

        Compilation compilation =
                compileWithDagger(NO_AUTO_WIRE_SOURCE, contract, impl, manualModule, factoryModule, component);
        assertEquals(Compilation.Status.SUCCESS, compilation.status(), diagnostics(compilation));

        var result = ProcessorTestHarness.run(
                new ServiceContractProcessor(), concat(FRAMEWORK_SOURCES, NO_AUTO_WIRE_SOURCE, contract, impl));
        result.assertSuccess();
        result.assertGeneratedSourceContains("com.example.GeneratedServicesModule", "UserService_ContractContributor");
        result.assertGeneratedSourceDoesNotContain("com.example.GeneratedServicesModule", "provideUserServiceClient(");
    }

    @Test
    @DisplayName("implementation @NoAutoWire retains the client binding and suppresses server registration")
    void noAutoWireOnImplementation_keepsClientBinding() {
        JavaFileObject contract = userServiceContract("com.example.UserService", "UserService");
        JavaFileObject impl = SourceFiles.inline("com.example.UserServiceImpl", """
                package com.example;
                import dev.vertique.codegen.NoAutoWire;
                import io.vertx.core.Future;
                @NoAutoWire public class UserServiceImpl implements UserService {
                    public Future<String> get(String id) { return Future.succeededFuture(id); }
                }
                """);

        var result = ProcessorTestHarness.run(
                new ServiceContractProcessor(), concat(FRAMEWORK_SOURCES, NO_AUTO_WIRE_SOURCE, contract, impl));

        result.assertSuccess();
        result.assertGeneratedSourceContains("com.example.GeneratedServicesModule", "provideUserServiceClient(");
        result.assertGeneratedSourceDoesNotContain(
                "com.example.GeneratedServicesModule", "UserService_ContractContributor");
    }

    @Test
    @DisplayName("module output is deterministic when source declaration order changes")
    void moduleOutput_isIndependentOfCompilationOrder() {
        JavaFileObject first = userServiceContract("one.First", "First");
        JavaFileObject second = userServiceContract("two.Second", "Second");
        JavaFileObject firstImpl = userServiceImpl("one.FirstImpl", "First");
        JavaFileObject secondImpl = userServiceImpl("two.SecondImpl", "Second");

        var forward = ProcessorTestHarness.run(
                new ServiceContractProcessor(), concat(FRAMEWORK_SOURCES, first, second, firstImpl, secondImpl));
        var reverse = ProcessorTestHarness.run(
                new ServiceContractProcessor(), concat(FRAMEWORK_SOURCES, second, first, secondImpl, firstImpl));

        forward.assertSuccess();
        reverse.assertSuccess();
        assertEquals(
                source(forward, "vertique.generated.services.GeneratedServicesModule"),
                source(reverse, "vertique.generated.services.GeneratedServicesModule"));
        assertEquals(
                source(forward, "one.First_ContractContributor"), source(reverse, "one.First_ContractContributor"));
        assertEquals(
                source(forward, "two.Second_ContractContributor"), source(reverse, "two.Second_ContractContributor"));
    }

    private static Compilation compileWithDagger(JavaFileObject... sources) {
        return Compiler.javac()
                .withProcessors(new ServiceContractProcessor(), new dagger.internal.codegen.ComponentProcessor())
                .withOptions("--release", "21")
                .compile(concat(FRAMEWORK_SOURCES, sources));
    }

    private static JavaFileObject userServiceContract(String fqn, String simpleName) {
        int lastDot = fqn.lastIndexOf('.');
        String packageName = fqn.substring(0, lastDot);
        return SourceFiles.inline(fqn, """
                package %s;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract("%s")
                public interface %s {
                    @ServiceOperation("get") Future<String> get(String id);
                }
                """.formatted(packageName, simpleName, simpleName));
    }

    private static JavaFileObject userServiceImpl(String fqn, String contractSimpleName) {
        int lastDot = fqn.lastIndexOf('.');
        String packageName = fqn.substring(0, lastDot);
        String simpleName = fqn.substring(lastDot + 1);
        return SourceFiles.inline(fqn, """
                package %s;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class %s implements %s {
                    @Inject public %s() {}
                    public Future<String> get(String id) { return Future.succeededFuture(id); }
                }
                """.formatted(packageName, simpleName, contractSimpleName, simpleName));
    }

    private static String source(ProcessorTestHarness.Result result, String fqn) {
        try {
            return result.compilation()
                    .generatedSourceFile(fqn)
                    .orElseThrow()
                    .getCharContent(true)
                    .toString();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String diagnostics(Compilation compilation) {
        return compilation.diagnostics().stream()
                .map(d -> d.getMessage(null))
                .toList()
                .toString();
    }

    private static int count(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        JavaFileObject[] result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
