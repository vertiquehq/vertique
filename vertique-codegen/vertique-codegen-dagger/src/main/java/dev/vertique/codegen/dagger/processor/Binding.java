// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor;

import com.palantir.javapoet.ClassName;
import javax.lang.model.element.TypeElement;

/**
 * Immutable record capturing a single auto-wired binding discovered by a collector or scanner.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@link #origin} — the source {@link TypeElement} from which this binding was discovered.
 *       For annotation-rooted discovery ({@code @Path}, {@code @RestClient},
 *       {@code @KafkaListener}/ {@code @KafkaSource}), this is the annotated type itself. For
 *       root-element scanners ({@code @ServiceContract} impl, {@code DelayedJobExecutor} impl),
 *       this is the concrete implementing class.</li>
 *   <li>{@link #implType} — the {@link ClassName} of the implementation type to wire. For most
 *       bindings this equals the origin's class name. For {@code @RestClient}, this is the
 *       interface name (the proxy is created at runtime by {@code RestClientFactory}).</li>
 * </ul>
 *
 * @param origin   the source {@link TypeElement}; used for package resolution and diagnostics
 * @param implType the {@link ClassName} of the type to be provided or contributed
 */
public record Binding(TypeElement origin, ClassName implType) {}
