// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static dev.vertique.http.client.HttpClientItSupport.awaitCondition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The event-loop guard of this module's integration tests must still fail on code that blocks the loop,
 * whether it burns CPU or waits; only a turn the host stalled is let through.
 */
class EventLoopGuardIT {

    private static final long BLOCK_MS = 600;
    private static final long CPU_MS = 600;

    private static HttpClientItSupport support;

    @BeforeAll
    static void startVertx() {
        support = HttpClientItSupport.start();
    }

    @AfterAll
    static void stopVertx() {
        support.stop();
    }

    @BeforeEach
    void forgetEarlierReports() {
        support.discardBlockedThreadReports();
    }

    /** Waits for the checker's report of a turn that has ended, then runs the guard's check. */
    private static void assertGuardFails(String reason) {
        awaitCondition(support::hasBlockedThreadReport, HttpClientItSupport.WAIT_MS);
        assertThatThrownBy(support::assertNoBlockedThreadWarning)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(reason);
    }

    private static void onEventLoop(Runnable work) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        support.vertx.runOnContext(v -> {
            try {
                work.run();
                done.complete(null);
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
        done.get(HttpClientItSupport.WAIT_MS, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("a turn that spins on the CPU for longer than the limit fails the guard")
    void spinningTurnFailsTheGuard() throws Exception {
        // Given a turn that does 600 ms of CPU-bound work on the event loop, however long the host takes to run it
        onEventLoop(() -> {
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            long end = threads.getCurrentThreadCpuTime() + TimeUnit.MILLISECONDS.toNanos(CPU_MS);
            long sink = 0;
            while (threads.getCurrentThreadCpuTime() < end) {
                sink += System.nanoTime() & 1;
            }
            assertThat(sink).isNotNegative();
        });

        // Then the guard reports it as blocking
        assertGuardFails("cpu ");
    }

    @Test
    @DisplayName("a turn that sleeps for longer than the limit fails the guard although it uses no CPU")
    void sleepingTurnFailsTheGuard() throws Exception {
        // Given a turn that parks for 600 ms on the event loop
        onEventLoop(() -> LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(BLOCK_MS)));

        // Then the guard reports it as blocking
        assertGuardFails("TIMED_WAITING");
    }

    @Test
    @DisplayName("a turn that blocks in a native socket call fails the guard although it uses no CPU")
    void nativeBlockingTurnFailsTheGuard() throws Exception {
        // Given a turn that waits 600 ms in a blocking accept
        onEventLoop(() -> {
            try (ServerSocket socket = new ServerSocket(0)) {
                socket.setSoTimeout((int) BLOCK_MS);
                socket.accept();
            } catch (java.net.SocketTimeoutException expected) {
                // the accept timed out, as intended
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        // Then the guard reports it as blocking
        assertGuardFails("blocking native call");
    }

    @Test
    @DisplayName("a turn that blocks on a monitor held by another thread fails the guard")
    void monitorWaitingTurnFailsTheGuard() throws Exception {
        // Given another thread holding a monitor for 600 ms and a turn that needs it
        Object monitor = new Object();
        AtomicBoolean turnWaiting = new AtomicBoolean();
        CompletableFuture<Void> held = new CompletableFuture<>();
        Thread holder = new Thread(() -> {
            synchronized (monitor) {
                held.complete(null);
                while (!turnWaiting.get()) {
                    Thread.onSpinWait();
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(BLOCK_MS));
            }
        });
        holder.setDaemon(true);
        holder.start();
        held.get(HttpClientItSupport.WAIT_MS, TimeUnit.MILLISECONDS);
        onEventLoop(() -> {
            turnWaiting.set(true);
            synchronized (monitor) {
                assertThat(Thread.holdsLock(monitor)).isTrue();
            }
        });

        // Then the guard reports it as blocking
        assertGuardFails("BLOCKED");
    }
}
