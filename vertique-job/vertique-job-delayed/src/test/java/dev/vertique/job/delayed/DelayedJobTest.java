// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DelayedJob} builder: field defaults, required fields, and custom values.
 */
@DisplayName("DelayedJob")
class DelayedJobTest {

    @Nested
    @DisplayName("builder defaults")
    class Defaults {

        @Test
        @DisplayName("has null payload by default")
        void defaultPayloadIsNull() {
            DelayedJob job = DelayedJob.builder().handler("my-handler").build();
            assertNull(job.payload());
        }

        @Test
        @DisplayName("has null runAt by default")
        void defaultRunAtIsNull() {
            DelayedJob job = DelayedJob.builder().handler("my-handler").build();
            assertNull(job.runAt());
        }

        @Test
        @DisplayName("has 'default' queue by default")
        void defaultQueueIsDefault() {
            DelayedJob job = DelayedJob.builder().handler("my-handler").build();
            assertEquals("default", job.queue());
        }

        @Test
        @DisplayName("has priority 0 by default")
        void defaultPriorityIsZero() {
            DelayedJob job = DelayedJob.builder().handler("my-handler").build();
            assertEquals(0, job.priority());
        }

        @Test
        @DisplayName("has maxAttempts 3 by default")
        void defaultMaxAttemptsIsThree() {
            DelayedJob job = DelayedJob.builder().handler("my-handler").build();
            assertEquals(3, job.maxAttempts());
        }

        @Test
        @DisplayName("has null jobId by default")
        void defaultJobIdIsNull() {
            DelayedJob job = DelayedJob.builder().handler("my-handler").build();
            assertNull(job.jobId());
        }
    }

    @Nested
    @DisplayName("builder with custom values")
    class CustomValues {

        @Test
        @DisplayName("sets handler correctly")
        void setsHandler() {
            DelayedJob job = DelayedJob.builder().handler("send-email").build();
            assertEquals("send-email", job.handler());
        }

        @Test
        @DisplayName("sets payload correctly")
        void setsPayload() {
            Object payload = new Object();
            DelayedJob job = DelayedJob.builder().handler("h").payload(payload).build();
            assertEquals(payload, job.payload());
        }

        @Test
        @DisplayName("sets runAt correctly")
        void setsRunAt() {
            Instant future = Instant.now().plusSeconds(60);
            DelayedJob job = DelayedJob.builder().handler("h").runAt(future).build();
            assertEquals(future, job.runAt());
        }

        @Test
        @DisplayName("sets queue correctly")
        void setsQueue() {
            DelayedJob job =
                    DelayedJob.builder().handler("h").queue("high-priority").build();
            assertEquals("high-priority", job.queue());
        }

        @Test
        @DisplayName("sets priority correctly")
        void setsPriority() {
            DelayedJob job = DelayedJob.builder().handler("h").priority(10).build();
            assertEquals(10, job.priority());
        }

        @Test
        @DisplayName("sets maxAttempts correctly")
        void setsMaxAttempts() {
            DelayedJob job = DelayedJob.builder().handler("h").maxAttempts(10).build();
            assertEquals(10, job.maxAttempts());
        }

        @Test
        @DisplayName("sets jobId correctly")
        void setsJobId() {
            DelayedJob job =
                    DelayedJob.builder().handler("h").jobId("my-unique-id").build();
            assertEquals("my-unique-id", job.jobId());
        }

        @Test
        @DisplayName("builds non-null with only handler set")
        void buildsWithHandlerOnly() {
            DelayedJob job = DelayedJob.builder().handler("some-handler").build();
            assertNotNull(job);
            assertEquals("some-handler", job.handler());
        }
    }
}
