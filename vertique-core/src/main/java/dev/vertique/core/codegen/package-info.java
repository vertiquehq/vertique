// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Neutral, runtime metadata SPI that codegen-generated code implements and consumes.
 *
 * <p>This package owns a <em>provider-neutral</em> runtime home for method and parameter metadata.
 * Generated code (e.g. AOP proxies, event publishers) emits implementations of these interfaces and
 * other generated/runtime code consumes them, without either side depending on a specific
 * cross-cutting concern. The package references no AOP or event type — that neutrality is
 * load-bearing and lets multiple concerns share one metadata contract.
 *
 * <p>Each interface splits its surface into two groups:
 * <ul>
 *   <li>The <strong>constant-only core accessors</strong> are the <em>reflection-free guarantee</em>:
 *       a generated implementation returns compile-time-captured constants and never reflects at
 *       call time. Annotation lookups are backed by generated annotation-literals rather than
 *       {@code Method#getAnnotation}.
 *   <li>The <strong>reflective-accessor group</strong> —
 *       {@link dev.vertique.core.codegen.MethodMetadata#asMethod()},
 *       {@link dev.vertique.core.codegen.MethodMetadata#genericReturnType()}, and
 *       {@link dev.vertique.core.codegen.ParameterMetadata#genericType()} — is an <em>opt-in</em>
 *       group that is <strong>not</strong> part of the reflection-free guarantee and is never called
 *       by generated proxy code. A consumer that explicitly needs reflective access uses it,
 *       accepting the reflection it entails.
 * </ul>
 *
 * <h2>Key types</h2>
 * <ul>
 *   <li>{@link dev.vertique.core.codegen.MethodMetadata} — runtime metadata view of a method.
 *   <li>{@link dev.vertique.core.codegen.ParameterMetadata} — runtime metadata view of a method
 *       parameter.
 * </ul>
 */
package dev.vertique.core.codegen;
