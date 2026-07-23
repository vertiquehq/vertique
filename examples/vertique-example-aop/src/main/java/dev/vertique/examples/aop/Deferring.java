// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.aop;

import dev.vertique.aop.Aspect;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A deliberately-misbehaving custom aspect trigger used to prove the FR-013-05 sync-defer guard.
 *
 * <p>Unlike the framework's {@code @Timed} built-in, which observes the outcome and passes it through
 * unchanged, {@link DeferringAspect} returns a {@link io.vertx.core.Future} that never settles
 * synchronously. Placing this annotation on a <em>synchronous-returning</em> method (one whose declared
 * return type is not a {@code Future}) therefore makes the generated proxy's around-chain fail to
 * settle by the time the override must produce a plain value — the exact condition the generated
 * sync guard (emitted by {@code AopProxyEmitter.emitSyncGuard}) turns into a loud
 * {@link IllegalStateException} rather than a block, a wrong value, or an NPE.
 *
 * <p>This annotation exists purely as a runtime end-to-end proof of that guard; it is meta-annotated
 * with {@link Aspect @Aspect} so the {@code vertique-codegen-aop} processor discovers it as a trigger
 * exactly as it discovers the built-ins.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Aspect
public @interface Deferring {}
