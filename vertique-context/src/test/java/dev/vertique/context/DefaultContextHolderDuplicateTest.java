// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Exercises {@link ContextInternal#duplicate(boolean) duplicate(true)} against the
 * substrate's {@link ContextLocalServiceProvider} duplicator and asserts the three buckets the
 * duplicator javadoc enumerates: deep-copied framework values, truly-immutable framework values,
 * and intentionally-shared-by-reference values.
 *
 * <p>The framework does not call {@code duplicate(true)} itself; this test pins the invariant so
 * a third party (interceptor, custom verticle) that does call it gets the documented behaviour.
 *
 * <p>The duplicator's "bucket 3" reference-sharing contract for {@code JobContext} is covered by
 * a parallel test in {@code vertique-job-core} — {@code vertique-core} has no dependency on
 * {@code vertique-job-core}, so this test uses a test-local mutable class
 * ({@link MutableTestValue}) for the same invariant.
 *
 * <p>MDC deep-copy isolation tests live in {@code vertique-logging} where {@code MDCContexts}
 * is defined — see {@code MdcContextHolderDuplicateTest}.
 *
 * <p>This test intentionally uses only test-local {@link ContextValue} stubs so that
 * {@code vertique-context} does not depend on any higher-level module (e.g.
 * {@code vertique-security-core}) at compile or test scope.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DefaultContextHolderDuplicateTest {

    @Test
    @DisplayName("duplicate(true) shares immutable ContextValue by reference")
    void immutableContextValueIsSharedByReference(Vertx vertx, VertxTestContext ctx) {
        ImmutableStubContextValue bound = new ImmutableStubContextValue("u-1");
        ContextInternal source = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        source.runOnContext(v -> {
            ContextHolder.Scope scope = ContextValues.bind(ImmutableStubContextValue.class, bound);
            try {
                ContextInternal duplicate = source.duplicate(true);
                duplicate.runOnContext(v2 -> {
                    try {
                        assertSame(
                                bound,
                                ContextValues.current(ImmutableStubContextValue.class)
                                        .orElseThrow(),
                                "immutable ContextValue must be shared by reference across duplicates");
                        ctx.completeNow();
                    } catch (Throwable t) {
                        ctx.failNow(t);
                    } finally {
                        scope.close();
                    }
                });
            } catch (Throwable t) {
                scope.close();
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("duplicate(true) shares application-supplied mutable values by reference (bucket-3 contract)")
    void applicationSuppliedMutableIsSharedByReference(Vertx vertx, VertxTestContext ctx) {
        MutableTestValue shared = new MutableTestValue();
        shared.counter.set(1);
        ContextInternal source = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        source.runOnContext(v -> {
            // Bind without try-with-resources so the scope stays open across the async chain.
            ContextHolder.Scope scope = ContextValues.bind(MutableTestValue.class, shared);
            try {
                ContextInternal duplicate = source.duplicate(true);
                duplicate.runOnContext(v2 -> {
                    try {
                        MutableTestValue seen =
                                ContextValues.current(MutableTestValue.class).orElseThrow();
                        assertSame(
                                shared,
                                seen,
                                "application-supplied mutable values are stored by reference in the duplicator (bucket 3) — same object visible on the duplicate");
                        // Mutate via the duplicate's reference, then re-read from the duplicate
                        // and from the source — both observe the mutation because both hold the
                        // same object reference (not a copy).
                        seen.counter.incrementAndGet();
                        assertEquals(2, seen.counter.get(), "duplicate-side read observes the mutation it just made");
                        source.runOnContext(v3 -> {
                            try {
                                assertEquals(
                                        2,
                                        ContextValues.current(MutableTestValue.class)
                                                .orElseThrow()
                                                .counter
                                                .get(),
                                        "source-side read observes the mutation the duplicate made (bucket 3)");
                                ctx.completeNow();
                            } catch (Throwable t) {
                                ctx.failNow(t);
                            } finally {
                                scope.close();
                            }
                        });
                    } catch (Throwable t) {
                        ctx.failNow(t);
                    }
                });
            } catch (Throwable t) {
                scope.close();
                ctx.failNow(t);
            }
        });
    }

    // --- Helpers ---

    /**
     * Immutable test-local value used to exercise the duplicator's "truly-immutable, shared by
     * reference" bucket without depending on any security or application module. The single
     * {@code id} field is final, making instances safe to share across context duplicates.
     *
     * @param id an opaque identifier used to distinguish instances in assertions
     */
    private record ImmutableStubContextValue(String id) implements ContextValue {}

    /**
     * Mutable test-local value class used to verify the duplicator's "intentionally shared by
     * reference" bucket without depending on {@code JobContext} (which lives in
     * {@code vertique-job-core}). Bound under its own FQCN by
     * {@link ContextValues#bind(Class, Object)}.
     */
    private static final class MutableTestValue implements ContextValue {
        private final AtomicInteger counter = new AtomicInteger();
    }
}
