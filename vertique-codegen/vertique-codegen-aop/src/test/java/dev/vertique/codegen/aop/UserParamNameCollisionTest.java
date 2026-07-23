// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED reproduction for Codex R3-4: generated locals collide with user parameter names in
 * {@link AopProxyEmitter#buildOverride}.
 *
 * <p>The override emits literal local names {@code Object[] args}, {@code Future<Object> result}, and
 * {@code Throwable cause} (the last via the sync guard), then reads each user parameter back as
 * {@code (ParamType) args[i]}. When a user parameter is itself named {@code args} or {@code result},
 * the generated declaration clashes with the user parameter in the same scope:
 * <ul>
 *   <li>a parameter named {@code args} yields {@code Object[] args = {args}} (duplicate variable
 *       {@code args}) and {@code (String) args[0]} now references the {@code Object[]}, not the user
 *       {@code String};
 *   <li>a parameter named {@code result} clashes with the {@code Future<Object> result} local.
 * </ul>
 *
 * <p>The generated proxy must NOT collide, so this test asserts compilation SUCCEEDS.
 *
 * <p><strong>Red now:</strong> the emitted {@code Object[] args = {args}} / {@code Future<Object>
 * result} declarations shadow or duplicate the user parameters, so the generated proxy fails to
 * compile (duplicate-variable / incompatible-type errors) and {@code assertSuccess()} fails.
 */
class UserParamNameCollisionTest {

    /**
     * A bean with two {@code Future}-returning {@code @TestTimed} methods whose parameters are named
     * {@code args} and {@code result} respectively — the exact names the override emits as locals.
     */
    private static JavaFileObject collidingBean() {
        return SourceFiles.inline("com.example.CollidingBean", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class CollidingBean {
                    @Inject
                    public CollidingBean() {}
                    @TestTimed
                    public Future<String> first(String args) {
                        return Future.succeededFuture(args);
                    }
                    @TestTimed
                    public Future<String> second(String result) {
                        return Future.succeededFuture(result);
                    }
                }
                """);
    }

    @Test
    @DisplayName("a @TestTimed method whose parameter is named like a generated local compiles without collision")
    void userParamNamedLikeGeneratedLocalCompiles() {
        ProcessorTestHarness.run(new AopProcessor(), collidingBean()).assertSuccess();
    }
}
