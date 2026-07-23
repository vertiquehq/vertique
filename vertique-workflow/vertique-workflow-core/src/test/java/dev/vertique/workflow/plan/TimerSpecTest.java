// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.vertique.workflow.registry.CallbackId;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the three permitted {@link TimerSpec} variants: construction, field access, equality,
 * and exhaustive pattern matching.
 */
class TimerSpecTest {

    @Nested
    @DisplayName("At variant")
    class AtVariant {

        @Test
        @DisplayName("constructs with the given fire instant and exposes it via fireAt()")
        void constructAndAccessFireAt() {
            Instant now = Instant.now();
            TimerSpec spec = new TimerSpec.At(now);
            assertInstanceOf(TimerSpec.At.class, spec);
            assertThat(((TimerSpec.At) spec).fireAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("two At instances with equal instants are equal")
        void equalityBasedOnInstant() {
            Instant t = Instant.ofEpochSecond(1_700_000_000L);
            assertThat(new TimerSpec.At(t)).isEqualTo(new TimerSpec.At(t));
        }

        @Test
        @DisplayName("rejects null fireAt with NullPointerException")
        void rejectsNullFireAt() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TimerSpec.At(null))
                    .withMessage("fireAt");
        }
    }

    @Nested
    @DisplayName("After variant")
    class AfterVariant {

        @Test
        @DisplayName("constructs with the given delay and exposes it via delay()")
        void constructAndAccessDelay() {
            Duration d = Duration.ofMinutes(30);
            TimerSpec spec = new TimerSpec.After(d);
            assertInstanceOf(TimerSpec.After.class, spec);
            assertThat(((TimerSpec.After) spec).delay()).isEqualTo(d);
        }

        @Test
        @DisplayName("two After instances with equal durations are equal")
        void equalityBasedOnDuration() {
            Duration d = Duration.ofHours(1);
            assertThat(new TimerSpec.After(d)).isEqualTo(new TimerSpec.After(d));
        }

        @Test
        @DisplayName("rejects null delay with NullPointerException")
        void rejectsNullDelay() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TimerSpec.After(null))
                    .withMessage("delay");
        }

        @Test
        @DisplayName("rejects zero delay with IllegalArgumentException")
        void rejectsZeroDelay() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TimerSpec.After(Duration.ZERO))
                    .withMessageContaining("delay must be a positive duration");
        }

        @Test
        @DisplayName("rejects negative delay with IllegalArgumentException")
        void rejectsNegativeDelay() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TimerSpec.After(Duration.ofSeconds(-1)))
                    .withMessageContaining("delay must be a positive duration");
        }
    }

    @Nested
    @DisplayName("FromState variant")
    class FromStateVariant {

        @Test
        @DisplayName("constructs with the given CallbackId and exposes it via resolverCallbackId()")
        void constructAndAccessCallbackId() {
            CallbackId cbId = new CallbackId("my-step.timerResolver");
            TimerSpec spec = new TimerSpec.FromState(cbId);
            assertInstanceOf(TimerSpec.FromState.class, spec);
            assertThat(((TimerSpec.FromState) spec).resolverCallbackId()).isEqualTo(cbId);
        }

        @Test
        @DisplayName("two FromState instances with equal ids are equal")
        void equalityBasedOnCallbackId() {
            CallbackId id = new CallbackId("step.timerResolver");
            assertThat(new TimerSpec.FromState(id)).isEqualTo(new TimerSpec.FromState(id));
        }

        @Test
        @DisplayName("rejects null resolverCallbackId with NullPointerException")
        void rejectsNullCallbackId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TimerSpec.FromState(null))
                    .withMessage("resolverCallbackId");
        }
    }

    @Nested
    @DisplayName("pattern matching")
    class PatternMatching {

        @Test
        @DisplayName("switch exhaustively covers all three permits")
        void exhaustiveSwitchCoversAllVariants() {
            Instant t = Instant.now();
            Duration d = Duration.ofSeconds(60);
            CallbackId cbId = new CallbackId("x.resolver");

            assertThat(label(new TimerSpec.At(t))).startsWith("at:");
            assertThat(label(new TimerSpec.After(d))).startsWith("after:");
            assertThat(label(new TimerSpec.FromState(cbId))).startsWith("fromState:");
        }

        private static String label(TimerSpec spec) {
            return switch (spec) {
                case TimerSpec.At a -> "at:" + a.fireAt();
                case TimerSpec.After a -> "after:" + a.delay();
                case TimerSpec.FromState f ->
                    "fromState:" + f.resolverCallbackId().value();
            };
        }
    }
}
