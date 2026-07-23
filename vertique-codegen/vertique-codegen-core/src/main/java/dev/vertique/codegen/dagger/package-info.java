// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * JavaPoet-based utilities for generating Dagger {@code @Module} source files.
 *
 * <p>Contains {@link dev.vertique.codegen.dagger.DaggerModuleWriter}, a builder-style wrapper
 * around Palantir JavaPoet that emits the most common Dagger patterns ({@code @Provides},
 * {@code @IntoSet}, {@code @Singleton}, {@code @BindsOptionalOf}) so downstream annotation
 * processors produce consistent module output without repeating the JavaPoet boilerplate.
 */
package dev.vertique.codegen.dagger;
