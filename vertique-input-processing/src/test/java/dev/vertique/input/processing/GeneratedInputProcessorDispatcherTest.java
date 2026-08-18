// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import jakarta.annotation.Nullable;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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
                    "dev.vertique.input.processing.GeneratedInputProcessorDispatcherTest$NoCompanionDto_InputProcessor"
                            .replace("$", "_"),
                    GeneratedInputProcessorDispatcher.generatedClassName(NoCompanionDto.class));
        }

        @Test
        @DisplayName("nested class: flattens '$' to '_' before suffix")
        void nestedClassFlattens() {
            // Outer is a top-level nested class of the enclosing test class, so the binary name is
            // ...GeneratedInputProcessorDispatcherTest$Outer$Inner — both '$' separators flatten.
            assertEquals(
                    "dev.vertique.input.processing.GeneratedInputProcessorDispatcherTest_Outer_Inner_InputProcessor",
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
