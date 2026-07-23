// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

import io.vertx.core.Future;

/**
 * A non-verticle unit of application startup work, run in lifecycle-phase order during application
 * start.
 *
 * <p>Startup steps are Dagger {@code @IntoSet}-contributed and ordered with {@link
 * LifecycleOrdered#comparator()} (phase &rarr; priority &rarr; orderKey). A step typically belongs to
 * one of the non-verticle phases ({@link LifecyclePhase#CONFIGURE}, {@link LifecyclePhase#VALIDATE},
 * {@link LifecyclePhase#MIGRATE}, {@link LifecyclePhase#AFTER_START}); verticle deployment is handled
 * separately for the {@link LifecyclePhase#isVerticlePhase() verticle-subset} phases.
 *
 * <p>This interface is intentionally <em>not</em> a {@code @FunctionalInterface}: it inherits the
 * abstract {@link LifecycleOrdered#phase()} in addition to declaring {@link #start()}, so it carries
 * two abstract methods and cannot be expressed as a single lambda.
 */
public interface ApplicationStartupStep extends LifecycleOrdered {

    /**
     * Performs this startup step.
     *
     * @return a future completing when the step has finished; a failed future aborts startup
     */
    Future<Void> start();
}
