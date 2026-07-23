// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.io.IOException;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the generated proxy builds its interceptor chain ordered outermost-by-higher
 * {@code @Aspect.ordering()}, with ties broken deterministically by annotation fully-qualified name
 * (PRD A.1 / FR-013-02 / §4.1).
 *
 * <p>Both methods read the chain-construction expression from the generated proxy source and assert
 * the relative position of each aspect's annotation simple name. A higher-ordered aspect (outermost)
 * appears earlier in the {@code MethodInterceptor[]} array; equal-ordered aspects appear in
 * annotation-FQN order.
 *
 * <p><strong>Red:</strong> the no-op scaffold processor emits no proxy, so no chain expression exists
 * and both assertions fail.
 */
class AroundChainOrderingTest {

    /** Reads the full generated source of {@code fqn} from the compilation, failing if absent. */
    private static String generatedSource(ProcessorTestHarness.Result result, String fqn) {
        JavaFileObject file = result.compilation()
                .generatedSourceFile(fqn)
                .orElseThrow(() -> new AssertionError("No generated source for " + fqn));
        try {
            return file.getCharContent(true).toString();
        } catch (IOException e) {
            throw new AssertionError("Failed to read generated source for " + fqn, e);
        }
    }

    @Test
    @DisplayName(
            "two aspects with different ordering: the higher-ordered (TestLogged) is outermost — earlier in the chain")
    void higherOrderingIsOutermost() {
        JavaFileObject bean = SourceFiles.inline("com.example.OrderedBean", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import dev.vertique.codegen.aop.TestLogged;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class OrderedBean {
                    @Inject
                    public OrderedBean() {}
                    @TestLogged
                    @TestTimed
                    public Future<String> work() {
                        return Future.succeededFuture("done");
                    }
                }
                """);

        var result = ProcessorTestHarness.run(new AopProcessor(), bean).assertSuccess();
        String source = generatedSource(result, "com.example.OrderedBean$AopProxy");

        int loggedAt = source.indexOf("TestLogged");
        int timedAt = source.indexOf("TestTimed");
        assertTrue(loggedAt >= 0 && timedAt >= 0, "both aspect names must appear in the generated chain");
        assertTrue(
                loggedAt < timedAt,
                "TestLogged (ordering=2000) must be outermost — appear before TestTimed (ordering=1000) in the chain");
    }

    @Test
    @DisplayName("two equal-ordering aspects: the tie is broken by annotation FQN — TestAlpha before TestBeta")
    void tiesAreBrokenByAnnotationFqn() {
        JavaFileObject bean = SourceFiles.inline("com.example.TiedBean", """
                package com.example;
                import dev.vertique.codegen.aop.TestAlpha;
                import dev.vertique.codegen.aop.TestBeta;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class TiedBean {
                    @Inject
                    public TiedBean() {}
                    @TestBeta
                    @TestAlpha
                    public Future<String> work() {
                        return Future.succeededFuture("done");
                    }
                }
                """);

        var result = ProcessorTestHarness.run(new AopProcessor(), bean).assertSuccess();
        String source = generatedSource(result, "com.example.TiedBean$AopProxy");

        int alphaAt = source.indexOf("TestAlpha");
        int betaAt = source.indexOf("TestBeta");
        assertTrue(alphaAt >= 0 && betaAt >= 0, "both aspect names must appear in the generated chain");
        assertTrue(
                alphaAt < betaAt,
                "equal-ordering tie must break by FQN — TestAlpha must appear before TestBeta in the chain");
    }
}
