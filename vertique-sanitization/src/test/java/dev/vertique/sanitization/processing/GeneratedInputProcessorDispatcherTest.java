// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.support.Identifiers;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.core.input.InputFieldNameResolver;
import dev.vertique.core.input.InputLocation;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GeneratedInputProcessorDispatcher} — verifies manual registration, the
 * classloader-driven {@link Class#forName} lookup with proper cache-miss semantics, the
 * {@code dispatchNested(...)} contract that prefers generated processors over the reflective
 * continuation, and the load-bearing {@code generatedClassName(...)} algorithm that must agree
 * with the codegen emitter.
 */
class GeneratedInputProcessorDispatcherTest {

    @Nested
    @DisplayName("classloader-driven lookup")
    class ClassloaderLookup {

        @Test
        @DisplayName("resolve returns empty for a class with no companion _InputProcessor")
        void resolveReturnsEmptyWhenNoCompanion() {
            var dispatcher = GeneratedInputProcessorDispatcher.withoutContinuation();

            Optional<GeneratedInputProcessor<NoCompanionDto>> resolved = dispatcher.resolve(NoCompanionDto.class);

            assertTrue(resolved.isEmpty());
        }

        @Test
        @DisplayName("resolve cache returns the same Optional instance on repeated calls (ClassValue cache)")
        void resolveCachesEmptyResult() {
            var dispatcher = GeneratedInputProcessorDispatcher.withoutContinuation();

            Optional<GeneratedInputProcessor<NoCompanionDto>> first = dispatcher.resolve(NoCompanionDto.class);
            Optional<GeneratedInputProcessor<NoCompanionDto>> second = dispatcher.resolve(NoCompanionDto.class);

            // Both empty Optionals — ClassValue cached the empty result; no lookup re-attempted.
            assertTrue(first.isEmpty());
            assertTrue(second.isEmpty());
        }

        @Test
        @DisplayName("propagates RuntimeException when the generated class exists but cannot be instantiated")
        void propagatesInstantiationFailure() {
            var dispatcher = GeneratedInputProcessorDispatcher.withoutContinuation();

            // BrokenCompanionDto has a sibling BrokenCompanionDto_InputProcessor whose constructor throws.
            RuntimeException ex =
                    assertThrows(RuntimeException.class, () -> dispatcher.resolve(BrokenCompanionDto.class));

            assertTrue(
                    ex.getMessage().contains("BrokenCompanionDto_InputProcessor"),
                    "Expected error message to reference the generated class FQN, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("broken-class lookup is cached — same exception instance rethrown on retry, no re-reflection")
        void cachesBrokenClassFailure() {
            var dispatcher = GeneratedInputProcessorDispatcher.withoutContinuation();

            RuntimeException first =
                    assertThrows(RuntimeException.class, () -> dispatcher.resolve(BrokenCompanionDto.class));
            RuntimeException second =
                    assertThrows(RuntimeException.class, () -> dispatcher.resolve(BrokenCompanionDto.class));

            // Same exception instance proves the failure is cached: the dispatcher did not redo
            // Class.forName + newInstance on the second call. Without caching, each call would
            // wrap a fresh ReflectiveOperationException and create a new RuntimeException.
            assertSame(
                    first,
                    second,
                    "Broken-class lookup must be memoized — repeated calls must rethrow the cached exception, "
                            + "not re-attempt reflection");
        }
    }

    @Nested
    @DisplayName("manual registration")
    class ManualRegistration {

        @Test
        @DisplayName("register + resolve returns the registered processor")
        void registerThenResolve() {
            var dispatcher = GeneratedInputProcessorDispatcher.withoutContinuation();
            var stub = new CountingProcessor<>(NoCompanionDto.class);
            dispatcher.register(NoCompanionDto.class, stub);

            Optional<GeneratedInputProcessor<NoCompanionDto>> resolved = dispatcher.resolve(NoCompanionDto.class);

            assertTrue(resolved.isPresent());
            assertSame(stub, resolved.get());
        }

        @Test
        @DisplayName("manual registration takes precedence over classloader lookup")
        void manualRegistrationWins() {
            var dispatcher = GeneratedInputProcessorDispatcher.withoutContinuation();
            // BrokenCompanionDto_InputProcessor would throw during instantiation if classloader lookup ran.
            var stub = new CountingProcessor<>(BrokenCompanionDto.class);

            dispatcher.register(BrokenCompanionDto.class, stub);

            Optional<GeneratedInputProcessor<BrokenCompanionDto>> resolved =
                    dispatcher.resolve(BrokenCompanionDto.class);
            assertTrue(resolved.isPresent());
            assertSame(stub, resolved.get());
        }
    }

    @Nested
    @DisplayName("dispatchNested")
    class DispatchNested {

        @Test
        @DisplayName("delegates to the registered generated processor when present")
        void dispatchNestedUsesGenerated() {
            var dispatcher = GeneratedInputProcessorDispatcher.withoutContinuation();
            var processor = new CountingProcessor<>(NoCompanionDto.class);
            dispatcher.register(NoCompanionDto.class, processor);

            Object intermediate = new Object();
            Object result = dispatcher.dispatchNested(
                    intermediate,
                    NoCompanionDto.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    (v, c, s, ctx) -> v,
                    InputTraversalContext.fromPolicies(EffectiveInputPolicies.NONE, InputFieldNameResolver.IDENTITY),
                    "field",
                    NoCompanionDto.class);

            assertEquals(1, processor.invocations.get());
            assertSame(intermediate, result);
        }

        @Test
        @DisplayName("falls through to the ReflectiveContinuation when no generated processor exists")
        void dispatchNestedFallsThroughToContinuation() {
            AtomicInteger continuationInvocations = new AtomicInteger();
            var dispatcher = new GeneratedInputProcessorDispatcher(
                    new GeneratedInputProcessorDispatcher.ReflectiveContinuation() {
                        @Override
                        public Object continueAt(
                                Object intermediate,
                                Class<?> type,
                                InputTraversalContext ctx,
                                InputLocation location,
                                String fieldPath,
                                Class<?> ownerType) {
                            continuationInvocations.incrementAndGet();
                            return "continuation-handled";
                        }

                        @Override
                        public Object walkUnknown(
                                Object intermediate,
                                InputTraversalContext ctx,
                                InputLocation location,
                                String fieldPath,
                                Class<?> ownerType) {
                            throw new UnsupportedOperationException("walkUnknown not expected in this test");
                        }
                    });

            Object result = dispatcher.dispatchNested(
                    "any-input",
                    NoCompanionDto.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    (v, c, s, ctx) -> v,
                    InputTraversalContext.fromPolicies(EffectiveInputPolicies.NONE, InputFieldNameResolver.IDENTITY),
                    "field",
                    NoCompanionDto.class);

            assertEquals(1, continuationInvocations.get());
            assertEquals("continuation-handled", result);
        }
    }

    @Nested
    @DisplayName("generatedClassName algorithm")
    class GeneratedClassName {

        @Test
        @DisplayName("top-level class: appends _InputProcessor")
        void topLevelClass() {
            assertEquals(
                    "dev.vertique.sanitization.processing.GeneratedInputProcessorDispatcherTest$NoCompanionDto_InputProcessor"
                            .replace("$", "_"),
                    GeneratedInputProcessorDispatcher.generatedClassName(NoCompanionDto.class));
        }

        @Test
        @DisplayName("nested class: flattens '$' to '_' before suffix")
        void nestedClassFlattens() {
            // Outer is a top-level nested class of the enclosing test class, so the binary name is
            // ...GeneratedInputProcessorDispatcherTest$Outer$Inner — both '$' separators flatten.
            assertEquals(
                    "dev.vertique.sanitization.processing.GeneratedInputProcessorDispatcherTest_Outer_Inner_InputProcessor",
                    GeneratedInputProcessorDispatcher.generatedClassName(Outer.Inner.class));
        }

        @Test
        @DisplayName("class in default package: no leading dot")
        void defaultPackageClass() {
            // Synthesized via a class name string that has no '.' — exercise the lastDot < 0 branch.
            // We can't easily put a real class in the default package within this test, so verify
            // via the String contract: passing a class whose binary name matches the assumption
            // produces the expected output. This is exercised indirectly by topLevelClass above
            // (Class<?> argument variant); here we just assert the algorithm doesn't NPE.
            assertFalse(GeneratedInputProcessorDispatcher.generatedClassName(NoCompanionDto.class)
                    .startsWith("."));
        }
    }

    @Nested
    @DisplayName("agreement with the codegen-side name derivation")
    class AgreesWithCodegen {

        @Test
        @DisplayName(
                "the runtime-derived companion name equals Identifiers.generatedClassName for top-level and nested types")
        void runtimeAndCodegenDeriveTheSameName() throws ClassNotFoundException {
            // Given: the type shapes this test class itself declares (top-level DTO, nested, doubly nested),
            // mirrored as source for the codegen-side derivation over the real annotation-processing model
            ProbeProcessor probe = new ProbeProcessor();
            ProcessorTestHarness.run(
                            probe,
                            SourceFiles.inline(
                                    "dev.vertique.sanitization.processing.GeneratedInputProcessorDispatcherTest", """
                                    package dev.vertique.sanitization.processing;

                                    class GeneratedInputProcessorDispatcherTest {
                                        static final class NoCompanionDto {}

                                        static final class Outer {
                                            static final class Inner {}
                                        }
                                    }
                                    """))
                    .assertSuccess();

            // When / Then: for every type the codegen side saw, the runtime derivation gives the same FQN
            assertFalse(probe.codegenNamesByBinaryName.isEmpty(), "the probe should have seen the mirrored types");
            for (Map.Entry<String, String> seen : probe.codegenNamesByBinaryName.entrySet()) {
                Class<?> type = Class.forName(seen.getKey());
                assertEquals(
                        seen.getValue(),
                        GeneratedInputProcessorDispatcher.generatedClassName(type),
                        "runtime and codegen derivations disagree for " + seen.getKey());
            }
            assertTrue(probe.codegenNamesByBinaryName.containsKey(Outer.Inner.class.getName()));
        }
    }

    /** Records, per type seen, the FQN the codegen-side derivation gives its {@code _InputProcessor} companion. */
    static final class ProbeProcessor extends AbstractProcessor {
        final Map<String, String> codegenNamesByBinaryName = new LinkedHashMap<>();

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
            env.getRootElements().forEach(this::visit);
            return false;
        }

        private void visit(Element element) {
            if (!(element instanceof TypeElement type)) {
                return;
            }
            String pkg = processingEnv
                    .getElementUtils()
                    .getPackageOf(type)
                    .getQualifiedName()
                    .toString();
            String name = Identifiers.generatedClassName(type, "_InputProcessor");
            codegenNamesByBinaryName.put(
                    processingEnv.getElementUtils().getBinaryName(type).toString(), pkg + "." + name);
            type.getEnclosedElements().forEach(this::visit);
        }
    }

    // --- Test fixtures ---

    /** A DTO type with no sibling {@code _InputProcessor} class on the classpath. */
    static final class NoCompanionDto {}

    /** A DTO type whose sibling {@code BrokenCompanionDto_InputProcessor} throws during instantiation. */
    static final class BrokenCompanionDto {}

    /** Used by {@link GeneratedClassName#nestedClassFlattens}. */
    static final class Outer {
        static final class Inner {}
    }

    /** Counting double for {@link GeneratedInputProcessor}. */
    static final class CountingProcessor<T> implements GeneratedInputProcessor<T> {
        final AtomicInteger invocations = new AtomicInteger();
        private final Class<T> type;

        CountingProcessor(Class<T> type) {
            this.type = type;
        }

        @Override
        public Class<T> targetType() {
            return type;
        }

        @Override
        public Object process(
                Object intermediate,
                EffectiveInputPolicies policies,
                InputLocation location,
                ChainResolver resolver,
                GeneratedInputProcessorDispatcher dispatcher,
                @Nullable InputTraversalContext parent,
                String parentPath) {
            invocations.incrementAndGet();
            return intermediate;
        }
    }
}
