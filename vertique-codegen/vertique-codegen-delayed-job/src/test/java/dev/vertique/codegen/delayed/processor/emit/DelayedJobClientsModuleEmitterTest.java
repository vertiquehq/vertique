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
 *   <li>A contract the module's package cannot reference is skipped with a warning rather than
 *       emitted into a module that would not compile; the same contract is bound normally when the
 *       module lands in its own package.</li>
 *   <li>A nested contract binds under its own simple name, unlike the proxy's flattened name.</li>
 *   <li>A generic contract is rejected up front by the validator.</li>
 *   <li>Contracts in disjoint top-level packages fall back to the default package.</li>
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
                // Asserted per binding, not file-scoped: the harness compiles without Dagger on the
                // classpath, so a module that silently lost @Provides — or scoped only its first
                // binding — would still compile and pass a file-scoped check.
                .assertGeneratedSourceContains(MODULE_FQN, """
                        @Provides
                          @Singleton
                          static DeliverJob provideDeliverJobClient(DelayedJobClientFactory factory) {""")
                .assertGeneratedSourceContains(MODULE_FQN, """
                        @Provides
                          @Singleton
                          static EmailJob provideEmailJobClient(DelayedJobClientFactory factory) {""")
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
    @DisplayName("a contract not visible from the module package is skipped, not emitted into a broken module")
    void contractInvisibleFromModulePackageIsSkipped() {
        // com.foo + com.bar resolve the module to package "com", from which a package-private
        // com.foo.HiddenJob is unreferenceable. Emitting a binding for it would produce a module that
        // does not compile — and generated sources are compiled whether or not the application
        // installs the module, so that would break the build on processor upgrade alone.
        JavaFileObject hidden = SourceFiles.inline("com.foo.HiddenJob", """
                package com.foo;
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "hidden")
                interface HiddenJob extends DelayedJobClient<String> {}
                """);

        ProcessorTestHarness.run(new DelayedJobContractProcessor(), hidden, contract("com.bar", "EmailJob", "email"))
                .assertSuccess()
                .assertGeneratedSourceContains("com.GeneratedDelayedJobClientsModule", "provideEmailJobClient")
                .assertGeneratedSourceDoesNotContain("com.GeneratedDelayedJobClientsModule", "HiddenJob");
    }

    @Test
    @DisplayName("a package-private contract is still bound when the module lands in its own package")
    void packagePrivateContractIsBoundWithinItsOwnPackage() {
        JavaFileObject hidden = SourceFiles.inline("com.foo.HiddenJob", """
                package com.foo;
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "hidden")
                interface HiddenJob extends DelayedJobClient<String> {}
                """);

        ProcessorTestHarness.run(new DelayedJobContractProcessor(), hidden)
                .assertSuccess()
                .assertGeneratedSourceContains("com.foo.GeneratedDelayedJobClientsModule", "provideHiddenJobClient");
    }

    @Test
    @DisplayName("a nested contract binds under its own simple name")
    void nestedContractBindsUnderSimpleName() {
        JavaFileObject nested = SourceFiles.inline("com.example.Outer", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                public class Outer {
                    @DelayedJobContract(name = "inner")
                    public interface Inner extends DelayedJobClient<String> {}
                }
                """);

        // Unlike the proxy, which flattens to Outer_Inner_DelayedJobProxy, the binding uses the
        // contract's simple name and its nested type literal.
        ProcessorTestHarness.run(new DelayedJobContractProcessor(), nested)
                .assertSuccess()
                .assertGeneratedSourceContains(MODULE_FQN, "provideInnerClient")
                .assertGeneratedSourceContains(MODULE_FQN, "return factory.create(Outer.Inner.class);");
    }

    @Test
    @DisplayName("a generic contract is rejected with one diagnostic instead of broken generated code")
    void genericContractIsRejected() {
        // A generic contract breaks both companions: the proxy fails to implement the erased enqueue
        // overloads, and a binding could only be the raw type. The validator rejects it up front so
        // the build reports one actionable error rather than a wall of javac override errors.
        JavaFileObject generic = SourceFiles.inline("com.example.GenericJob", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "generic")
                public interface GenericJob<T> extends DelayedJobClient<String> {}
                """);

        ProcessorTestHarness.run(new DelayedJobContractProcessor(), generic)
                .assertFailed()
                .assertErrorMessage("must not declare type parameters");
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

    @Test
    @DisplayName("a contract in the unnamed package is skipped rather than named from a named package")
    void unnamedPackageContractIsSkipped() {
        // Every contract in the unnamed package makes the LCP empty, which resolves to the named
        // fallback package — from which an unnamed-package type can neither be imported nor named.
        JavaFileObject unnamed = SourceFiles.inline("UnnamedJob", """
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "unnamed")
                public interface UnnamedJob extends DelayedJobClient<String> {}
                """);

        var result = ProcessorTestHarness.run(new DelayedJobContractProcessor(), unnamed)
                .assertSuccess();

        assertTrue(
                result.compilation()
                        .generatedSourceFile("vertique.generated.delayedjob.GeneratedDelayedJobClientsModule")
                        .isEmpty(),
                "an unnamed-package contract is the only contract, so no module should be emitted");
    }
}
