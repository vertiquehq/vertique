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
 * An annotation with a {@code String[]} member yields a compilable literal: {@code
 * AnnotationLiteralEmitter} renders the array attribute as a valid Java array initializer rather
 * than the {@code toString()} of the raw {@code List<? extends AnnotationValue>} (which would print
 * {@code ["k", "v"]}), so the generated {@code <Ann>$AopLiteral} constructor invocation compiles.
 *
 * <p>The real {@code @Timed} aspect's {@code extraTags()} member is a {@code String[]}, so this is
 * the production-relevant shape that a hand-written-proxy {@code @Timed} runtime test does not
 * exercise through the real emitter.
 *
 * <p>The test asserts the overall compilation succeeds and that the emitted constructor arguments
 * contain a valid {@code new String[]{...}} / {@code {...}} array initializer.
 */
class ArrayAttributeLiteralCompilesTest {

    // The String[] array initializer is rendered by AnnotationLiteralEmitter.constructorArgs
    // at the literal's *construction site* — i.e. inside the generated AopProxy, where the proxy holds a
    // `static final <Ann> ..._LITERAL = new TestTagged$AopLiteral(new String[]{...})` constant. The literal
    // class itself only stores `this.tags = tags`; the array initializer lives in the proxy. So the proof
    // (a valid array initializer that compiles) is read from the proxy source below.
    private static final String TAGGED_PROXY_FQN = "com.example.Tagged$AopProxy";

    private static final String DEFAULT_TAGGED_PROXY_FQN = "com.example.DefaultTagged$AopProxy";

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

    /** A bean with a {@code Future}-returning method carrying {@code @TestTagged(tags={"k","v"})}. */
    private static JavaFileObject taggedBean() {
        return SourceFiles.inline("com.example.Tagged", """
                package com.example;
                import dev.vertique.codegen.aop.TestTagged;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class Tagged {
                    @Inject
                    public Tagged() {}
                    @TestTagged(tags = {"k", "v"})
                    public Future<String> work() {
                        return Future.succeededFuture("done");
                    }
                }
                """);
    }

    /** A bean whose {@code @TestTagged} uses the default empty array ({@code tags()} omitted). */
    private static JavaFileObject defaultEmptyTaggedBean() {
        return SourceFiles.inline("com.example.DefaultTagged", """
                package com.example;
                import dev.vertique.codegen.aop.TestTagged;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class DefaultTagged {
                    @Inject
                    public DefaultTagged() {}
                    @TestTagged
                    public Future<String> work() {
                        return Future.succeededFuture("done");
                    }
                }
                """);
    }

    @Test
    @DisplayName(
            "a String[] annotation attribute yields a TestTagged$AopLiteral with a valid array initializer and compiles")
    void arrayAttributeLiteralCompiles() {
        var result = ProcessorTestHarness.run(new AopProcessor(), taggedBean()).assertSuccess();

        String proxy = generatedSource(result, TAGGED_PROXY_FQN);
        boolean hasArrayInitializer = proxy.contains("new String[]{") || proxy.contains("new String[] {");
        assertTrue(
                hasArrayInitializer,
                "The proxy must construct the TestTagged$AopLiteral tags() field via a valid String[] initializer "
                        + "(e.g. new String[]{\"k\", \"v\"}); generated source:\n" + proxy);
    }

    @Test
    @DisplayName("a default (omitted) String[] attribute yields an empty-array literal that compiles")
    void emptyArrayAttributeLiteralCompiles() {
        var result = ProcessorTestHarness.run(new AopProcessor(), defaultEmptyTaggedBean())
                .assertSuccess();

        String proxy = generatedSource(result, DEFAULT_TAGGED_PROXY_FQN);
        boolean hasEmptyArrayInitializer = proxy.contains("new String[]{}")
                || proxy.contains("new String[] {}")
                || proxy.contains("new String[0]");
        assertTrue(
                hasEmptyArrayInitializer,
                "The proxy must construct the empty TestTagged$AopLiteral tags() field via a valid empty String[] "
                        + "initializer; generated source:\n" + proxy);
    }
}
