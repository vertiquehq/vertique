// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Compile-testing coverage for generic {@code @RegisterAs} and {@code @RegisterIntoSet}
 * registrations emitted by {@link AutoWireProcessor}.
 */
class AutoWireProcessorRegistrationsTest {

    private static final String GENERATED_MODULE_FQN = "dev.vertique.examples.GeneratedRegistrationsModule";

    @Test
    @DisplayName("direct and set registrations generate abstract Binds methods")
    void directAndSetRegistrations_generateBindsMethods() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.examples.Bar", """
                        package dev.vertique.examples;

                        public interface Bar {}
                        """),
                SourceFiles.inline("dev.vertique.examples.Foo", """
                        package dev.vertique.examples;

                        import dev.vertique.codegen.RegisterAs;
                        import dev.vertique.codegen.RegisterIntoSet;
                        import jakarta.inject.Inject;

                        @RegisterAs(Bar.class)
                        @RegisterIntoSet(Bar.class)
                        public final class Foo implements Bar {
                            @Inject
                            public Foo() {}
                        }
                        """));

        result.assertSuccess()
                .assertGeneratedSourceContains(GENERATED_MODULE_FQN, "abstract class GeneratedRegistrationsModule")
                .assertGeneratedSourceContains(GENERATED_MODULE_FQN, "@Binds")
                .assertGeneratedSourceContains(GENERATED_MODULE_FQN, "@IntoSet")
                .assertGeneratedSourceContains(GENERATED_MODULE_FQN, "bindFoo")
                .assertGeneratedSourceContains(GENERATED_MODULE_FQN, "Foo implementation");
    }

    @Test
    @DisplayName("repeatable registrations emit one binding per explicit target")
    void repeatableRegistrations_emitEachTarget() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.examples.DirectTarget", """
                        package dev.vertique.examples;

                        public interface DirectTarget {}
                        """),
                SourceFiles.inline("dev.vertique.examples.SecondTarget", """
                        package dev.vertique.examples;

                        public interface SecondTarget {}
                        """),
                SourceFiles.inline("dev.vertique.examples.MultiRegistration", """
                        package dev.vertique.examples;

                        import dev.vertique.codegen.RegisterAs;
                        import dev.vertique.codegen.RegisterIntoSet;
                        import jakarta.inject.Inject;

                        @RegisterAs(DirectTarget.class)
                        @RegisterAs(SecondTarget.class)
                        @RegisterIntoSet(DirectTarget.class)
                        @RegisterIntoSet(SecondTarget.class)
                        public final class MultiRegistration implements DirectTarget, SecondTarget {
                            @Inject
                            public MultiRegistration() {}
                        }
                        """));

        result.assertSuccess()
                .assertGeneratedSourceContains(GENERATED_MODULE_FQN, "bindMultiRegistration")
                .assertGeneratedSourceContains(GENERATED_MODULE_FQN, "bindMultiRegistrationToSecondTarget")
                .assertGeneratedSourceContains(GENERATED_MODULE_FQN, "@IntoSet");
    }

    @Test
    @DisplayName("source-retained registration annotations are absent at runtime")
    void registrations_areSourceRetained() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.examples.Bar", """
                        package dev.vertique.examples;

                        public interface Bar {}
                        """),
                SourceFiles.inline("dev.vertique.examples.Foo", """
                        package dev.vertique.examples;

                        import dev.vertique.codegen.RegisterAs;
                        import jakarta.inject.Inject;

                        @RegisterAs(Bar.class)
                        public final class Foo implements Bar {
                            @Inject
                            public Foo() {}
                        }
                        """));

        result.assertSuccess();
        Class<?> foo = result.loadGeneratedClass("dev.vertique.examples.Foo");
        Assertions.assertFalse(foo.isAnnotationPresent(dev.vertique.codegen.RegisterAs.class));
    }

    @Test
    @DisplayName("NoAutoWire suppresses direct and set registrations")
    void noAutoWire_suppressesAllRegistrations() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.examples.Bar", """
                        package dev.vertique.examples;

                        public interface Bar {}
                        """),
                SourceFiles.inline("dev.vertique.examples.Foo", """
                        package dev.vertique.examples;

                        import dev.vertique.codegen.NoAutoWire;
                        import dev.vertique.codegen.RegisterAs;
                        import dev.vertique.codegen.RegisterIntoSet;
                        import jakarta.inject.Inject;

                        @NoAutoWire
                        @RegisterAs(Bar.class)
                        @RegisterIntoSet(Bar.class)
                        public final class Foo implements Bar {
                            @Inject
                            public Foo() {}
                        }
                        """));

        result.assertSuccess();
        Assertions.assertTrue(
                result.compilation().generatedSourceFile(GENERATED_MODULE_FQN).isEmpty());
    }

    @Test
    @DisplayName("invalid implementation and target declarations fail compilation")
    void invalidDeclarations_failCompilation() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.examples.Bar", """
                        package dev.vertique.examples;

                        public interface Bar {}
                        """),
                SourceFiles.inline("dev.vertique.examples.Unrelated", """
                        package dev.vertique.examples;

                        public interface Unrelated {}
                        """),
                SourceFiles.inline("dev.vertique.examples.AbstractFoo", """
                        package dev.vertique.examples;

                        import dev.vertique.codegen.RegisterAs;
                        import jakarta.inject.Inject;

                        @RegisterAs(Bar.class)
                        public abstract class AbstractFoo implements Bar {
                            @Inject
                            protected AbstractFoo() {}
                        }
                        """),
                SourceFiles.inline("dev.vertique.examples.WrongFoo", """
                        package dev.vertique.examples;

                        import dev.vertique.codegen.RegisterAs;
                        import jakarta.inject.Inject;

                        @RegisterAs(Unrelated.class)
                        public final class WrongFoo implements Bar {
                            @Inject
                            public WrongFoo() {}
                        }
                        """));

        result.assertFailed().assertErrorMessage("concrete class").assertErrorMessage("not assignable");
    }

    @Test
    @DisplayName("missing or duplicate Inject constructors fail registration validation")
    void constructorValidation_rejectsMissingAndDuplicateInjectConstructors() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.examples.Bar", """
                        package dev.vertique.examples;

                        public interface Bar {}
                        """),
                SourceFiles.inline("dev.vertique.examples.NoConstructor", """
                        package dev.vertique.examples;

                        import dev.vertique.codegen.RegisterAs;

                        @RegisterAs(Bar.class)
                        public final class NoConstructor implements Bar {
                            public NoConstructor() {}
                        }
                        """),
                SourceFiles.inline("dev.vertique.examples.DuplicateConstructor", """
                        package dev.vertique.examples;

                        import dev.vertique.codegen.RegisterAs;
                        import jakarta.inject.Inject;

                        @RegisterAs(Bar.class)
                        public final class DuplicateConstructor implements Bar {
                            @Inject
                            public DuplicateConstructor() {}

                            @Inject
                            public DuplicateConstructor(String ignored) {}
                        }
                        """));

        result.assertFailed()
                .assertErrorMessage("no @Inject constructor")
                .assertErrorMessage("multiple @Inject constructors");
    }

    @Test
    @DisplayName("legacy javax Inject constructor is accepted")
    void legacyJavaxInject_isAccepted() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.examples.Bar", """
                        package dev.vertique.examples;

                        public interface Bar {}
                        """),
                SourceFiles.inline("dev.vertique.examples.LegacyFoo", """
                        package dev.vertique.examples;

                        import dev.vertique.codegen.RegisterAs;
                        import javax.inject.Inject;

                        @RegisterAs(Bar.class)
                        public final class LegacyFoo implements Bar {
                            @Inject
                            public LegacyFoo() {}
                        }
                        """));

        result.assertSuccess().assertGeneratedSourceContains(GENERATED_MODULE_FQN, "LegacyFoo");
    }

    @Test
    @DisplayName("generated registration module compiles and supplies direct and set bindings to Dagger")
    void generatedModule_compilesAndSuppliesDaggerBindings() {
        var result = ProcessorTestHarness.run(
                List.of(new AutoWireProcessor(), new dagger.internal.codegen.ComponentProcessor()),
                SourceFiles.inline("dev.vertique.examples.Bar", """
                        package dev.vertique.examples;

                        public interface Bar {}
                        """),
                SourceFiles.inline("dev.vertique.examples.Foo", """
                        package dev.vertique.examples;

                        import dev.vertique.codegen.RegisterAs;
                        import dev.vertique.codegen.RegisterIntoSet;
                        import jakarta.inject.Inject;

                        @RegisterAs(Bar.class)
                        @RegisterIntoSet(Bar.class)
                        public final class Foo implements Bar {
                            @Inject
                            public Foo() {}
                        }
                        """),
                SourceFiles.inline("dev.vertique.examples.AppComponent", """
                        package dev.vertique.examples;

                        import dagger.Component;
                        import java.util.Set;

                        @Component(modules = GeneratedRegistrationsModule.class)
                        public interface AppComponent {
                            Bar directBar();
                            Set<Bar> bars();
                        }
                        """));

        result.assertSuccess();
        Assertions.assertNotNull(result.loadGeneratedClass("dev.vertique.examples.DaggerAppComponent"));
    }
}
