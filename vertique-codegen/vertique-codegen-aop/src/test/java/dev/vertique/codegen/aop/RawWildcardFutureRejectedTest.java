// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED reproduction for Codex R3-2: a raw or wildcard {@code io.vertx.core.Future} intercepted return
 * is mishandled by {@link AopProxyEmitter}, and the processor does not reject it.
 *
 * <p>Two distinct failure modes motivate the explicit-rejection decision (reject what we don't safely
 * support, LOUDLY):
 * <ul>
 *   <li><strong>Raw {@code Future}</strong> — {@code CodegenContext.unwrapFuture} returns the original
 *       type when there is no type argument, so {@link AopProxyEmitter#isFuture} reports {@code false}
 *       and the method is misclassified as SYNCHRONOUS. The proxy then records the {@code @TestTimed}
 *       outcome before the future settles — a silently-wrong metric, with no compile error at all.
 *   <li><strong>Wildcard {@code Future<? extends Number>}</strong> — the emitter unwraps to the
 *       wildcard element type and emits an invalid back-cast {@code (? extends Number)} in the
 *       generated override, producing a confusing generated-source compile error rather than a clear
 *       diagnostic.
 * </ul>
 *
 * <p>The intended v1 behavior is for {@link AopProcessor} to REJECT both with a clear
 * {@code Diagnostics.error} mentioning the unsupported raw/wildcard {@code Future} return.
 *
 * <p><strong>Red now:</strong> the raw case compiles successfully (mis-metered as sync, so no error
 * diagnostic) and the wildcard case fails only with a generated-source cast error — neither emits the
 * clear rejection diagnostic these tests assert on, so both assertions fail.
 */
class RawWildcardFutureRejectedTest {

    /** A bean whose {@code @TestTimed} method returns a RAW {@code Future} (no type argument). */
    private static JavaFileObject rawFutureBean() {
        return SourceFiles.inline("com.example.RawFutureBean", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class RawFutureBean {
                    @Inject
                    public RawFutureBean() {}
                    @TestTimed
                    @SuppressWarnings("rawtypes")
                    public Future rawFuture() {
                        return Future.succeededFuture("v");
                    }
                }
                """);
    }

    /** A bean whose {@code @TestTimed} method returns a WILDCARD {@code Future<? extends Number>}. */
    private static JavaFileObject wildcardFutureBean() {
        return SourceFiles.inline("com.example.WildcardFutureBean", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class WildcardFutureBean {
                    @Inject
                    public WildcardFutureBean() {}
                    @TestTimed
                    public Future<? extends Number> wildcardFuture() {
                        return Future.succeededFuture(1);
                    }
                }
                """);
    }

    @Test
    @DisplayName("a raw Future intercepted return is rejected with a clear raw/wildcard-Future diagnostic")
    void rawFutureReturnIsRejected() {
        ProcessorTestHarness.run(new AopProcessor(), rawFutureBean())
                .assertFailed()
                .assertErrorMessage("Future");
    }

    @Test
    @DisplayName("a wildcard Future intercepted return is rejected with a clear raw/wildcard-Future diagnostic")
    void wildcardFutureReturnIsRejected() {
        ProcessorTestHarness.run(new AopProcessor(), wildcardFutureBean())
                .assertFailed()
                .assertErrorMessage("wildcard");
    }
}
