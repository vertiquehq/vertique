// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.engine.spi.TimerCommandSpec;
import dev.vertique.workflow.engine.spi.TimerCommandSpec.FireTime;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.TimerSpec;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.timer.TimerPurpose;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies how a plan's timer spec becomes the fire time of a timer command, and what a timer command spec accepts. */
class TimerFireTimesTest {

    private static final WorkflowInstanceId INSTANCE = new WorkflowInstanceId(UUID.randomUUID());

    @Test
    @DisplayName("a delay stays a delay, so the database clock that stamps the command measures it")
    void aDelayStaysADelay() {
        AtomicInteger stateReads = new AtomicInteger();

        FireTime fireTime = TimerFireTimes.of(new TimerSpec.After(Duration.ofSeconds(5)), () -> {
            stateReads.incrementAndGet();
            return Instant.EPOCH;
        });

        assertThat(fireTime).isEqualTo(new FireTime.After(Duration.ofSeconds(5)));
        assertThat(stateReads).hasValue(0);
    }

    @Test
    @DisplayName("an instant stays an instant")
    void anInstantStaysAnInstant() {
        Instant at = Instant.parse("2030-01-01T00:00:00Z");

        assertThat(TimerFireTimes.of(new TimerSpec.At(at), () -> Instant.EPOCH)).isEqualTo(new FireTime.At(at));
    }

    @Test
    @DisplayName("a timer read from the state is an instant computed by the caller")
    void aTimerReadFromTheStateIsAnInstant() {
        Instant computed = Instant.parse("2031-02-03T04:05:06Z");

        assertThat(TimerFireTimes.of(new TimerSpec.FromState(new CallbackId("resolver")), () -> computed))
                .isEqualTo(new FireTime.At(computed));
    }

    @Test
    @DisplayName("a negative delay is taken as zero")
    void aNegativeDelayIsTakenAsZero() {
        assertThat(new FireTime.After(Duration.ofSeconds(-3)).delay()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("a task timer needs its task and a timer of a wait has none")
    void aTaskTimerNeedsItsTask() {
        FireTime fireTime = new FireTime.After(Duration.ofHours(1));

        assertThatThrownBy(() -> new TimerCommandSpec(
                        INSTANCE, TimerPurpose.TASK_DUE, "s", null, null, null, null, fireTime, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimerCommandSpec(
                        INSTANCE,
                        TimerPurpose.STANDALONE,
                        "s",
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        fireTime,
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new TimerCommandSpec(
                        INSTANCE, TimerPurpose.SIGNAL_TIMEOUT, "s", null, null, null, null, fireTime, List.of("a")))
                .extracting(TimerCommandSpec::signalNames)
                .isEqualTo(List.of("a"));
    }
}
