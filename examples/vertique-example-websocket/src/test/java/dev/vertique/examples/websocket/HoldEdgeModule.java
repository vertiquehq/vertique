// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.websocket;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.concurrent.CountDownLatch;

/**
 * Contributes two startup steps the test holds pending: one in the edge phase, which runs before the edge verticles
 * deploy, and one in the post-start phase, which runs after every verticle is deployed and just before the readiness
 * gate.
 */
@Module
abstract class HoldEdgeModule {

    /** A latch that tells the test a step is running and the promise that lets it finish. */
    static final class Hold {
        final CountDownLatch reached = new CountDownLatch(1);
        final Promise<Void> release = Promise.promise();
    }

    /** The edge-phase hold and the post-start hold of the current test run. */
    static final class Holds {
        static volatile Hold edge = new Hold();
        static volatile Hold afterStart = new Hold();

        private Holds() {}

        static void reset() {
            edge = new Hold();
            afterStart = new Hold();
        }
    }

    private static ApplicationStartupStep step(LifecyclePhase phase, int priority, boolean edge) {
        return new ApplicationStartupStep() {
            @Override
            public LifecyclePhase phase() {
                return phase;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public Future<Void> start() {
                Hold hold = edge ? Holds.edge : Holds.afterStart;
                hold.reached.countDown();
                return hold.release.future();
            }
        };
    }

    @Provides
    @IntoSet
    static ApplicationStartupStep holdEdge() {
        return step(LifecyclePhase.EDGE, 0, true);
    }

    @Provides
    @IntoSet
    static ApplicationStartupStep holdAfterStart() {
        return step(LifecyclePhase.AFTER_START, Integer.MAX_VALUE - 1, false);
    }
}
