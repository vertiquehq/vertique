// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * {@code compile-testing}-based harness for testing annotation processors.
 *
 * <p>This package is the root of {@code vertique-codegen-test}, a module that downstream
 * annotation-processor modules and custom-processor authors consume as a
 * {@code <scope>test</scope>} dependency.
 *
 * <p>The central entry point is {@link dev.vertique.codegen.test.ProcessorTestHarness}, which
 * wraps {@link com.google.testing.compile.Compiler} and exposes a fluent assertion API whose
 * assertion methods throw {@link org.opentest4j.AssertionFailedError} (not JUnit-specific types)
 * so the harness remains compatible with any test framework that integrates opentest4j.
 *
 * <p>Sub-packages:
 * <ul>
 *   <li>{@code dev.vertique.codegen.test.fixtures} — lightweight helpers for building
 *       {@link javax.tools.JavaFileObject} source inputs
 * </ul>
 */
package dev.vertique.codegen.test;
