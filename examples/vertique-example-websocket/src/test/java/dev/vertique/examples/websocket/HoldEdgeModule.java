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
 * Contributes a startup step in the edge phase, which runs before the edge verticles deploy, and keeps it pending
 * until the test releases it.
 */
@Module
abstract class HoldEdgeModule {

    /** The latch that tells the test the step is running and the promise that lets it finish. */
    static final class Hold {
        static volatile CountDownLatch reached = new CountDownLatch(1);
        static volatile Promise<Void> release = Promise.promise();

        private Hold() {}

        static void reset() {
            reached = new CountDownLatch(1);
            release = Promise.promise();
        }
    }

    @Provides
    @IntoSet
    static ApplicationStartupStep holdEdge() {
        return new ApplicationStartupStep() {
            @Override
            public LifecyclePhase phase() {
                return LifecyclePhase.EDGE;
            }

            @Override
            public Future<Void> start() {
                Hold.reached.countDown();
                return Hold.release.future();
            }
        };
    }
}
