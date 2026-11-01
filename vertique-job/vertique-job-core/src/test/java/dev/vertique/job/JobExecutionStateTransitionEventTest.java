// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.core.context.DurableMetadata;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JobExecutionStateTransitionEvent")
class JobExecutionStateTransitionEventTest {

    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

    @Test
    @DisplayName("the previous-arity constructor reports the default scope")
    void previousArityYieldsDefault() {
        JobExecutionStateTransitionEvent event = new JobExecutionStateTransitionEvent(
                UUID.randomUUID(),
                "job",
                JobType.DELAYED,
                "default",
                JobState.SUCCEEDED,
                0,
                3,
                null,
                NOW,
                NOW,
                NOW,
                NOW,
                DurableMetadata.empty());

        assertEquals("default", event.scopeKey());
    }

    @Test
    @DisplayName("a null scope on the canonical constructor reads as the default scope")
    void nullScopeYieldsDefault() {
        JobExecutionStateTransitionEvent event = new JobExecutionStateTransitionEvent(
                UUID.randomUUID(),
                "job",
                JobType.DELAYED,
                "default",
                JobState.SUCCEEDED,
                0,
                3,
                null,
                NOW,
                NOW,
                NOW,
                NOW,
                DurableMetadata.empty(),
                null);

        assertEquals("default", event.scopeKey());
    }
}
