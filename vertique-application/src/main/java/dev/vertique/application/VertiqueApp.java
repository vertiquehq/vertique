// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.application;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an application's Dagger {@code @Component} interface so the framework generates its
 * {@link dev.vertique.core.VertiqueComponentFactory} implementation and the matching
 * {@code META-INF/services} registration at compile time.
 *
 * <p>Place this annotation <strong>on the Dagger {@code @Component} interface</strong> that
 * <ul>
 *   <li>is annotated with {@code dagger.Component},</li>
 *   <li>extends {@link VertiqueApplicationComponent} (so the generated factory's return type and the
 *       lifecycle runner's contract hold), and</li>
 *   <li>includes {@link dev.vertique.core.VertxModule} in its {@code @Component(modules = …)} list
 *       (the generated factory calls {@code .vertxModule(…)} on Dagger's builder; a component that
 *       omits {@code VertxModule} yields a clear generated-code compile error).</li>
 * </ul>
 *
 * <p>The {@code vertique-codegen-application} annotation processor generates, in the component's own
 * package, a {@code final class <ComponentSimpleName>VertiqueComponentFactory implements
 * VertiqueComponentFactory<Component>} whose {@code build(VertiqueRuntime)} body is exactly
 * {@code return Dagger<ComponentSimpleName>.builder().vertxModule(new VertxModule(runtime.vertx(),
 * runtime.config())).build();}, plus a {@code META-INF/services/dev.vertique.core
 * .VertiqueComponentFactory} resource naming that generated class. A standalone application
 * therefore writes neither the factory nor the service file.
 *
 * <p>Exactly one type per compilation may carry {@code @VertiqueApp}; the processor reports a
 * compile error when it finds more than one.
 *
 * <p>The annotation is {@link RetentionPolicy#SOURCE source-retained} — it is consumed entirely at
 * compile time and is absent from the runtime classpath.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface VertiqueApp {}
