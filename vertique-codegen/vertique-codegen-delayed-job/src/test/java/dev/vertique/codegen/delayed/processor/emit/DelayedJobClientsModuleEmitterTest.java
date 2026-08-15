// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.delayed.processor.emit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.delayed.processor.DelayedJobContractProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.Map;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link DelayedJobClientsModuleEmitter} generates a
 * {@code GeneratedDelayedJobClientsModule} whose {@code @Provides @Singleton} methods delegate to
 * {@code DelayedJobClientFactory.create(…)} — never to the generated proxy directly, so the
 * factory's contract checks and config merge always run.
 *
 * <p>Cases covered:
 * <ul>
 *   <li>Two valid contracts in one package produce a module in that package with one factory-delegating
 *       binding each, and no direct proxy construction.</li>
 *   <li>Contracts in sibling packages place the module at the longest common package prefix.</li>
 *   <li>{@code -Avertique.codegen.package} overrides the module package while the proxies stay pinned
 *       to their origin package.</li>
 *   <li>Two contracts sharing a simple name across packages get distinct binding method names, so the
 *       module still compiles.</li>
 *   <li>A compilation unit with no {@code @DelayedJobContract} emits no module at all.</li>
 * </ul>
 */
class DelayedJobClientsModuleEmitterTest {

    private static final String MODULE_FQN = "com.example.GeneratedDelayedJobClientsModule";
    private static final String MODULE_FQN_OVERRIDE = "com.acme.gen.GeneratedDelayedJobClientsModule";

    // --- Fixtures ---

    private static JavaFileObject contract(String pkg, String simpleName, String jobName) {
        return SourceFiles.inline(pkg + "." + simpleName, """
                package %s;
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "%s")
                public interface %s extends DelayedJobClient<String> {}
                """.formatted(pkg, jobName, simpleName));
    }

    // --- Tests ---

    @Test
    @DisplayName("two contracts in one package produce factory-delegating @Singleton bindings")
    void twoContractsGenerateFactoryDelegatingBindings() {
        ProcessorTestHarness.run(
                        new DelayedJobContractProcessor(),
                        contract("com.example", "DeliverJob", "deliver"),
                        contract("com.example", "EmailJob", "email"))
                .assertSuccess()
                .assertGeneratedSourceContains(MODULE_FQN, "@Module")
                .assertGeneratedSourceContains(
                        MODULE_FQN,
                        "@Generated(\"dev.vertique.codegen.delayed.processor.DelayedJobContractProcessor\")")
                .assertGeneratedSourceContains(MODULE_FQN, "@Singleton")
                .assertGeneratedSourceContains(MODULE_FQN, "DelayedJobClientFactory factory")
                .assertGeneratedSourceContains(MODULE_FQN, "provideDeliverJobClient")
                .assertGeneratedSourceContains(MODULE_FQN, "provideEmailJobClient")
                .assertGeneratedSourceContains(MODULE_FQN, "return factory.create(DeliverJob.class);")
                .assertGeneratedSourceContains(MODULE_FQN, "return factory.create(EmailJob.class);")
                // The binding must go through the factory, never construct the generated proxy directly.
                .assertGeneratedSourceDoesNotContain(MODULE_FQN, "new DeliverJob_DelayedJobProxy");
    }

    @Test
    @DisplayName("contracts in sibling packages place the module at the longest common package prefix")
    void siblingPackagesResolveToLongestCommonPrefix() {
        ProcessorTestHarness.run(
                        new DelayedJobContractProcessor(),
                        contract("com.example.a", "DeliverJob", "deliver"),
                        contract("com.example.b", "EmailJob", "email"))
                .assertSuccess()
                .assertGeneratedSourceContains(MODULE_FQN, "provideDeliverJobClient")
                .assertGeneratedSourceContains(MODULE_FQN, "provideEmailJobClient");
    }

    @Test
    @DisplayName("package override relocates the module but leaves proxies pinned to the origin package")
    void packageOverrideRelocatesOnlyTheModule() {
        ProcessorTestHarness.run(
                        new DelayedJobContractProcessor(),
                        Map.of("vertique.codegen.package", "com.acme.gen"),
                        contract("com.example", "DeliverJob", "deliver"))
                .assertSuccess()
                .assertGeneratedSourceContains(MODULE_FQN_OVERRIDE, "@Module")
                .assertGeneratedSourceContains(MODULE_FQN_OVERRIDE, "provideDeliverJobClient")
                .assertGeneratedSourceContains(MODULE_FQN_OVERRIDE, "return factory.create(DeliverJob.class);")
                // Origin-package pinning of the proxy is unaffected by the module override.
                .assertGeneratedSourceContains("com.example.DeliverJob_DelayedJobProxy", "implements DeliverJob");
    }

    @Test
    @DisplayName("contracts sharing a simple name across packages get distinct binding method names")
    void sameSimpleNameAcrossPackagesDisambiguates() {
        // Two same-simple-name contracts would collide on provideJobClient(DelayedJobClientFactory);
        // methods differing only in return type do not compile, so assertSuccess is the real proof.
        ProcessorTestHarness.run(
                        new DelayedJobContractProcessor(),
                        contract("com.foo", "Job", "foo-job"),
                        contract("com.bar", "Job", "bar-job"))
                .assertSuccess()
                .assertGeneratedSourceContains("com.GeneratedDelayedJobClientsModule", "provideJobClient(")
                .assertGeneratedSourceContains("com.GeneratedDelayedJobClientsModule", "provideJobClient_com_foo_Job(");
    }

    @Test
    @DisplayName("contracts in disjoint top-level packages fall back to the default package")
    void disjointPackagesFallBackToDefaultPackage() {
        // Unlike PackageResolver.resolve, which reports disjoint packages as a compiler error, the
        // clients-module family falls back rather than failing the build.
        ProcessorTestHarness.run(
                        new DelayedJobContractProcessor(),
                        contract("com.foo", "DeliverJob", "deliver"),
                        contract("org.bar", "EmailJob", "email"))
                .assertSuccess()
                .assertGeneratedSourceContains(
                        "vertique.generated.delayedjob.GeneratedDelayedJobClientsModule", "provideDeliverJobClient")
                .assertGeneratedSourceContains(
                        "vertique.generated.delayedjob.GeneratedDelayedJobClientsModule", "provideEmailJobClient");
    }

    @Test
    @DisplayName("a compilation unit with no @DelayedJobContract emits no module")
    void noContractsEmitsNoModule() {
        var result = ProcessorTestHarness.run(
                        new DelayedJobContractProcessor(), SourceFiles.inline("com.example.Plain", """
                                package com.example;
                                public interface Plain {}
                                """))
                .assertSuccess();

        assertTrue(
                result.compilation().generatedSourceFile(MODULE_FQN).isEmpty(),
                "no @DelayedJobContract in the unit must emit no GeneratedDelayedJobClientsModule");
    }
}
