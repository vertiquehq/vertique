// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * APT helper library for annotation processor development in the Vertique framework.
 *
 * <p>This package is the root of {@code vertique-codegen-core}, a compile-time-only helper library
 * that downstream annotation-processor modules (CG-002+) depend on for shared APT bootstrapping,
 * type resolution, annotation mirror utilities, diagnostic formatting, and Dagger module code
 * generation via Palantir JavaPoet.
 *
 * <p>This library registers no {@link javax.annotation.processing.Processor} and has no runtime
 * footprint. It is never placed on a runtime classpath.
 *
 * <p>Sub-packages:
 * <ul>
 *   <li>{@code dev.vertique.codegen} — source-retained registration annotations such as
 *       {@link dev.vertique.codegen.RegisterAs} and {@link dev.vertique.codegen.RegisterIntoSet}
 *   <li>{@code dev.vertique.codegen.dagger} — {@link dev.vertique.codegen.dagger.DaggerModuleWriter}
 *       for emitting Dagger {@code @Module} classes via JavaPoet
 *   <li>{@code dev.vertique.codegen.support} — identifier utilities
 * </ul>
 */
package dev.vertique.codegen;
