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
 * RED reproduction for Bug F2 (first half): {@code AopProxyEmitter.collectAspects} keys aspects by
 * annotation FQN with {@code computeIfAbsent}, so it keeps a SINGLE {@code AspectRef} per aspect
 * <em>type</em> holding the FIRST mirror encountered. It then emits one {@code static final}
 * literal constant per aspect type (built from that representative mirror) and references it from
 * every intercepted method's chain.
 *
 * <p>Consequently, when two methods on the same bean carry {@code @TestTagged} with DIFFERENT
 * attribute values ({@code {"a"}} vs {@code {"b"}}), both methods' chains share the same literal
 * built from the first mirror's {@code {"a"}} — method-2's {@code {"b"}} is silently lost.
 *
 * <p>The assertion reads the generated proxy source directly (not gated on {@code assertSuccess()},
 * since the F1 array-literal bug also makes this compilation fail) and requires BOTH {@code "a"}
 * and {@code "b"} to appear as distinct literal constructor arguments — proving each occurrence
 * materializes its own literal. Currently RED: only {@code "a"} appears.
 */
class PerOccurrenceLiteralTest {

    private static final String PROXY_FQN = "com.example.TwoTagged$AopProxy";

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

    /** A single bean with two {@code Future}-returning methods, tagged {@code {"a"}} and {@code {"b"}}. */
    private static JavaFileObject twoTaggedBean() {
        return SourceFiles.inline("com.example.TwoTagged", """
                package com.example;
                import dev.vertique.codegen.aop.TestTagged;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class TwoTagged {
                    @Inject
                    public TwoTagged() {}
                    @TestTagged(tags = {"a"})
                    public Future<String> first() {
                        return Future.succeededFuture("1");
                    }
                    @TestTagged(tags = {"b"})
                    public Future<String> second() {
                        return Future.succeededFuture("2");
                    }
                }
                """);
    }

    @Test
    @DisplayName("two @TestTagged occurrences with distinct values on one bean materialize distinct literals")
    void distinctOccurrencesMaterializeDistinctLiterals() {
        var result = ProcessorTestHarness.run(new AopProcessor(), twoTaggedBean());

        String proxy = generatedSource(result, PROXY_FQN);
        assertTrue(
                proxy.contains("\"a\""),
                "proxy must reference the first occurrence's {\"a\"} literal; generated source:\n" + proxy);
        assertTrue(
                proxy.contains("\"b\""),
                "proxy must reference the second occurrence's {\"b\"} literal as a distinct literal — "
                        + "F2 collapses both to the first mirror's {\"a\"}; generated source:\n" + proxy);
    }
}
