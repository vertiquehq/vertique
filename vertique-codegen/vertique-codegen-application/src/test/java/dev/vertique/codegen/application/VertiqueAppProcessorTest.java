// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.io.IOException;
import java.util.List;
import javax.annotation.processing.Processor;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VertiqueAppProcessor} — the annotation processor that generates a
 * {@code VertiqueComponentFactory} implementation and its {@code META-INF/services} registration
 * from a {@code @VertiqueApp}-annotated Dagger {@code @Component}.
 *
 * <p>The happy-path test runs {@code VertiqueAppProcessor} together with the real Dagger annotation
 * processor ({@code dagger.internal.codegen.ComponentProcessor}) so that {@code DaggerTestAppComponent}
 * exists in the same compilation; the generated factory references {@code DaggerTestAppComponent} by
 * name, and the whole compilation succeeding proves the factory + the Dagger component compile
 * together. The {@code @VertiqueApp} component extends {@code VertiqueApplicationComponent} via a
 * minimal stub {@code @Module} that declares empty multibindings, so a real (if lightweight) Dagger
 * graph is assembled.
 */
class VertiqueAppProcessorTest {

    private static final String GENERATED_FACTORY_FQN = "com.example.app.TestAppComponentVertiqueComponentFactory";
    private static final String SERVICE_RESOURCE_PATH = "META-INF/services/dev.vertique.core.VertiqueComponentFactory";

    /**
     * A minimal Dagger {@code @Module} that satisfies {@code VertiqueApplicationComponent}'s
     * accessors via empty multibindings: an empty {@code Set<ApplicationStartupStep>}, an empty
     * {@code Set<ApplicationShutdownStep>}, and an empty {@code Set<VerticleDeployment>} (which lets
     * the {@code @Inject VerticleDeploymentManager(VerticleDeployer, Set<VerticleDeployment>)}
     * resolve, with {@code VerticleDeployer} injected from {@code VertxModule}'s {@code Vertx}).
     */
    private static final JavaFileObject STUB_MODULE = SourceFiles.inline("com.example.app.StubLifecycleModule", """
            package com.example.app;

            import dagger.Module;
            import dagger.multibindings.Multibinds;
            import dev.vertique.core.lifecycle.ApplicationShutdownStep;
            import dev.vertique.core.lifecycle.ApplicationStartupStep;
            import dev.vertique.deploy.VerticleDeployment;
            import java.util.Set;

            @Module
            abstract class StubLifecycleModule {
                @Multibinds
                abstract Set<ApplicationStartupStep> startupSteps();

                @Multibinds
                abstract Set<ApplicationShutdownStep> shutdownSteps();

                @Multibinds
                abstract Set<VerticleDeployment> verticleDeployments();
            }
            """);

    private static final JavaFileObject VALID_COMPONENT = SourceFiles.inline("com.example.app.TestAppComponent", """
            package com.example.app;

            import dagger.Component;
            import dev.vertique.application.VertiqueApp;
            import dev.vertique.application.VertiqueApplicationComponent;
            import dev.vertique.core.VertxModule;
            import jakarta.inject.Singleton;

            @VertiqueApp
            @Singleton
            @Component(modules = {VertxModule.class, StubLifecycleModule.class})
            public interface TestAppComponent extends VertiqueApplicationComponent {}
            """);

    private static List<Processor> processors() {
        return List.of(new VertiqueAppProcessor(), new dagger.internal.codegen.ComponentProcessor());
    }

    @Test
    @DisplayName("valid @VertiqueApp component generates the factory + SPI registration and compiles with Dagger")
    void validComponent_generatesFactoryAndService() throws IOException {
        var result = ProcessorTestHarness.run(processors(), STUB_MODULE, VALID_COMPONENT);

        // The whole compilation — generated factory + DaggerTestAppComponent — succeeds.
        result.assertSuccess();

        // The factory implements VertiqueComponentFactory<TestAppComponent> with the exact build() body.
        result.assertGeneratedSourceContains(GENERATED_FACTORY_FQN, "class TestAppComponentVertiqueComponentFactory");
        result.assertGeneratedSourceContains(GENERATED_FACTORY_FQN, "VertiqueComponentFactory<TestAppComponent>");
        result.assertGeneratedSourceContains(
                GENERATED_FACTORY_FQN, "public TestAppComponent build(VertiqueRuntime runtime)");
        // The build() body delegates to DaggerTestAppComponent.builder().vertxModule(...).build();
        // assert on fragments rather than the whole line, since JavaPoet may wrap the long chain.
        result.assertGeneratedSourceContains(GENERATED_FACTORY_FQN, "return DaggerTestAppComponent.builder()");
        result.assertGeneratedSourceContains(
                GENERATED_FACTORY_FQN, "vertxModule(new VertxModule(runtime.vertx(), runtime.config()))");
        result.assertGeneratedSourceContains(GENERATED_FACTORY_FQN, ".build()");

        // The SPI resource names the generated factory FQN.
        var serviceFile = result.compilation().generatedFile(StandardLocation.CLASS_OUTPUT, "", SERVICE_RESOURCE_PATH);
        assertTrue(serviceFile.isPresent(), "Expected the VertiqueComponentFactory service resource to be generated");
        String content = serviceFile.get().getCharContent(true).toString().strip();
        assertEquals(GENERATED_FACTORY_FQN, content);
    }

    @Test
    @DisplayName("non-@Component interface is rejected")
    void nonComponentInterface_isRejected() {
        var component = SourceFiles.inline("com.example.app.NotAComponent", """
                package com.example.app;

                import dev.vertique.application.VertiqueApp;
                import dev.vertique.application.VertiqueApplicationComponent;

                @VertiqueApp
                public interface NotAComponent extends VertiqueApplicationComponent {}
                """);

        var result = ProcessorTestHarness.run(processors(), STUB_MODULE, component);

        result.assertFailed();
        result.assertErrorMessage("must be annotated with @dagger.Component");
    }

    @Test
    @DisplayName("@Component interface not extending VertiqueApplicationComponent is rejected")
    void componentNotExtendingApplicationComponent_isRejected() {
        var component = SourceFiles.inline("com.example.app.LonelyComponent", """
                package com.example.app;

                import dagger.Component;
                import dev.vertique.application.VertiqueApp;
                import dev.vertique.core.VertxModule;
                import jakarta.inject.Singleton;

                @VertiqueApp
                @Singleton
                @Component(modules = VertxModule.class)
                public interface LonelyComponent {}
                """);

        var result = ProcessorTestHarness.run(processors(), component);

        result.assertFailed();
        result.assertErrorMessage("must extend dev.vertique.application.VertiqueApplicationComponent");
    }

    @Test
    @DisplayName("nested @VertiqueApp @Component is rejected because it must be top-level")
    void nestedComponent_isRejected() {
        // A nested @VertiqueApp component (Outer.Inner) would yield Dagger's DaggerOuter_Inner, which
        // the generated factory's by-simple-name reference (DaggerInner) cannot resolve.
        var nested = SourceFiles.inline("com.example.app.Outer", """
                package com.example.app;

                import dagger.Component;
                import dev.vertique.application.VertiqueApp;
                import dev.vertique.application.VertiqueApplicationComponent;
                import dev.vertique.core.VertxModule;
                import jakarta.inject.Singleton;

                public class Outer {
                    @VertiqueApp
                    @Singleton
                    @Component(modules = {VertxModule.class, StubLifecycleModule.class})
                    public interface Inner extends VertiqueApplicationComponent {}
                }
                """);

        var result = ProcessorTestHarness.run(processors(), STUB_MODULE, nested);

        result.assertFailed();
        result.assertErrorMessage("must be placed on a top-level interface");
    }

    @Test
    @DisplayName("a single valid component generates exactly one factory + one SPI line across rounds")
    void singleComponent_generatesExactlyOnce() throws IOException {
        var result = ProcessorTestHarness.run(processors(), STUB_MODULE, VALID_COMPONENT);

        result.assertSuccess();

        // Exactly one generated factory source — emit-once guards against a duplicate Filer write
        // when Dagger drives multiple processing rounds.
        long factoryCount = result.compilation().generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TestAppComponentVertiqueComponentFactory"))
                .count();
        assertEquals(1, factoryCount, "Expected exactly one generated factory source");

        // The SPI resource names the factory FQN on exactly one line — no duplicated registration.
        var serviceFile = result.compilation().generatedFile(StandardLocation.CLASS_OUTPUT, "", SERVICE_RESOURCE_PATH);
        assertTrue(serviceFile.isPresent(), "Expected the VertiqueComponentFactory service resource to be generated");
        String content = serviceFile.get().getCharContent(true).toString();
        long spiLineCount = content.lines().filter(line -> !line.isBlank()).count();
        assertEquals(1, spiLineCount, "Expected exactly one non-blank SPI registration line");
        assertEquals(GENERATED_FACTORY_FQN, content.strip());
    }

    @Test
    @DisplayName("two @VertiqueApp types in one compilation are rejected with the exactly-one message")
    void twoVertiqueApps_areRejected() {
        var first = SourceFiles.inline("com.example.app.FirstApp", """
                package com.example.app;

                import dagger.Component;
                import dev.vertique.application.VertiqueApp;
                import dev.vertique.application.VertiqueApplicationComponent;
                import dev.vertique.core.VertxModule;
                import jakarta.inject.Singleton;

                @VertiqueApp
                @Singleton
                @Component(modules = {VertxModule.class, StubLifecycleModule.class})
                public interface FirstApp extends VertiqueApplicationComponent {}
                """);
        var second = SourceFiles.inline("com.example.app.SecondApp", """
                package com.example.app;

                import dagger.Component;
                import dev.vertique.application.VertiqueApp;
                import dev.vertique.application.VertiqueApplicationComponent;
                import dev.vertique.core.VertxModule;
                import jakarta.inject.Singleton;

                @VertiqueApp
                @Singleton
                @Component(modules = {VertxModule.class, StubLifecycleModule.class})
                public interface SecondApp extends VertiqueApplicationComponent {}
                """);

        var result = ProcessorTestHarness.run(processors(), STUB_MODULE, first, second);

        result.assertFailed();
        result.assertErrorMessage("exactly one @VertiqueApp per application; found also");
    }

    @Test
    @DisplayName("@VertiqueApp @Component with a custom @Component.Builder is rejected with the custom-builder message")
    void customBuilder_isRejected() {
        // A component that declares its own @Component.Builder prevents the processor from generating
        // Dagger<Component>.builder().vertxModule(...).build() — the generated builder API would differ.
        var component = SourceFiles.inline("com.example.app.CustomBuilderComponent", """
                package com.example.app;

                import dagger.Component;
                import dev.vertique.application.VertiqueApp;
                import dev.vertique.application.VertiqueApplicationComponent;
                import dev.vertique.core.VertxModule;
                import jakarta.inject.Singleton;

                @VertiqueApp
                @Singleton
                @Component(modules = {VertxModule.class, StubLifecycleModule.class})
                public interface CustomBuilderComponent extends VertiqueApplicationComponent {

                    @Component.Builder
                    interface Builder {
                        Builder vertxModule(VertxModule module);
                        CustomBuilderComponent build();
                    }
                }
                """);

        var result = ProcessorTestHarness.run(processors(), STUB_MODULE, component);

        result.assertFailed();
        result.assertErrorMessage("custom @Component.Factory/@Component.Builder");
        result.assertErrorMessage("not supported");
    }

    @Test
    @DisplayName("@VertiqueApp @Component with a custom @Component.Factory is rejected with the custom-builder message")
    void customFactory_isRejected() {
        // A component that declares its own @Component.Factory is rejected by the same guard that
        // rejects @Component.Builder — the processor's generated code calls
        // Dagger<Component>.builder().vertxModule(...).build(), which is only valid for the default
        // Dagger builder. A custom factory produces a different generated API and would cause a
        // confusing downstream compile error.
        var component = SourceFiles.inline("com.example.app.CustomFactoryComponent", """
                package com.example.app;

                import dagger.Component;
                import dev.vertique.application.VertiqueApp;
                import dev.vertique.application.VertiqueApplicationComponent;
                import dev.vertique.core.VertxModule;
                import jakarta.inject.Singleton;

                @VertiqueApp
                @Singleton
                @Component(modules = {VertxModule.class, StubLifecycleModule.class})
                public interface CustomFactoryComponent extends VertiqueApplicationComponent {

                    @Component.Factory
                    interface Factory {
                        CustomFactoryComponent create(VertxModule vertxModule);
                    }
                }
                """);

        var result = ProcessorTestHarness.run(processors(), STUB_MODULE, component);

        result.assertFailed();
        result.assertErrorMessage("custom @Component.Factory/@Component.Builder");
        result.assertErrorMessage("not supported");
    }
}
