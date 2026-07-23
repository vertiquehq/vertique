// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opt-out marker for the Vertique auto-wiring annotation processor ({@code AutoWireProcessor}).
 *
 * <p>When placed on a type that would otherwise be discovered and auto-wired by
 * {@code AutoWireProcessor} (e.g., a {@code @Path} resource, a {@code @RestClient} interface,
 * a {@code @ServiceContract} implementation, etc.), the processor skips that type entirely.
 * Any existing manual {@code @Provides} binding for that type remains authoritative.
 *
 * <p>This annotation has {@link RetentionPolicy#SOURCE} retention — it is consumed only during
 * compilation and never reaches the runtime classpath or the compiled {@code .class} file.
 *
 * <p>It lives in {@code vertique-codegen-core} (not in {@code vertique-codegen-dagger}) so that
 * user code can import and use it as a compile-scope dependency without adding the processor
 * itself to the normal compile classpath. Declare it {@code provided} or {@code compileOnly}:
 *
 * <pre>{@code
 * <dependency>
 *     <groupId>dev.vertique</groupId>
 *     <artifactId>vertique-codegen-core</artifactId>
 *     <scope>provided</scope>
 * </dependency>
 * }</pre>
 *
 * <p>Migration contract: for any marker-annotated type, exactly one of the following must hold:
 * <ol>
 *   <li>Remove the manual {@code @Provides} binding — the auto-wired binding takes over.</li>
 *   <li>Add {@code @NoAutoWire} — the existing manual binding stays canonical and no generated
 *       binding is emitted for this type.</li>
 * </ol>
 * Two bindings for the same implementation type (one manual, one generated) will cause a Dagger
 * duplicate-binding error at compile time.
 *
 * @see <a href="https://github.com/vertiquehq/vertique/blob/main/vertique-codegen/vertique-codegen-dagger/src/main/resources/META-INF/vertique/module.md">
 *      Auto-Wiring Guide</a>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface NoAutoWire {}
