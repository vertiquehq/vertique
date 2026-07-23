// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Annotation processor that generates an application's {@link dev.vertique.core.VertiqueComponentFactory}
 * implementation from a {@code @VertiqueApp}-annotated Dagger {@code @Component}.
 *
 * <p>The entry point is {@link dev.vertique.codegen.application.VertiqueAppProcessor}, registered via
 * {@code META-INF/services/javax.annotation.processing.Processor}. For the single
 * {@code dev.vertique.application.VertiqueApp}-annotated component interface, it emits — through
 * {@link dev.vertique.codegen.application.ComponentFactoryEmitter} (JavaPoet) — a {@code final class
 * <ComponentSimpleName>VertiqueComponentFactory} in the component's package whose {@code build(…)}
 * delegates to the Dagger-generated {@code Dagger<ComponentSimpleName>} builder, plus a
 * {@code META-INF/services/dev.vertique.core.VertiqueComponentFactory} resource naming the generated
 * class. The result is the same factory + service file an application would otherwise hand-write.
 *
 * <p>The processor returns {@code false} from {@code process()} so that the Dagger annotation
 * processor continues to see the same elements and generates {@code Dagger<ComponentSimpleName>} in
 * the same compilation — the generated factory's {@code Dagger<…>}-by-name reference resolves at the
 * final javac compile with no reflection.
 */
package dev.vertique.codegen.application;
