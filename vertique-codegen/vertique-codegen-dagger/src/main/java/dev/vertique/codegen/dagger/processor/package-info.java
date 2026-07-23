// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Annotation processor that auto-generates Dagger {@code @Module} bindings from framework marker
 * annotations and interface implementations.
 *
 * <p>The central entry point is {@link dev.vertique.codegen.dagger.processor.AutoWireProcessor},
 * registered via {@code META-INF/services/javax.annotation.processing.Processor}. It delegates
 * discovery to per-qualifier collectors and scanners, then emits one generated module per
 * qualifier per compilation round via the emitter classes.
 *
 * <p>Helper sub-packages:
 * <ul>
 *   <li>{@code collect} — annotation-rooted collectors and root-element scanners</li>
 *   <li>{@code emit} — module emitters ({@code MultibindingModuleEmitter},
 *       {@code RestClientModuleEmitter})</li>
 * </ul>
 */
package dev.vertique.codegen.dagger.processor;
