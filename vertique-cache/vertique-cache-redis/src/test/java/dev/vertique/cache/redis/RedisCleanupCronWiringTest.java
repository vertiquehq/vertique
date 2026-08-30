// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static dev.vertique.cache.redis.RedisCleanupTestFixtures.CADENCE_MILLIS;
import static dev.vertique.cache.redis.RedisCleanupTestFixtures.JOB_ID;
import static dev.vertique.cache.redis.RedisCleanupTestFixtures.MAX_JITTER_MILLIS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import dev.vertique.job.cron.CronJobDefinition;
import dev.vertique.job.cron.CronScheduler;
import dev.vertique.job.cron.ExecutionMode;
import dev.vertique.job.cron.MisfirePolicy;
import dev.vertique.job.cron.OverlapPolicy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that physical cleanup is registered once through the existing cron scheduler. */
class RedisCleanupCronWiringTest {

    @Test
    @DisplayName("registers cleanup through cron with stable bounded scheduling policy")
    void cleanupIsRegisteredThroughExistingCronScheduler() {
        CronScheduler scheduler = mock(CronScheduler.class);
        List<CronJobDefinition> registrations = captureRegistrations(scheduler);

        RedisCleanupJob job = RedisCleanupJobTestSupport.job();
        job.register(scheduler);

        assertEquals(1, registrations.size());
        CronJobDefinition definition = registrations.get(0);
        assertEquals(JOB_ID, definition.id());
        assertEquals(CADENCE_MILLIS, definition.parameters().get("cadenceMs"));
        assertEquals(MAX_JITTER_MILLIS, definition.parameters().get("maxJitterMs"));
        assertEquals(ExecutionMode.EVERY_INSTANCE, definition.mode());
        assertEquals(OverlapPolicy.SKIP, definition.overlapPolicy());
        assertFalse(definition.tracked());
        assertEquals(MisfirePolicy.SKIP, definition.misfirePolicy());
    }

    @Test
    @DisplayName("registering the cleanup twice leaves one stable cron job")
    void registrationIsIdempotent() {
        CronScheduler scheduler = mock(CronScheduler.class);
        List<CronJobDefinition> registrations = captureRegistrations(scheduler);
        RedisCleanupJob job = RedisCleanupJobTestSupport.job();

        job.register(scheduler);
        job.register(scheduler);

        assertEquals(1, registrations.size());
        assertTrue(registrations.stream().allMatch(definition -> JOB_ID.equals(definition.id())));
    }

    private static List<CronJobDefinition> captureRegistrations(CronScheduler scheduler) {
        List<CronJobDefinition> registrations = new ArrayList<>();
        doAnswer(invocation -> {
                    registrations.add(invocation.getArgument(0));
                    return null;
                })
                .when(scheduler)
                .register(any(CronJobDefinition.class));
        return registrations;
    }
}
