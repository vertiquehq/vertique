// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import java.io.IOException;
import javax.tools.JavaFileObject;

/**
 * Test-local helpers for source-level assertions over generated proxies.
 *
 * <p>The generated proxy's nested {@code MethodMetadataImpl} implements the opt-in reflective
 * {@code asMethod()} accessor, whose body is the one place {@code Class.getDeclaredMethod} may appear
 * in generated source. Reflection-free assertions therefore run over
 * {@link #withoutAsMethodBodies(String)} so they cover the overrides, the chain construction, and
 * the constant-only accessors without flagging the accessor that is reflective by contract.
 */
final class GeneratedSources {

    private GeneratedSources() {}

    /** Counts the non-overlapping occurrences of {@code needle} in {@code source}. */
    static int count(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /** Reads the full generated source of {@code fqn} from the compilation, failing if absent. */
    static String of(ProcessorTestHarness.Result result, String fqn) {
        JavaFileObject file = result.compilation()
                .generatedSourceFile(fqn)
                .orElseThrow(() -> new AssertionError("No generated source for " + fqn));
        try {
            return file.getCharContent(true).toString();
        } catch (IOException e) {
            throw new AssertionError("Failed to read generated source for " + fqn, e);
        }
    }

    /**
     * Returns {@code source} with the body of every {@code asMethod()} method elided (brace-matched,
     * so each intercepted method's nested metadata implementation is handled), leaving every other
     * line intact for a reflection-free assertion.
     *
     * <p>Limits: the anchor is the emitted declaration {@code public Method asMethod()}, so a bean's
     * own public {@code Method asMethod()} override would be elided too; brace matching is literal and does not
     * skip braces inside string literals or comments (the emitted body contains none). A silent
     * no-match is caught by {@code AsMethodAccessorTest}, which asserts the elision marker count.
     */
    static String withoutAsMethodBodies(String source) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (true) {
            int at = source.indexOf("public Method asMethod() {", from);
            if (at < 0) {
                return out.append(source, from, source.length()).toString();
            }
            int open = source.indexOf('{', at);
            int depth = 0;
            int close = open;
            for (; close < source.length(); close++) {
                char c = source.charAt(close);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            out.append(source, from, open + 1).append(" /* asMethod body elided */ ");
            from = close;
        }
    }
}
