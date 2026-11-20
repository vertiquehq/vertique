// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Timer scheduler that runs real Vert.x timers and records which timers were started and cancelled. */
final class RecordingTimerScheduler implements Resilience.TimerScheduler {

    private final Vertx vertx;
    private final List<Long> started = new CopyOnWriteArrayList<>();
    private final List<Long> cancelled = new CopyOnWriteArrayList<>();

    RecordingTimerScheduler(Vertx vertx) {
        this.vertx = vertx;
    }

    @Override
    public long setTimer(long delayMs, Handler<Long> handler) {
        long id = vertx.setTimer(delayMs, handler);
        started.add(id);
        return id;
    }

    @Override
    public boolean cancelTimer(long timerId) {
        cancelled.add(timerId);
        return vertx.cancelTimer(timerId);
    }

    List<Long> started() {
        return started;
    }

    List<Long> cancelled() {
        return cancelled;
    }
}
