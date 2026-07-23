// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.aop;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runtime proof for PRD-CODEGEN-013 slice 2.3 / FR-013-05 — a deliberately-deferring custom aspect on
 * a sync-returning method throws {@link IllegalStateException} through the real generated proxy.
 *
 * <p>{@link DeferringBean#compute(String)} returns a plain {@link String} (sync) but carries the
 * custom {@link Deferring @Deferring} aspect, whose {@link DeferringAspect} interceptor calls
 * {@code proceed()} but returns a never-completing future (it does not await {@code proceed()}). The
 * generated {@code DeferringBean$AopProxy} therefore finds the around-chain incomplete when it must
 * produce a plain value, and the FR-013-05 guard throws {@link IllegalStateException} naming the
 * sync-returning method — proving a sync method cannot carry a deferring aspect.
 *
 * <p>This validates already-shipped guard code ({@code AopProxyEmitter.emitSyncGuard}); the test is
 * expected to pass.
 */
class CustomDeferringAspectOnSyncMethodThrowsTest {

    /** Builds the component over a fresh, caller-owned registry. */
    private static GreeterComponent component() {
        return DaggerGreeterComponent.builder()
                .aopRegistryModule(new AopRegistryModule(new SimpleMeterRegistry()))
                .build();
    }

    @Test
    @DisplayName("a deferring aspect on a sync method throws IllegalStateException naming the guard cause")
    void deliberatelyDeferringAspectThrowsIllegalStateException() {
        DeferringBean bean = component().deferringBean();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> bean.compute("x"),
                "a deferring aspect on a"
                        + " sync-returning method must throw IllegalStateException, not block or return a wrong value");

        String message = ex.getMessage();
        assertNotNull(message, "the guard's IllegalStateException must carry a message");
        // The guard message identifies the cause as a deferring aspect on a sync-returning method,
        // and names the offending method. (AopProxyEmitter.emitSyncGuard.)
        assertTrue(
                message.contains("Aspect deferred completion on sync-returning method"),
                "message must identify the cause as a deferring aspect on a sync-returning method, was: " + message);
        assertTrue(
                message.contains("dev.vertique.examples.aop.DeferringBean.compute"),
                "message must name the offending sync-returning method, was: " + message);
        assertTrue(
                message.contains("requires a synchronous aspect"),
                "message must explain a sync-returning method requires a synchronous aspect, was: " + message);
    }
}
