// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the FR-013-05 custom-aspect sync guard is emitted into the generated proxy's
 * sync-returning overrides (Bug F5).
 *
 * <p>A sync-returning method cannot surface a deferred completion, so the generated override must
 * fail loudly when a custom aspect defers the around-chain: the source asserts the {@code isComplete()}
 * incompleteness check and the {@code throw new IllegalStateException(...)} guard are present for both
 * a value-returning and a {@code void}-returning sync method. The full runtime "a deferring aspect
 * throws" behavioral proof lives in the e2e module (plan slice 2.3); a source assertion here is
 * sufficient to prove the guard is generated.
 */
class SyncOverrideGuardTest {

    private static final String CALCULATOR_FQN = "com.example.Calculator";
    private static final String PROXY_FQN = "com.example.Calculator$AopProxy";

    /** A bean with a value-returning sync method and a {@code void}-returning sync method. */
    private static JavaFileObject calculatorBean() {
        return SourceFiles.inline(CALCULATOR_FQN, """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import jakarta.inject.Inject;
                public class Calculator {
                    @Inject
                    public Calculator() {}
                    @TestTimed
                    public int sum(int seed, int[] values) {
                        int total = seed;
                        for (int v : values) {
                            total += v;
                        }
                        return total;
                    }
                    @TestTimed
                    public void reset(boolean hard) {
                        // no-op
                    }
                }
                """);
    }

    @Test
    @DisplayName("sync override emits the FR-013-05 guard: !result.isComplete() → throw IllegalStateException")
    void syncOverrideEmitsIncompletenessGuard() {
        ProcessorTestHarness.run(new AopProcessor(), calculatorBean())
                .assertSuccess()
                .assertGeneratedSourceContains(PROXY_FQN, "!result.isComplete()")
                .assertGeneratedSourceContains(PROXY_FQN, "throw new IllegalStateException")
                // the failed-chain branch propagates the cause rather than swallowing it
                .assertGeneratedSourceContains(PROXY_FQN, "result.failed()")
                .assertGeneratedSourceContains(PROXY_FQN, "result.cause()");
    }
}
