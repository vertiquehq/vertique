// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Binds;
import dagger.multibindings.IntoSet;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link AutoWireProcessor} registers a class annotated {@code @WorkflowSignalSource} into the
 * {@code Set} of the interface it implements that is marked {@code @SignalSourceType}, with no application
 * {@code @Provides} method.
 *
 * <p>The processor identifies both markers by fully qualified name, so the annotations are inline stubs and this
 * module takes no dependency on the workflow modules.
 */
class AutoWireProcessorSignalSourcesTest {

    private static final String PACKAGE = "dev.vertique.examples";
    private static final String GENERATED_MODULE_FQN = PACKAGE + ".GeneratedRegistrationsModule";

    private static final String WORKFLOW_SIGNAL_SOURCE_ANNOTATION = """
            package dev.vertique.workflow.source;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.CLASS)
            public @interface WorkflowSignalSource {}
            """;

    private static final String SIGNAL_SOURCE_TYPE_ANNOTATION = """
            package dev.vertique.workflow.source;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.CLASS)
            public @interface SignalSourceType {
                Class<?> module();
            }
            """;

    private static final String FEED_MODULE = """
            package dev.vertique.examples;

            import dagger.Module;

            @Module
            public abstract class FeedModule {}
            """;

    private static final String FEED = """
            package dev.vertique.examples;

            import dev.vertique.workflow.source.SignalSourceType;

            @SignalSourceType(module = FeedModule.class)
            public interface Feed<T> {}
            """;

    private static final String SHIPMENT = """
            package dev.vertique.examples;

            public record Shipment(String id) {}
            """;

    private static final String SHIPMENT_REPOSITORY = """
            package dev.vertique.examples;

            import jakarta.inject.Inject;

            public class ShipmentRepository {
                @Inject
                public ShipmentRepository() {}
            }
            """;

    private static final String CARRIER_FEED = """
            package dev.vertique.examples;

            import dev.vertique.workflow.source.WorkflowSignalSource;
            import jakarta.inject.Inject;

            @WorkflowSignalSource
            public final class CarrierFeed implements Feed<Shipment> {
                @Inject
                public CarrierFeed(ShipmentRepository repository) {}
            }
            """;

    private static final String PORT_FEED = """
            package dev.vertique.examples;

            import dev.vertique.workflow.source.WorkflowSignalSource;
            import jakarta.inject.Inject;

            @WorkflowSignalSource
            public final class PortFeed implements Feed<Shipment> {
                @Inject
                public PortFeed(ShipmentRepository repository) {}
            }
            """;

    @Test
    @DisplayName("a @WorkflowSignalSource class is registered into the Set of its marked interface")
    void sourceClassIsRegisteredIntoTheSetOfItsMarkedInterface() throws Exception {
        // Given a marked generic interface and a source class with an @Inject constructor over a repository
        // When the processor compiles them
        var result = ProcessorTestHarness.run(new AutoWireProcessor(), commonStubs(carrierFeed()));

        // Then the generated registrations module has an abstract @Binds @IntoSet method that takes CarrierFeed and
        // returns Feed<?>
        result.assertSuccess();
        Class<?> module = result.loadGeneratedClass(GENERATED_MODULE_FQN);
        Class<?> carrierFeed = result.loadGeneratedClass(PACKAGE + ".CarrierFeed");
        List<Method> bindings = Arrays.stream(module.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Binds.class))
                .toList();
        assertEquals(1, bindings.size(), "one binding for the one source class");
        Method binding = bindings.get(0);
        assertTrue(Modifier.isAbstract(binding.getModifiers()), "the binding is abstract");
        assertTrue(binding.isAnnotationPresent(IntoSet.class), "the binding contributes into a Set");
        assertEquals(PACKAGE + ".Feed<?>", binding.getGenericReturnType().getTypeName());
        assertArrayEquals(new Class<?>[] {carrierFeed}, binding.getParameterTypes());
    }

    @Test
    @DisplayName("a source class with no @Inject constructor fails compilation naming the class")
    void sourceClassWithoutInjectConstructorFailsCompilationNamingTheClass() {
        // Given the source class declares a no-argument constructor with no @Inject
        JavaFileObject carrierFeed = SourceFiles.inline(PACKAGE + ".CarrierFeed", """
                package dev.vertique.examples;

                import dev.vertique.workflow.source.WorkflowSignalSource;

                @WorkflowSignalSource
                public final class CarrierFeed implements Feed<Shipment> {
                    public CarrierFeed() {}
                }
                """);

        // When the processor compiles it
        var result = ProcessorTestHarness.run(new AutoWireProcessor(), commonStubs(carrierFeed));

        // Then compilation fails and the error names the class
        result.assertFailed().assertErrorMessage(PACKAGE + ".CarrierFeed");
    }

    @Test
    @DisplayName("a source class the generated module cannot reference fails compilation naming it")
    void sourceClassTheGeneratedModuleCannotReferenceFailsCompilationNamingIt() {
        // Given a package-private source class in one package and a public one in a sibling package, so the output
        // package resolves to their common parent
        JavaFileObject hidden = SourceFiles.inline(PACKAGE + ".a.CarrierFeed", """
                package dev.vertique.examples.a;

                import dev.vertique.examples.Feed;
                import dev.vertique.examples.Shipment;
                import dev.vertique.workflow.source.WorkflowSignalSource;
                import jakarta.inject.Inject;

                @WorkflowSignalSource
                final class CarrierFeed implements Feed<Shipment> {
                    @Inject
                    public CarrierFeed() {}
                }
                """);
        JavaFileObject hiddenAsSetMember = SourceFiles.inline(PACKAGE + ".a.CarrierFeed", """
                package dev.vertique.examples.a;

                import dev.vertique.codegen.RegisterIntoSet;
                import dev.vertique.examples.Feed;
                import dev.vertique.examples.Shipment;
                import jakarta.inject.Inject;

                @RegisterIntoSet(Feed.class)
                final class CarrierFeed implements Feed<Shipment> {
                    @Inject
                    public CarrierFeed() {}
                }
                """);
        JavaFileObject visible = SourceFiles.inline(PACKAGE + ".b.OtherFeed", """
                package dev.vertique.examples.b;

                import dev.vertique.examples.Feed;
                import dev.vertique.examples.Shipment;
                import dev.vertique.workflow.source.WorkflowSignalSource;
                import jakarta.inject.Inject;

                @WorkflowSignalSource
                public final class OtherFeed implements Feed<Shipment> {
                    @Inject
                    public OtherFeed() {}
                }
                """);

        // When the processor compiles them, and again with the hidden class registered through @RegisterIntoSet
        var asSource = ProcessorTestHarness.run(new AutoWireProcessor(), commonStubs(hidden, visible));
        var asSetMember = ProcessorTestHarness.run(new AutoWireProcessor(), commonStubs(hiddenAsSetMember, visible));

        // Then the first compilation fails naming the hidden class, and the second compiles and reports the skip as a
        // mandatory warning, so the error is specific to source classes
        asSource.assertFailed().assertErrorMessage(PACKAGE + ".a.CarrierFeed");
        asSetMember.assertSuccess().assertWarningMessage(PACKAGE + ".a.CarrierFeed");
    }

    @Test
    @DisplayName("a component that leaves out the generated registrations module fails to build")
    void componentThatLeavesOutTheGeneratedModuleFailsToBuildNamingTheMissingSet() {
        // Given a consumer module that reads Set<Feed<?>> and declares it with no @Multibinds, and two components over
        // it, one that also includes the generated registrations module and one that does not
        JavaFileObject consumed = SourceFiles.inline(PACKAGE + ".Consumed", """
                package dev.vertique.examples;

                public final class Consumed {}
                """);
        JavaFileObject consumerModule = SourceFiles.inline(PACKAGE + ".ConsumerModule", """
                package dev.vertique.examples;

                import dagger.Module;
                import dagger.Provides;
                import dagger.multibindings.ElementsIntoSet;
                import java.util.Set;

                @Module
                public abstract class ConsumerModule {
                    @Provides
                    @ElementsIntoSet
                    static Set<Consumed> consumed(Set<Feed<?>> feeds) {
                        return Set.of();
                    }
                }
                """);
        JavaFileObject withRegistrations = SourceFiles.inline(PACKAGE + ".ComponentWithRegistrations", """
                package dev.vertique.examples;

                import dagger.Component;
                import java.util.Set;

                @Component(modules = {ConsumerModule.class, GeneratedRegistrationsModule.class})
                public interface ComponentWithRegistrations {
                    Set<Consumed> consumed();
                }
                """);
        JavaFileObject leavingOut = SourceFiles.inline(PACKAGE + ".ComponentLeavingOut", """
                package dev.vertique.examples;

                import dagger.Component;
                import java.util.Set;

                @Component(modules = ConsumerModule.class)
                public interface ComponentLeavingOut {
                    Set<Consumed> consumed();
                }
                """);

        // When the processor and Dagger compile each component in a run of its own, because Dagger reports a missing
        // binding in the first round and javac then skips the round in which the other component would see the
        // generated module
        var leavingOutResult = ProcessorTestHarness.run(
                List.of(new AutoWireProcessor(), new dagger.internal.codegen.ComponentProcessor()),
                commonStubs(carrierFeed(), consumed, consumerModule, leavingOut));
        var withRegistrationsResult = ProcessorTestHarness.run(
                List.of(new AutoWireProcessor(), new dagger.internal.codegen.ComponentProcessor()),
                commonStubs(carrierFeed(), consumed, consumerModule, withRegistrations));

        // Then compilation fails with a missing-binding error for Set<Feed<?>> against the component that leaves the
        // module out, and nothing is reported against the component that includes it
        leavingOutResult.assertFailed();
        List<String> againstLeavingOut = errorsAbout(leavingOutResult, "ComponentLeavingOut");
        assertFalse(againstLeavingOut.isEmpty(), "the component without the generated module must not build");
        assertTrue(
                againstLeavingOut.stream().anyMatch(message -> message.contains("Feed<?>")),
                () -> "the error must name the missing Set<Feed<?>> but was " + againstLeavingOut);
        withRegistrationsResult.assertSuccess();
        assertEquals(
                List.of(),
                errorsAbout(withRegistrationsResult, "ComponentWithRegistrations"),
                "the component that includes the generated module must build");
    }

    @Test
    @DisplayName("the generated registrations module includes the transport module of the interface it registers into")
    void generatedModuleIncludesTheTransportModuleOfTheMarkedInterface() {
        // Given two source classes of one marked interface, and a second compilation with only an @RegisterIntoSet
        // class
        JavaFileObject registeredIntoSet = SourceFiles.inline(PACKAGE + ".CarrierFeed", """
                package dev.vertique.examples;

                import dev.vertique.codegen.RegisterIntoSet;
                import jakarta.inject.Inject;

                @RegisterIntoSet(Feed.class)
                public final class CarrierFeed implements Feed<Shipment> {
                    @Inject
                    public CarrierFeed(ShipmentRepository repository) {}
                }
                """);

        // When the processor compiles each set
        var sources = ProcessorTestHarness.run(new AutoWireProcessor(), commonStubs(carrierFeed(), portFeed()));
        var setMember = ProcessorTestHarness.run(new AutoWireProcessor(), commonStubs(registeredIntoSet));

        // Then the module of the two sources includes FeedModule exactly once, and the module of the set member
        // includes nothing
        sources.assertSuccess();
        Class<?> feedModule = sources.loadGeneratedClass(PACKAGE + ".FeedModule");
        assertArrayEquals(
                new Class<?>[] {feedModule},
                sources.loadGeneratedClass(GENERATED_MODULE_FQN)
                        .getAnnotation(dagger.Module.class)
                        .includes());
        setMember.assertSuccess();
        assertEquals(
                0,
                setMember
                        .loadGeneratedClass(GENERATED_MODULE_FQN)
                        .getAnnotation(dagger.Module.class)
                        .includes()
                        .length);
    }

    /** The messages of the errors that name the type or that sit in its source file. */
    private static List<String> errorsAbout(ProcessorTestHarness.Result result, String simpleName) {
        return result.compilation().diagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .filter(diagnostic -> diagnostic.getMessage(null).contains(simpleName)
                        || (diagnostic.getSource() != null
                                && diagnostic.getSource().getName().contains(simpleName)))
                .map(diagnostic -> diagnostic.getMessage(null))
                .toList();
    }

    private static JavaFileObject carrierFeed() {
        return SourceFiles.inline(PACKAGE + ".CarrierFeed", CARRIER_FEED);
    }

    private static JavaFileObject portFeed() {
        return SourceFiles.inline(PACKAGE + ".PortFeed", PORT_FEED);
    }

    private static JavaFileObject[] commonStubs(JavaFileObject... classes) {
        return Stream.concat(
                        Stream.of(
                                SourceFiles.inline(
                                        "dev.vertique.workflow.source.WorkflowSignalSource",
                                        WORKFLOW_SIGNAL_SOURCE_ANNOTATION),
                                SourceFiles.inline(
                                        "dev.vertique.workflow.source.SignalSourceType", SIGNAL_SOURCE_TYPE_ANNOTATION),
                                SourceFiles.inline(PACKAGE + ".FeedModule", FEED_MODULE),
                                SourceFiles.inline(PACKAGE + ".Feed", FEED),
                                SourceFiles.inline(PACKAGE + ".Shipment", SHIPMENT),
                                SourceFiles.inline(PACKAGE + ".ShipmentRepository", SHIPMENT_REPOSITORY)),
                        Arrays.stream(classes))
                .toArray(JavaFileObject[]::new);
    }
}
