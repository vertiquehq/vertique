// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Compile-time annotation processor for method-level AOP.
 *
 * <p>{@link dev.vertique.codegen.aop.AopProcessor} scans for beans with at least one method carrying
 * an {@link dev.vertique.aop.Aspect}-meta-annotated annotation (for example {@code @Timed}) and
 * generates a reflection-free {@code {Bean}$AopProxy extends Bean} subclass
 * proxy plus a {@code GeneratedAopModule} Dagger {@code @Binds} that substitutes the proxy for the
 * original bean. Each intercepted method is wrapped by an
 * {@link dev.vertique.aop.AspectProvider}-produced {@link dev.vertique.aop.MethodInterceptor}, with
 * the interceptor chain resolved once in the proxy constructor and the terminal dispatch calling
 * {@code super.method(...)} directly.
 */
package dev.vertique.codegen.aop;
