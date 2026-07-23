// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Compile-time validators for {@code @DelayedJobContract} interfaces: contract shape (must extend
 * {@code DelayedJobClient<P>} with a resolvable {@code P}), duplicate contract names within the
 * compilation unit, and same-unit {@code DelayedJobExecutor} payload alignment.
 */
package dev.vertique.codegen.delayed.processor.validate;
