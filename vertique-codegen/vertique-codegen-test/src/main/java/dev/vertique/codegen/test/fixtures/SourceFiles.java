// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.test.fixtures;

import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;

/**
 * Lightweight factory for creating {@link JavaFileObject} source inputs for use with
 * {@link dev.vertique.codegen.test.ProcessorTestHarness}.
 *
 * <p>Wraps {@link JavaFileObjects#forSourceString} with a concise factory method so test code
 * does not need to import the underlying {@code compile-testing} class directly.
 *
 * <p>Usage:
 * <pre>{@code
 * JavaFileObject source = SourceFiles.inline("com.example.Foo", """
 *         package com.example;
 *         public class Foo {}
 *         """);
 * }</pre>
 */
public final class SourceFiles {

    private SourceFiles() {}

    /**
     * Creates an in-memory {@link JavaFileObject} representing a Java source file with the given
     * fully-qualified class name and source body.
     *
     * @param fqn  the fully-qualified class name (e.g., {@code "com.example.Foo"}); must not be
     *             {@code null}
     * @param body the Java source code body; must not be {@code null}. The body must be a
     *             syntactically valid Java compilation unit (including the {@code package}
     *             declaration and class declaration).
     * @return a {@link JavaFileObject} suitable for passing to
     *         {@link dev.vertique.codegen.test.ProcessorTestHarness#run}
     */
    public static JavaFileObject inline(String fqn, String body) {
        return JavaFileObjects.forSourceString(fqn, body);
    }
}
