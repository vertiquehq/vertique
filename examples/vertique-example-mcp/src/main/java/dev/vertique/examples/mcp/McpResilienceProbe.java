// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared example instrumentation used to make the generated-proxy integration proof decisive. */
public final class McpResilienceProbe {

    private static final McpResilienceProbe SHARED = new McpResilienceProbe();

    private final AtomicInteger timeoutInvocations = new AtomicInteger();
    private final AtomicInteger disconnectInvocations = new AtomicInteger();
    private final Promise<String> timeoutResult = Promise.promise();
    private final Promise<String> disconnectResult = Promise.promise();
    private final Promise<Void> cancellationObserved = Promise.promise();

    public static McpResilienceProbe shared() {
        return SHARED;
    }

    public void reset() {
        timeoutInvocations.set(0);
        disconnectInvocations.set(0);
    }

    public int timeoutInvocations() {
        return timeoutInvocations.get();
    }

    public int disconnectInvocations() {
        return disconnectInvocations.get();
    }

    public Future<Void> cancellationObserved() {
        return cancellationObserved.future();
    }

    public void recordTimeoutInvocation() {
        timeoutInvocations.incrementAndGet();
    }

    public void recordDisconnectInvocation() {
        disconnectInvocations.incrementAndGet();
    }

    public void observeCancellation() {
        cancellationObserved.tryComplete();
    }

    public Future<String> timeoutFuture() {
        return timeoutResult.future();
    }

    public Future<String> disconnectFuture() {
        return disconnectResult.future();
    }

    public void completeDisconnectLate() {
        disconnectResult.tryComplete("late-resilient-result");
    }
}
