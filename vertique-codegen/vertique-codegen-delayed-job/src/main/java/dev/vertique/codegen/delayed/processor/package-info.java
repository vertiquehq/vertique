// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Annotation processor for generating static delayed-job client proxies from
 * {@code @DelayedJobContract}-annotated interfaces.
 *
 * <p>The central entry point is
 * {@link dev.vertique.codegen.delayed.processor.DelayedJobContractProcessor}. Supporting classes are
 * organized into sub-packages:
 * <ul>
 *   <li>{@code scan} — APT-side scanner that resolves the contract payload type and metadata</li>
 *   <li>{@code validate} — contract-shape, duplicate-name, and executor-alignment validators</li>
 *   <li>{@code emit} — source emitter for {@code {Contract}_DelayedJobProxy}</li>
 * </ul>
 */
package dev.vertique.codegen.delayed.processor;
