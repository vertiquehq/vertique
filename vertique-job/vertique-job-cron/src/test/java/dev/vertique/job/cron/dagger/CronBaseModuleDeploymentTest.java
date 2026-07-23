// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron.dagger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.job.cron.CronJobRegistrar;
import dev.vertique.job.cron.CronScheduler;
import io.vertx.core.Verticle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link VerticleDeployment} provider on {@link CronBaseModule}.
 *
 * <p>Because both {@link CronModule} and {@link CronPersistenceModule} include
 * {@link CronBaseModule}, contributing the deployment here means including either parent
 * module is sufficient to wire cron startup. The tests verify the deployment shape (name,
 * phase, priority, instance count) and that the supplier produces a {@link CronLifecycleVerticle}.
 */
@DisplayName("CronBaseModule deployment contribution")
class CronBaseModuleDeploymentTest {

    @Test
    @DisplayName("contributes a VerticleDeployment with stable name, phase, priority, and instance count")
    void contributesDeploymentWithStableShape() {
        VerticleDeployment deployment = deployment();

        assertNotNull(deployment);
        assertEquals(CronLifecycleVerticle.DEPLOYMENT_NAME, deployment.name());
        assertSame(LifecyclePhase.SERVICES, deployment.phase());
        assertEquals(100, deployment.priority());
        assertEquals(1, deployment.options().getInstances());
    }

    @Test
    @DisplayName("supplier produces a CronLifecycleVerticle instance per call")
    void supplierProducesLifecycleVerticle() {
        VerticleDeployment deployment = deployment();

        Verticle first = deployment.supplier().get();
        Verticle second = deployment.supplier().get();
        assertInstanceOf(CronLifecycleVerticle.class, first);
        assertInstanceOf(CronLifecycleVerticle.class, second);
    }

    private static VerticleDeployment deployment() {
        return CronBaseModule.cronSchedulerDeployment(mock(CronJobRegistrar.class), mock(CronScheduler.class));
    }
}
