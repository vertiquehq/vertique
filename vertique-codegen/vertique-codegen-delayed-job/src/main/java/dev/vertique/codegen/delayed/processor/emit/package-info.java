// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Source emitters for the delayed-job contract processor:
 * <ul>
 *   <li>{@code {Contract}_DelayedJobProxy} — a static, zero-reflection implementation of a
 *       {@code @DelayedJobContract} interface that delegates each {@code enqueue} overload to
 *       {@code DelayedJobService}, reproducing the behavior of the reflective
 *       {@code DelayedJobClientProxy}.</li>
 *   <li>{@code GeneratedDelayedJobClientsModule} — a Dagger module binding each contract via
 *       {@code DelayedJobClientFactory.create(…)}, so applications do not hand-write a provider per
 *       contract.</li>
 * </ul>
 */
package dev.vertique.codegen.delayed.processor.emit;
