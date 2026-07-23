// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Lightweight helper utilities for constructing {@link javax.tools.JavaFileObject} source inputs
 * to be passed to {@link dev.vertique.codegen.test.ProcessorTestHarness}.
 *
 * <p>Contains {@link dev.vertique.codegen.test.fixtures.SourceFiles}, a thin wrapper around
 * {@link com.google.testing.compile.JavaFileObjects#forSourceString} that provides a concise
 * factory method.
 */
package dev.vertique.codegen.test.fixtures;
