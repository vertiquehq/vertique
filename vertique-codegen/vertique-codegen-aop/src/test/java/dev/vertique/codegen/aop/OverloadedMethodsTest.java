// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.testing.compile.Compilation;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two overloaded intercepted methods yield a compilable proxy. {@code AopProxyEmitter} derives
 * per-method member names (the nested metadata type {@code <name>_MethodMetadata}, the metadata
 * constant {@code <NAME>_META}, and the chain field {@code <name>$chain}) so that overloads with the
 * same simple name but different parameters do not collide into duplicate nested types and fields.
 *
 * <p>This shape uses {@code @TestTimed} (a non-array aspect), so the failure mode is purely the
 * overload name collision.
 *
 * <p>The test asserts no duplicate-member ("already defined") diagnostic is present and that the
 * compilation succeeds.
 */
class OverloadedMethodsTest {

    /** A bean with two overloaded {@code Future}-returning {@code @TestTimed} methods. */
    private static JavaFileObject overloadedBean() {
        return SourceFiles.inline("com.example.Overloaded", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class Overloaded {
                    @Inject
                    public Overloaded() {}
                    @TestTimed
                    public Future<String> process(String name) {
                        return Future.succeededFuture(name);
                    }
                    @TestTimed
                    public Future<String> process(int id) {
                        return Future.succeededFuture(Integer.toString(id));
                    }
                }
                """);
    }

    @Test
    @DisplayName("two overloaded @TestTimed methods generate distinct metadata-type / field / chain names and compile")
    void overloadedMethodsDoNotCollide() {
        var result = ProcessorTestHarness.run(new AopProcessor(), overloadedBean());

        Compilation compilation = result.compilation();
        boolean duplicateMember = compilation.diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null))
                .anyMatch(
                        msg -> msg != null && (msg.contains("already defined") || msg.contains("is already defined")));
        assertFalse(
                duplicateMember,
                "overloaded intercepted methods must get distinct metadata-type, metadata-field, and chain-field "
                        + "names — deriving all three from the simple name only would make the two process(..) overloads "
                        + "collide");

        // The proxy (with both overloads) must compile cleanly.
        result.assertSuccess();
    }
}
