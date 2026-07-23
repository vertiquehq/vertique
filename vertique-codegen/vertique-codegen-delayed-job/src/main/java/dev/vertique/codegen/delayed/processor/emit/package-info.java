// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Source emitter for {@code {Contract}_DelayedJobProxy} — a static, zero-reflection implementation of a
 * {@code @DelayedJobContract} interface that delegates each {@code enqueue} overload to
 * {@code DelayedJobService}, reproducing the behavior of the reflective {@code DelayedJobClientProxy}.
 */
package dev.vertique.codegen.delayed.processor.emit;
