// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * AOP integration for the resilience anchor.
 *
 * <p>{@link dev.vertique.resilience.aop.ResilienceAopModule} binds the application-scoped
 * {@code @Resilient} provider and closes its adapter-owned state during the {@code VALIDATE}
 * shutdown phase. The aspect implementation is framework-internal; application code uses the
 * {@link dev.vertique.resilience.annotation.Resilient} annotation.
 */
package dev.vertique.resilience.aop;
